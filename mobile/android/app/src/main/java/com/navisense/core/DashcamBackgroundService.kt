package com.navisense.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.navisense.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lifecycle‑aware foreground service that runs CameraX **without a PreviewView**
 * and captures frames periodically while the app is in background.
 *
 * ## Design
 * - **Foreground Service** with a persistent notification (required by Android 13+
 *   background camera access restrictions).
 * - **CameraX** is bound to a manual [LifecycleOwner] (not an Activity/Fragment)
 *   since the service runs independently of any UI.
 * - **Periodic timer**: captures one frame every [CAPTURE_INTERVAL_MS] (5 seconds).
 * - **Blur detection**: rejects blurry frames to conserve battery/bandwidth.
 * - **Backend send**: each sharp frame is sent to `POST /api/visual-locate` via
 *   [LocalizationApiClient] and the result is emitted to a `liveTrackingLocation`
 *   flow via [MainViewModel.publishDashcamLocation] (accessed via a bound interface).
 *
 * ## Thread safety
 * - All CameraX operations run on a dedicated single‑thread executor.
 * - Coroutine scope is [SupervisorJob] so a single failure doesn't cancel the loop.
 * - Notification channel created once in [onCreate].
 *
 * ## Permissions
 * Requires the following in AndroidManifest.xml:
 * - `FOREGROUND_SERVICE`
 * - `FOREGROUND_SERVICE_TYPE_CAMERA` (API 34+)
 * - `POST_NOTIFICATIONS` (API 33+)
 * - `CAMERA`
 */
class DashcamBackgroundService : Service() {


    // ── Service state ──────────────────────────────────────────────

    /** Flag to track whether the service has been initialised. */
    private val isInitialised = AtomicBoolean(false)

    /** CameraX components. */
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    /** Dedicated executor for CameraX operations (single thread). */
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Manual lifecycle for CameraX binding in a Service context. */
    private val serviceLifecycleOwner = ServiceLifecycleOwner()

    /** Coroutine scope for the periodic capture loop. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Reference to the periodic capture job (cancelled on stop). */
    private var captureJob: Job? = null

    /** FileManagerService for saving captured frames to TempScans/. */
    private lateinit var fileManagerService: FileManagerService

    /** LocalizationApiClient for sending frames to the backend. */
    private lateinit var localizationApiClient: LocalizationApiClient

    // ── Android Lifecycle ──────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DashcamBackgroundService created")

        fileManagerService = FileManagerService(this)
        localizationApiClient = LocalizationApiClient.create(this)

        createNotificationChannel()
        startForegroundWithNotification()

