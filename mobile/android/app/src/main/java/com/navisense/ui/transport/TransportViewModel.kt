package com.navisense.ui.transport

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navisense.core.LocalizationApiClient
import com.navisense.core.ScannerCamera
import com.navisense.core.VggtOdometryResponse
import com.navisense.core.VisualLocateResponse
import com.navisense.model.UiState
import com.navisense.model.VggtOdometryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * NaviSense unified debug tag for all Logcat output.
 */
private const val TAG = "NaviSense_Debug"

/**
 * ViewModel for the **Transport (vehicle) scanning mode**.
 *
 * ## Behaviour
 * When [startAutoScan] is called, a periodic coroutine loop begins:
 * 1. Every 5 s a burst of 4 frames is captured via [ScannerCamera].
 * 2. The **first frame** is sent to the ViT endpoint
 *    ([LocalizationApiClient.visualLocate]) for absolute position
 *    (latitude / longitude).
 * 3. **All 4 frames** are sent to the VGGT-1B endpoint
 *    ([LocalizationApiClient.vggtOdometry]) for relative bearing.
 * 4. The combined result is published as [UiState].
 * 5. If either API call fails, the error is surfaced through [uiState].
 *
 * ## Mock support
 * [simulateKhreshchatykScan] bypasses the camera entirely and directly
 * feeds mock image files into the API pipeline — useful for development
 * and demonstration without a live camera feed.
 *
 * ## Staleness
 * Every successful scan resets the staleness timer. If more than 120 s
 * have elapsed since the last successful scan, [UiState.isStale] flips
 * to `true`.
 *
 * ## Lifecycle safety
 * A boolean [isTabActive] flag guards the auto‑scan loop against
 * stale coroutines that survive tab switches (Android Navigation
 * Component keeps fragments on the backstack alive).
 */
class TransportViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** Auto‑scan interval between successive burst cycles. */
        private const val SCAN_INTERVAL_MS = 5000L

        /** Number of frames captured per burst. */
        private const val BURST_COUNT = 4

        /** Interval between individual frames in a burst. */
        private const val BURST_INTERVAL_MS = 500L

        /** Staleness threshold — if no successful scan within this time, mark stale. */
        private const val STALE_THRESHOLD_MS = 120_000L
    }

    // ── Dependencies (lazily initialised) ─────────────────────────────

    /**
     * The [ScannerCamera] is created lazily so it survives configuration
     * changes. **Important:** The fragment must provide the [PreviewView]
     * via [setPreviewView] before [startAutoScan] is called.
     */
    private var scannerCamera: ScannerCamera? = null

    private val localizationApiClient: LocalizationApiClient by lazy {
        LocalizationApiClient.create(getApplication())
    }

    // ── Exposed State ─────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Reference to the current auto‑scan coroutine job (for cancellation). */
    private var autoScanJob: Job? = null

    /** Timestamp (System.currentTimeMillis()) of the last successful scan. */
    private var lastSuccessfulScanTimeMs: Long = 0L

    /**
     * Lifecycle guard flag.
     *
     * Set to `true` when [startAutoScan] is called (typically from
     * [onResume]) and `false` when [stopAutoScan] is called (typically
     * from [onPause]).  The auto‑scan coroutine loop checks this flag
     * **before** every [ScannerCamera.captureBurst] call and exits
     * immediately if the tab is no longer active.  This is a defence
     * in depth alongside coroutine cancellation: Navigation Component
     * keeps fragments on the backstack alive, so their [viewModelScope]
     * remains active and coroutine cancellation alone may not prevent
     * a stale burst from firing.
     */
    private var isTabActive = false

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Injects the [ScannerCamera] instance created by the fragment.
     * Must be called before [startAutoScan].
     */
    fun setCamera(camera: ScannerCamera) {
        this.scannerCamera = camera
    }

    /**
     * Starts the auto‑scan loop.
     *
     * The loop runs until [stopAutoScan] is called or the ViewModel is cleared.
     * Each iteration:
     * 1. Sets [UiState.isScanning] = `true`.
     * 2. Captures a burst of [BURST_COUNT] frames.
     * 3. Sends the first frame to [LocalizationApiClient.visualLocate] (ViT).
     * 4. Sends all frames to [LocalizationApiClient.vggtOdometry] (VGGT).
     * 5. Updates [UiState] with the fused result.
     * 6. Waits [SCAN_INTERVAL_MS] before the next iteration.
     *
     * Safe to call multiple times — previous job is cancelled first.
     */
    fun startAutoScan() {
        isTabActive = true
        autoScanJob?.cancel()
        autoScanJob = viewModelScope.launch {
            Log.d(TAG, "Transport: auto‑scan started")

            while (isActive) {
                // ── Lifecycle guard: return immediately if tab is inactive ──
                if (!isTabActive) {
                    Log.d(TAG, "Transport: tab no longer active, exiting auto‑scan loop")
                    _uiState.value = _uiState.value.copy(isScanning = false)
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isScanning = true, errorMessage = null)

                try {
                    // ── Step 1: Capture burst ──────────────────────────
                    val camera = scannerCamera
                        ?: throw IllegalStateException("ScannerCamera not set — call setCamera() first")

                    val burstFiles: List<File> = camera.captureBurst(
                        count = BURST_COUNT,
                        intervalMs = BURST_INTERVAL_MS
                    )

                    if (burstFiles.isEmpty()) {
                        throw IOException("Burst capture returned zero files")
                    }

                    Log.d(
                        TAG,
                        "Transport: captured ${burstFiles.size} frames for analysis"
                    )

                    // ── Step 2: ViT visual place recognition (first frame) ──
                    //
                    // ⚠️  visualLocate internally DELETES the file after a
                    // successful response (see LocalizationApiClient.visualLocate).
                    // To prevent the first frame from being destroyed before
                    // vggtOdometry can use it, we make a throwaway copy.
                    val firstFrameOriginal = burstFiles.first()
                    val firstFrameVit = duplicateFile(firstFrameOriginal)

                    val vitResponse: VisualLocateResponse = localizationApiClient.visualLocate(
                        file = firstFrameVit,
                        locationScope = null // Full‑world search for transport
                    )

                    Log.d(
                        TAG,
                        "Transport: ViT locate → lat=${vitResponse.latitude}, " +
                                "lon=${vitResponse.longitude}, " +
                                "confidence=${vitResponse.confidence_score}"
                    )

                    // ── Step 3: VGGT-1B visual odometry (all frames) ──
                    val vggtResponse: VggtOdometryResponse =
                        localizationApiClient.vggtOdometry(burstFiles)

                    val odometryResult = VggtOdometryResult.fromResponse(vggtResponse)

                    Log.d(
                        TAG,
                        "Transport: VGGT odometry → bearing=${odometryResult.bearingDegrees}°"
                    )

                    // ── Step 4: Fuse results into UiState ──────────────
                    val stale = (System.currentTimeMillis() - lastSuccessfulScanTimeMs) > STALE_THRESHOLD_MS
                            && lastSuccessfulScanTimeMs > 0L

                    _uiState.value = UiState(
                        latitude = vitResponse.latitude,
                        longitude = vitResponse.longitude,
                        bearing = odometryResult.bearingDegrees,
                        isStale = stale,
                        isScanning = false
                    )

                    lastSuccessfulScanTimeMs = System.currentTimeMillis()

                } catch (e: IOException) {
                    Log.e(TAG, "Transport: scan cycle failed (IO): ${e.message}", e)
                    _uiState.value = _uiState.value.copy(
                        isScanning = false,
                        errorMessage = e.message ?: "Network error during scan"
                    )
                } catch (e: CancellationException) {
                    Log.d(TAG, "Transport: auto‑scan cancelled")
                    _uiState.value = _uiState.value.copy(isScanning = false)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Transport: scan cycle failed: ${e.message}", e)
                    _uiState.value = _uiState.value.copy(
                        isScanning = false,
                        errorMessage = e.message ?: "Unexpected error during scan"
                    )
                }

                // ── Wait before next cycle ─────────────────────────────
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops the auto‑scan loop. The current burst completes, but no
     * further cycles are scheduled.
     */
    fun stopAutoScan() {
        isTabActive = false
        autoScanJob?.cancel()
        autoScanJob = null
        _uiState.value = _uiState.value.copy(isScanning = false)
        Log.d(TAG, "Transport: auto‑scan stopped")
    }

    /**
     * Clears the last error from the UI state so the UI does not
     * re‑display a stale error snackbar.
     */
    fun clearError() {
        _uiState.value = _uiState.value.clearError()
    }

    /**
     * Mock trigger — bypasses the camera and directly feeds the provided
     * image files into the VGGT‑1B and ViT pipeline.
     *
     * This simulates a scan on **Khreshchatyk Street** (Kyiv's main
     * thoroughfare) for development and demo purposes.
     *
     * @param mockFiles List of pre‑captured JPEG files (at least 4).
     *                  The first file is sent to ViT; all files are sent
     *                  to VGGT-1B.
     */
    fun simulateKhreshchatykScan(mockFiles: List<File>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true, errorMessage = null)

            try {
                require(mockFiles.isNotEmpty()) { "mockFiles must not be empty" }

                Log.d(TAG, "Transport: simulating Khreshchatyk scan with ${mockFiles.size} files")

                // ⚠️  Duplicate the first frame so visualLocate can delete
                // its copy without destroying the original needed by vggtOdometry.
                val firstFrameOriginal = mockFiles.first()
                val firstFrameVit = duplicateFile(firstFrameOriginal)

                // ── ViT visual place recognition (first frame) ──
                val vitResponse: VisualLocateResponse = localizationApiClient.visualLocate(
                    file = firstFrameVit,
                    locationScope = "Kyiv"
                )

                Log.d(
                    TAG,
                    "Transport (mock): ViT locate → lat=${vitResponse.latitude}, " +
                            "lon=${vitResponse.longitude}"
                )

                // ── VGGT-1B visual odometry (all files) ──
                val vggtResponse: VggtOdometryResponse =
                    localizationApiClient.vggtOdometry(mockFiles)

                val odometryResult = VggtOdometryResult.fromResponse(vggtResponse)

                Log.d(
                    TAG,
                    "Transport (mock): VGGT odometry → bearing=${odometryResult.bearingDegrees}°"
                )

                // ── Fuse results ──
                val stale = (System.currentTimeMillis() - lastSuccessfulScanTimeMs) > STALE_THRESHOLD_MS
                        && lastSuccessfulScanTimeMs > 0L

                _uiState.value = UiState(
                    latitude = vitResponse.latitude,
                    longitude = vitResponse.longitude,
                    bearing = odometryResult.bearingDegrees,
                    isStale = stale,
                    isScanning = false
                )

                lastSuccessfulScanTimeMs = System.currentTimeMillis()

                Log.d(TAG, "Transport (mock): scan complete — state updated")

            } catch (e: IOException) {
                Log.e(TAG, "Transport (mock): scan failed (IO): ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    errorMessage = e.message ?: "Mock network error"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Transport (mock): scan failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    errorMessage = e.message ?: "Unexpected mock error"
                )
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Creates an independent copy of [file] in the app's cache directory.
     *
     * The copy is used as a throwaway argument to [LocalizationApiClient.visualLocate],
     * which deletes the file after a successful network response.  By duping the
     * first frame we preserve the original for the subsequent
     * [LocalizationApiClient.vggtOdometry] call.
     *
     * @return The newly created temporary copy.
     * @throws IOException if the copy operation fails.
     */
    private fun duplicateFile(file: File): File {
        val tempFile = java.io.File.createTempFile("vit_copy_", ".jpg", getApplication<Application>().cacheDir)
        java.nio.file.Files.copy(file.toPath(), tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Log.d(TAG, "Duplicated ${file.name} → ${tempFile.name} for ViT safety")
        return tempFile
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoScan()
        scannerCamera?.shutdown()
    }
}
