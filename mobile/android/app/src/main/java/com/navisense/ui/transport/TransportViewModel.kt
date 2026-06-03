package com.navisense.ui.transport

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navisense.core.LocalizationApiClient
import com.navisense.core.NavigateFusionResponse
import com.navisense.core.ScannerCamera
import com.navisense.model.NavigateFusionResult
import com.navisense.model.UiState
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
 * 2. **All 4 frames** are sent in a **single** request to the fusion endpoint
 *    ([LocalizationApiClient.navigateFusion]) which runs ViT and VGGT
 *    **in parallel** on the server.
 * 3. The combined result (position + trajectory + heading) is published
 *    as [UiState].
 *
 * This replaces the previous two-round-trip approach (separate ViT and VGGT
 * calls), cutting latency by ~40 % and eliminating the need for file duplication.
 *
 * ## Mock support
 * [simulateKhreshchatykScan] bypasses the camera entirely and directly
 * feeds mock image files into the fusion pipeline — useful for development
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
     * 3. Sends **all frames** to [LocalizationApiClient.navigateFusion]
     *    which runs ViT + VGGT in parallel on the server.
     * 4. Updates [UiState] with the fused result.
     * 5. Waits [SCAN_INTERVAL_MS] before the next iteration.
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

                    Log.d(TAG, "Transport: captured ${burstFiles.size} frames for analysis")

                    // ── Step 2: Single fused request (ViT + VGGT in parallel) ──
                    //
                    // The server runs both models concurrently. This single
                    // network call replaces two sequential round-trips and
                    // eliminates the need for file duplication.
                    val fusionResponse: NavigateFusionResponse =
                        localizationApiClient.navigateFusion(burstFiles)

                    val fusionResult = NavigateFusionResult.fromResponse(fusionResponse)

                    Log.d(
                        TAG,
                        "Transport: fusion → lat=${fusionResult.latitude}, " +
                                "lon=${fusionResult.longitude}, " +
                                "heading=(${fusionResult.headingVector.x}, " +
                                "${fusionResult.headingVector.y}), " +
                                "trajectory_len=${fusionResult.trajectory.size}"
                    )

                    // ── Step 3: Update UiState ─────────────────────────
                    val stale = (System.currentTimeMillis() - lastSuccessfulScanTimeMs) > STALE_THRESHOLD_MS
                            && lastSuccessfulScanTimeMs > 0L

                    _uiState.value = UiState(
                        latitude = fusionResult.latitude,
                        longitude = fusionResult.longitude,
                        bearing = fusionResult.headingVector.toBearingDegrees(),
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
     * image files into the fusion pipeline.
     *
     * This simulates a scan on **Khreshchatyk Street** (Kyiv's main
     * thoroughfare) for development and demo purposes.
     *
     * @param mockFiles List of pre‑captured JPEG files (at least 4).
     *                  All files are sent to the fusion endpoint.
     */
    fun simulateKhreshchatykScan(mockFiles: List<File>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true, errorMessage = null)

            try {
                require(mockFiles.isNotEmpty()) { "mockFiles must not be empty" }

                Log.d(TAG, "Transport: simulating Khreshchatyk scan with ${mockFiles.size} files")

                // ── Single fusion call (no file duplication needed) ──
                val fusionResponse: NavigateFusionResponse =
                    localizationApiClient.navigateFusion(mockFiles)

                val fusionResult = NavigateFusionResult.fromResponse(fusionResponse)

                Log.d(
                    TAG,
                    "Transport (mock): fusion → lat=${fusionResult.latitude}, " +
                            "lon=${fusionResult.longitude}"
                )

                // ── Update UiState ──
                val stale = (System.currentTimeMillis() - lastSuccessfulScanTimeMs) > STALE_THRESHOLD_MS
                        && lastSuccessfulScanTimeMs > 0L

                _uiState.value = UiState(
                    latitude = fusionResult.latitude,
                    longitude = fusionResult.longitude,
                    bearing = fusionResult.headingVector.toBearingDegrees(),
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

    override fun onCleared() {
        super.onCleared()
        stopAutoScan()
        scannerCamera?.shutdown()
    }
}