        // Initialise CameraX on the next main-thread tick
        android.os.Handler(mainLooper).post {
            initialiseCamera()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isInitialised.get()) {
            Log.w(TAG, "Camera not ready yet, deferring capture start")
            // Camera initialisation will trigger startCaptureLoop when ready
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null // Not a bound service

    override fun onDestroy() {
        Log.i(TAG, "DashcamBackgroundService destroying")
        stopCaptureLoop()
        serviceScope.cancel()
        cameraExecutor.execute {
            cameraProvider?.unbindAll()
            cameraProvider = null
            imageCapture = null
        }
        cameraExecutor.shutdown()
        serviceLifecycleOwner.markDestroyed()
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.dashcam_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.dashcam_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                0
            }
        )
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.dashcam_notification_title))
            .setContentText(getString(R.string.dashcam_notification_text))
            .setSmallIcon(R.drawable.ic_camera_capture)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ── CameraX Initialisation (no PreviewView) ────────────────────

    /**
     * Initialises CameraX with only the [ImageCapture] use case.
     * No Preview use case is bound — this service runs completely headless.
     */
    private fun initialiseCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1080, 1920),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setResolutionSelector(resolutionSelector)
                        .build()

                    imageCapture = capture

                    // Bind ONLY ImageCapture (no Preview) to the service lifecycle
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        serviceLifecycleOwner,
                        cameraSelector,
                        capture
                    )

                    isInitialised.set(true)
                    Log.i(TAG, "CameraX initialised successfully (headless mode)")

                    // Start the periodic capture loop once camera is ready
                    startCaptureLoop()

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialise CameraX", e)
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    // ── Periodic Capture Loop ──────────────────────────────────────

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            Log.i(TAG, "Periodic capture loop started (interval=${CAPTURE_INTERVAL_MS}ms)")
            while (isActive) {
                captureSingleFrame()
                delay(CAPTURE_INTERVAL_MS)
            }
        }
    }

    private fun stopCaptureLoop() {
        captureJob?.cancel()
        captureJob = null
        Log.i(TAG, "Periodic capture loop stopped")
    }

    /**
     * Captures a single frame via CameraX, checks blur, and if sharp,
     * sends it to the backend via [LocalizationApiClient].
     */
    private fun captureSingleFrame() {
        val capture = imageCapture ?: return
        val mainExecutor = ContextCompat.getMainExecutor(this)

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bitmap = imageProxy.toBitmap()
                        imageProxy.close()

                        // Blur detection
                        if (isImageBlurry(bitmap)) {
                            Log.d(TAG, "Dashcam frame rejected: blurry")
                            bitmap.recycle()
                            return
                        }

                        // Convert to JPEG bytes
                        val jpegBytes = bitmap.toJpegBytes(quality = 75)
                        bitmap.recycle()

                        // Save to TempScans/
                        val savedFile = fileManagerService.saveImage(jpegBytes)
                        Log.d(TAG, "Dashcam frame saved: ${savedFile.name} (${savedFile.length()} bytes)")

                        // Send to backend on IO dispatcher
                        serviceScope.launch {
                            sendFrameToBackend(savedFile)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Dashcam frame processing error: ${e.message}", e)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Dashcam capture error: ${exception.message}", exception)
                }
            }
        )
    }

    /**
     * Sends a captured frame to [LocalizationApiClient.visualLocate] and
     * broadcasts the result via a local broadcast or stores it for the
     * MapFragment to pick up.
     *
     * Note: Since this is a Service (no ViewModel access), we use a
     * [ResultBroadcast] mechanism — the MainActivity/MapFragment listens
     * for these broadcasts and updates the [MainViewModel.liveTrackingLocation] flow.
     */
    private suspend fun sendFrameToBackend(file: File) {
        try {
            val response = localizationApiClient.visualLocate(file, null)
            Log.i(TAG, "Dashcam visual-locate: lat=${response.latitude}, lon=${response.longitude}")

            // Broadcast the result so the active Activity/Fragment can pick it up
            val intent = Intent(ACTION_DASHCAM_LOCATION_UPDATE).apply {
                putExtra(EXTRA_LATITUDE, response.latitude)
                putExtra(EXTRA_LONGITUDE, response.longitude)
                putExtra(EXTRA_CONFIDENCE, response.confidence_score)
            }
            sendBroadcast(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Dashcam backend send failed: ${e.message}", e)
        } finally {
            // Clean up the temp file
            if (file.exists()) {
                file.delete()
            }
        }
    }

    // ── Blur Detection ─────────────────────────────────────────────

    /**
     * Determines whether a bitmap is blurry using Laplacian variance.
     * Mirror of [ScannerCamera.isImageBlurry] for standalone use.
     */
    private fun isImageBlurry(bitmap: Bitmap): Boolean {
        val scaledBitmap = if (bitmap.width > BLUR_SCALE_MAX || bitmap.height > BLUR_SCALE_MAX) {
            val scale = BLUR_SCALE_MAX.toFloat() / bitmap.width.coerceAtLeast(bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        val width = scaledBitmap.width
        val height = scaledBitmap.height
        val pixels = IntArray(width * height)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b)
        }

        var sum = 0.0
        var sumSq = 0.0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = gray[y * width + x]
                val left = gray[y * width + (x - 1)]
                val right = gray[y * width + (x + 1)]
                val top = gray[(y - 1) * width + x]
                val bottom = gray[(y + 1) * width + x]
                val laplacian = (left + right + top + bottom - 4 * center)
                sum += laplacian
                sumSq += laplacian * laplacian
            }
        }
        val n = (width - 2) * (height - 2).toDouble()
        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)

        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }

        return variance < BLUR_THRESHOLD
    }

    private fun Bitmap.toJpegBytes(quality: Int = 75): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    companion object {
        private const val TAG = "DashcamService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "dashcam_channel"

        /** Interval between periodic captures (milliseconds). */
        private const val CAPTURE_INTERVAL_MS = 5000L

        /** Threshold for Laplacian variance blur detection. */
        private const val BLUR_THRESHOLD = 100.0

        /** Maximum dimension for blur detection scaling. */
        private const val BLUR_SCALE_MAX = 512

        /** Intent action to stop the service gracefully. */
        const val ACTION_STOP = "com.navisense.action.STOP_DASHCAM"

        /** Action sent when the Dashcam produces a new location estimate. */
        const val ACTION_DASHCAM_LOCATION_UPDATE = "com.navisense.action.DASHCAM_LOCATION_UPDATE"

        /** Extra key for latitude (Double). */
        const val EXTRA_LATITUDE = "extra_latitude"

        /** Extra key for longitude (Double). */
        const val EXTRA_LONGITUDE = "extra_longitude"

        /** Extra key for confidence score (Float). */
        const val EXTRA_CONFIDENCE = "extra_confidence"

        /**
         * Convenience method to start the service.
         * @param context Any context (Activity or Application).
         */
        fun start(context: Context) {
            val intent = Intent(context, DashcamBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Convenience method to stop the service.
         * @param context Any context.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, DashcamBackgroundService::class.java))
        }
    }
}

/**
 * Minimal [LifecycleOwner] for use with CameraX in a non‑Activity context.
 * Starts in [Lifecycle.State.INITIALIZED], moves to [Lifecycle.State.STARTED]
 * when the service is ready, and to [Lifecycle.State.DESTROYED] when the
 * service stops.
 */
private class ServiceLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    init {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override val lifecycle: Lifecycle get() = registry

    fun markDestroyed() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
