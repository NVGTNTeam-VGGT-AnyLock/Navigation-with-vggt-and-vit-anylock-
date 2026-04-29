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
        } catch (e: Exception) {
            Log.e(tag, "Failed to bind camera use cases", e)
            fileManagerService.logError("Failed to bind camera use cases: ${e.message}")
        }
    }

    /**
     * Captures a single image, validates its sharpness, and saves it via FileManagerService
     * only if the image passes the blur detection.
     *
     * @param onSuccess Callback invoked with the saved File when capture and validation succeed.
     * @param onError Callback invoked when an error occurs (camera error, blurry image, I/O error, etc.).
     */
    fun captureSharpImage(
        onSuccess: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val imageCapture = imageCapture ?: run {
            onError(IllegalStateException("ImageCapture use case is not ready"))
            return
        }

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        // Convert ImageProxy to Bitmap using the built-in
                        // ImageProxy.toBitmap() from CameraX 1.4+ (no shadowing)
                        val bitmap = imageProxy.toBitmap()
                        imageProxy.close()

                        // Validate sharpness
                        if (!isImageBlurry(bitmap)) {
                            // Convert to JPEG bytes
                            val jpegBytes = bitmap.toJpegBytes(quality = 85)
                            // Save via FileManagerService
                            val savedFile = fileManagerService.saveImage(jpegBytes)
                            Log.d(tag, "Sharp image saved: ${savedFile.absolutePath}")
                            onSuccess(savedFile)
                        } else {
                            Log.d(tag, "Image rejected: too blurry")
                            onError(ImageTooBlurryException("Captured image is too blurry"))
                        }
                    } catch (e: FileManagerService.InsufficientStorageException) {
                        Log.e(tag, "Insufficient storage", e)
                        fileManagerService.logError("Insufficient storage: ${e.message}")
                        onError(e)
                    } catch (e: FileManagerService.FileManagerException) {
                        Log.e(tag, "File manager error", e)
                        fileManagerService.logError("File manager error: ${e.message}")
                        onError(e)
                    } catch (e: Exception) {
                        Log.e(tag, "Unexpected error during image processing", e)
                        fileManagerService.logError("Unexpected error during image processing: ${e.message}")
                        onError(e)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(tag, "Image capture failed", exception)
                    fileManagerService.logError("Image capture failed: ${exception.message}")
                    onError(exception)
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
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        cameraExecutor.shutdown()
    }

    /** Custom exception indicating that the captured image is too blurry to be used. */
    class ImageTooBlurryException(message: String) : Exception(message)
}
