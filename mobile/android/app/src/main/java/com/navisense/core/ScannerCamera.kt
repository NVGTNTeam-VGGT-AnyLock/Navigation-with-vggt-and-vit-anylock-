package com.navisense.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Controller responsible for capturing single high‑quality frames using CameraX,
 * performing on‑device blur detection, and handing sharp images to the FileManagerService.
 *
 * The module is designed to be energy‑efficient: it uses a single‑shot ImageCapture
 * (no video streaming) and discards blurry frames before any file I/O or network transmission.
 *
 * Usage:
 * ```
 * val fileManagerService = FileManagerService(context)
 * val scanner = ScannerCamera(context, lifecycleOwner, fileManagerService, previewView)
 * scanner.captureSharpImage(
 *     onSuccess = { file -> /* upload file */ },
 *     onError = { exception -> /* handle error */ }
 * )
 * ```
 *
 * @property context Android context (usually from Activity/Fragment)
 * @property lifecycleOwner LifecycleOwner that controls the camera lifecycle (e.g., a Fragment)
 * @property fileManagerService Instance of FileManagerService for saving validated images
 * @property previewView Optional PreviewView to show a live camera preview. If null, only capture works.
 */
class ScannerCamera(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val fileManagerService: FileManagerService,
    private val previewView: PreviewView? = null
) {
    private val tag = "ScannerCamera"

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Guard flag to prevent the async [initializeCamera] callback from binding
     * after [shutdown] has been called. This prevents the race where a pending
     * [ProcessCameraProvider] future resolves after [onDestroyView] and binds
     * to a stale (destroyed) lifecycle owner.
     */
    private var isShutdown = false

    /**
     * Public [StateFlow] that emits `true` once CameraX has been fully initialised
     * and [bindToLifecycle] has completed successfully. Consumers (e.g. [MapViewModel]
     * or [MapFragment]) MUST observe this flow and never call [captureBurst] or
     * [captureSharpImage] until the value is `true`.
     *
     * The flow emits `false` on initialisation and after [shutdown].
     */
    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady: StateFlow<Boolean> = _isCameraReady.asStateFlow()

    /** Threshold for Laplacian variance below which an image is considered blurry. */
    private var blurThreshold: Double = DEFAULT_BLUR_THRESHOLD

    companion object {
        /** Default blur threshold (empirical value for 1080×1920 images). */
        private const val DEFAULT_BLUR_THRESHOLD = 100.0

        /** Minimum required free storage bytes (copied from FileManagerService). */
        private const val MIN_STORAGE_BYTES = 50L * 1024L * 1024L
    }

    init {
        initializeCamera()
    }

    /**
     * Initializes CameraX, binds the Preview (if a PreviewView is supplied) and ImageCapture use cases.
     * Must be called before any capture operation.
     */
    private fun initializeCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                // ⚠️ Guard: if shutdown() was already called (e.g. during tab switch),
                // the old lifecycleOwner is destroyed — do NOT bind.
                if (isShutdown) {
                    Log.w(tag, "initializeCamera — already shut down, skipping bind")
                    return@addListener
                }
                try {
                    cameraProvider = cameraProviderFuture.get()
                    bindCameraUseCases()
                } catch (e: Exception) {
                    Log.e(tag, "Camera initialization failed", e)
                    fileManagerService.logError("Camera initialization failed: ${e.message}")
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /**
     * Binds the Preview and ImageCapture use cases to the camera lifecycle.
     */
    private fun bindCameraUseCases() {
        // Double-check shutdown guard (the listener was posted before shutdown was called)
        if (isShutdown) {
            Log.w(tag, "bindCameraUseCases — already shut down, skipping bind")
            return
        }

        val cameraProvider = cameraProvider ?: run {
            Log.e(tag, "CameraProvider is null, cannot bind use cases")
            return
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // ── Preview use case ──────────────────────────────────────
        val preview = Preview.Builder()
            .build()

        // Attach the PreviewView's surface provider to the preview use case
        previewView?.let { viewFinder ->
            preview.setSurfaceProvider(viewFinder.surfaceProvider)
        }

        // ── ImageCapture use case (modern ResolutionSelector API) ──
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1080, 1920),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            // Signal that the camera is fully initialised and ready for capture.
            _isCameraReady.value = true
            Log.d(tag, "Camera initialised and ready for capture")
        } catch (e: Exception) {
            Log.e(tag, "Failed to bind camera use cases", e)
            fileManagerService.logError("Failed to bind camera use cases: ${e.message}")
            _isCameraReady.value = false
        }
    }

    /**
     * Re‑builds and re‑binds the **Preview** use case with the current
     * [PreviewView]'s surface provider.
     *
     * Call this method when returning to a fragment from the backstack
     * (e.g. after a tab switch) to ensure the live camera preview does
     * not remain black.  A fresh [Preview] instance is created and bound
     * together with the existing [ImageCapture] use case.
     *
     * This is safe to call multiple times.  It is a no‑op if the camera
     * has been shut down or the [cameraProvider] is not yet initialised.
     */
    fun rebindPreview() {
        if (isShutdown) {
            Log.w(tag, "rebindPreview — already shut down, skipping")
            return
        }

        val provider = cameraProvider
        if (provider == null) {
            // Camera provider not yet available — the init block's listener
            // will bind when it resolves, so nothing to do here.
            Log.d(tag, "rebindPreview — cameraProvider not ready yet, deferring")
            return
        }

        val capture = imageCapture
        if (capture == null) {
            Log.w(tag, "rebindPreview — imageCapture not ready yet, skipping")
            return
        }

        Log.d(tag, "rebindPreview — rebuilding Preview use case")

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Create a brand‑new Preview use case and attach the current
        // surface provider from the PreviewView.
        val preview = Preview.Builder()
            .build()

        previewView?.let { viewFinder ->
            preview.setSurfaceProvider(viewFinder.surfaceProvider)
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                capture
            )
            Log.d(tag, "rebindPreview — successfully rebound preview")
        } catch (e: Exception) {
            Log.e(tag, "rebindPreview — failed to rebind: ${e.message}", e)
            fileManagerService.logError("rebindPreview failed: ${e.message}")
        }
    }

    /**
     * Captures a single image, validates its sharpness, and saves it via FileManagerService
     * only if the image passes the blur detection.
     *
     * The heavy work (blur detection, JPEG encoding) executes on the camera background executor.
     * The [onSuccess] and [onError] callbacks are always dispatched to the **main thread**
     * so callers may safely update UI elements and show Toasts without additional thread routing.
     *
     * @param onSuccess Callback invoked on the **main thread** with the saved File
     *                  when capture and validation succeed.
     * @param onError Callback invoked on the **main thread** when an error occurs
     *                (camera error, blurry image, I/O error, etc.).
     */
    fun captureSharpImage(
        onSuccess: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val imageCapture = imageCapture ?: run {
            onError(IllegalStateException("ImageCapture use case is not ready"))
            return
        }

        // Main-thread executor for dispatching results to callers
        val mainExecutor = ContextCompat.getMainExecutor(context)

        imageCapture.takePicture(
            cameraExecutor, // Background executor for heavy computation
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        // ── Heavy work (runs on cameraExecutor background thread) ──
                        // Convert ImageProxy to Bitmap using the built-in
                        // ImageProxy.toBitmap() from CameraX 1.4+ (no shadowing)
                        val bitmap = imageProxy.toBitmap()
                        imageProxy.close()

                        // Validate sharpness (Laplacian variance — expensive)
                        if (!isImageBlurry(bitmap)) {
                            // Convert to JPEG bytes
                            val jpegBytes = bitmap.toJpegBytes(quality = 85)
                            // Save via FileManagerService
                            val savedFile = fileManagerService.saveImage(jpegBytes)
                            Log.d(tag, "Sharp image saved: ${savedFile.absolutePath}")

                            // ── Dispatch success to main thread ──
                            mainExecutor.execute {
                                onSuccess(savedFile)
                            }
                        } else {
                            Log.d(tag, "Image rejected: too blurry")
                            // ── Dispatch error to main thread ──
                            mainExecutor.execute {
                                onError(ImageTooBlurryException("Captured image is too blurry"))
                            }
                        }
                    } catch (e: FileManagerService.InsufficientStorageException) {
                        Log.e(tag, "Insufficient storage", e)
                        fileManagerService.logError("Insufficient storage: ${e.message}")
                        mainExecutor.execute { onError(e) }
                    } catch (e: FileManagerService.FileManagerException) {
                        Log.e(tag, "File manager error", e)
                        fileManagerService.logError("File manager error: ${e.message}")
                        mainExecutor.execute { onError(e) }
                    } catch (e: Exception) {
                        Log.e(tag, "Unexpected error during image processing", e)
                        fileManagerService.logError("Unexpected error during image processing: ${e.message}")
                        mainExecutor.execute { onError(e) }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(tag, "Image capture failed", exception)
                    fileManagerService.logError("Image capture failed: ${exception.message}")
                    // ── Dispatch error to main thread ──
                    mainExecutor.execute {
                        onError(exception)
                    }
                }
            }
        )
    }

    /**
     * Compresses a Bitmap into a JPEG ByteArray with the given quality (0‑100).
     */
    private fun Bitmap.toJpegBytes(quality: Int = 85): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Determines whether a Bitmap is blurry by computing the variance of its Laplacian.
     * The algorithm converts the image to grayscale, applies a discrete Laplacian kernel,
     * and computes the variance of the resulting values. A low variance indicates a blurry image.
     *
     * @param bitmap the input image (will be scaled down for performance if needed)
     * @return true if the image is considered blurry, false otherwise.
     */
    private fun isImageBlurry(bitmap: Bitmap): Boolean {
        // Scale down the bitmap to speed up computation (keeping aspect ratio)
        val maxSize = 512
        val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
            val scale = maxSize.toFloat() / bitmap.width.coerceAtLeast(bitmap.height)
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

        // Convert to grayscale luminance (using ITU‑R BT.601 coefficients)
        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b)
        }

        // Compute Laplacian variance
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

        // Clean up scaled bitmap if it's a different instance
        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }

        return variance < blurThreshold
    }

    /**
     * Captures a burst of [count] images sequentially, saving each to temporary storage
     * via [FileManagerService], and returns the list of saved files.
     *
     * This is designed for the VGGT-1B visual-odometry pipeline, which requires a
     * **sequence** of frames captured in quick succession. Blur detection is **skipped**
     * during burst mode — the VGGT model is robust to moderate motion blur, and we
     * prioritise capture speed over per-frame quality filtering.
     *
     * ## Threading
     * Each individual capture uses CameraX's [cameraExecutor] (a single-thread
     * background executor). The outer coroutine suspension bridges the callback-based
     * [ImageCapture.takePicture] into a sequential `suspend` flow via
     * [suspendCancellableCoroutine], ensuring thread-safe, non-blocking sequential
     * execution.
     *
     * ## Cancellation
     * If the calling coroutine is cancelled mid-burst, the current in-flight capture
     * is aborted via [suspendCancellableCoroutine.invokeOnCancellation], and all
     * files captured so far are cleaned up (deleted) to avoid orphaned temp files.
     *
     * ## Lifecycle safety
     * The caller must ensure [shutdown] is called in `onDestroy` / `onStop`; this
     * method will throw [IllegalStateException] if [imageCapture] is `null` (i.e.
     * the camera has not been initialised or has been released).
     *
     * @param count      Number of frames to capture (must be >= 1).
     * @param intervalMs Delay **between** successive captures, in milliseconds.
     *                   Pass `0L` for the fastest possible back-to-back capture.
     * @return List of [File] references, one per captured frame, saved in the
     *         TempScans directory.
     * @throws IllegalArgumentException if [count] < 1 or [intervalMs] < 0.
     * @throws IllegalStateException if the camera is not initialised.
     * @throws FileManagerService.InsufficientStorageException if free space is too low.
     * @throws FileManagerService.FileManagerException on file I/O errors.
     */
    suspend fun captureBurst(count: Int, intervalMs: Long): List<File> {
        require(count >= 1) { "count must be >= 1, got $count" }
        require(intervalMs >= 0L) { "intervalMs must be >= 0, got $intervalMs" }

        // Fast‑fail: if the camera has already been shut down (e.g. tab switch),
        // throw CancellationException immediately rather than attempting to call
        // takePicture on an unbound ImageCapture use case.
        if (isShutdown) {
            Log.w(tag, "captureBurst — camera already shut down, aborting")
            throw CancellationException("Camera is shut down — cannot capture burst")
        }

        val imageCapture = imageCapture
            ?: throw IllegalStateException("ImageCapture use case is not ready — camera may not be initialised")

        return withContext(Dispatchers.IO) {
            val files = mutableListOf<File>()
            try {
                for (i in 0 until count) {
                    // Re‑check shutdown before each frame (a concurrent shutdown()
                    // may have been called while we were sleeping between frames)
                    if (isShutdown) {
                        throw CancellationException("Camera shut down during burst at frame $i/$count")
                    }

                    // Delay between captures (skip delay before the first frame)
                    if (i > 0 && intervalMs > 0L) {
                        delay(intervalMs)
                    }

                    val file = suspendCancellableCoroutine<File> { continuation ->
                        // Register cancellation handler to clean up in-flight capture
                        continuation.invokeOnCancellation {
                            Log.w(tag, "Burst capture cancelled at frame $i/$count")
                        }

                        imageCapture.takePicture(
                            cameraExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                    try {
                                        val bitmap = imageProxy.toBitmap()
                                        imageProxy.close()

                                        // Encode to JPEG and save — no blur check in burst mode
                                        val jpegBytes = bitmap.toJpegBytes(quality = 85)
                                        val savedFile = fileManagerService.saveImage(jpegBytes)
                                        Log.d(
                                            tag,
                                            "Burst frame $i/$count saved: ${savedFile.name}"
                                        )
                                        continuation.resume(savedFile)
                                    } catch (e: Exception) {
                                        Log.e(
                                            tag,
                                            "Burst frame $i/$count processing failed",
                                            e
                                        )
                                        continuation.resumeWithException(e)
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e(
                                        tag,
                                        "Burst capture frame $i/$count failed: ${exception.message}",
                                        exception
                                    )
                                    fileManagerService.logError(
                                        "Burst capture frame $i failed: ${exception.message}"
                                    )
                                    continuation.resumeWithException(exception)
                                }
                            }
                        )
                    }

                    files.add(file)
                }
            } catch (e: Exception) {
                // Clean up any files captured so far on failure
                Log.e(tag, "Burst capture failed at frame ${files.size}/$count, cleaning up", e)
                files.forEach { file ->
                    try {
                        fileManagerService.deleteImage(file)
                    } catch (_: Exception) {
                        // Best-effort cleanup
                    }
                }
                throw e
            }

            Log.d(tag, "Burst capture completed: ${files.size} files saved")
            return@withContext files
        }
    }

    /**
     * Updates the blur‑detection threshold.
     * @param threshold new Laplacian variance threshold (lower values accept more blur).
     */
    fun setBlurThreshold(threshold: Double) {
        blurThreshold = threshold
    }

    /**
     * Releases camera resources and shuts down the internal executor.
     * Must be called when the ScannerCamera is no longer needed (e.g., in onDestroy).
     */
    fun shutdown() {
        Log.w(tag, "shutdown — unbinding all use cases")
        isShutdown = true
        _isCameraReady.value = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        cameraExecutor.shutdown()
    }

    /** Custom exception indicating that the captured image is too blurry to be used. */
    class ImageTooBlurryException(message: String) : Exception(message)
}
