package com.navisense.ui.pedestrian

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navisense.core.LocalizationApiClient
import com.navisense.core.NavigateFusionResponse
import com.navisense.core.ScannerCamera
import com.navisense.model.NavigateFusionResult
import com.navisense.model.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * NaviSense unified debug tag for all Logcat output.
 */
private const val TAG = "NaviSense_Debug"

/**
 * ViewModel for the **Pedestrian (walking) scanning mode**.
 *
 * ## Behaviour
 * Unlike [TransportViewModel] which runs a continuous auto‑scan loop, the
 * pedestrian mode is **manual** — the user taps a button to trigger one
 * scan cycle.
 *
 * A single scan cycle via [startManualScan]:
 * 1. Captures 4 frames in quick succession via [ScannerCamera.captureBurst]
 *    with `count=4, intervalMs=500`.
 * 2. Sends **all 4 frames** in a **single** request to the fusion endpoint
 *    ([LocalizationApiClient.navigateFusion]) which runs ViT and VGGT
 *    **in parallel** on the server.
 * 3. The combined result (position + trajectory + heading) is published
 *    as [UiState].
 *
 * This replaces the previous two-round-trip approach (separate ViT and VGGT
 * calls), cutting latency by ~40 %.
 *
 * ## Mock support
 * [simulateKhreshchatykScan] bypasses the camera entirely and directly
 * feeds mock image files into the fusion pipeline — useful for development
 * and demonstration without a live camera feed.
 *
 * ## Lifecycle safety
 * A boolean [isTabActive] flag guards the manual scan against stale
 * coroutines that survive tab switches (Android Navigation Component
 * keeps fragments on the backstack alive).
 */
class PedestrianViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** Number of frames captured per manual scan. */
        private const val BURST_COUNT = 4

        /** Interval between individual frames in the burst (500 ms). */
        private const val BURST_INTERVAL_MS = 500L

        /** Staleness threshold — if no successful scan within this time, mark stale. */
        private const val STALE_THRESHOLD_MS = 120_000L
    }

    // ── Dependencies (lazily initialised) ─────────────────────────────

    /**
     * The [ScannerCamera] is set by the fragment via [setCamera] before
     * any manual scan is triggered.
     */
    private var scannerCamera: ScannerCamera? = null

    private val localizationApiClient: LocalizationApiClient by lazy {
        LocalizationApiClient.create(getApplication())
    }

    // ── Exposed State ─────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Timestamp (System.currentTimeMillis()) of the last successful scan. */
    private var lastSuccessfulScanTimeMs: Long = 0L

    /**
     * Lifecycle guard flag.
     *
     * Set to `true` when the tab is in the foreground and `false` when
     * the tab goes to the backstack (onPause).  The [startManualScan]
     * and [simulateKhreshchatykScan] methods check this flag before
     * executing the API pipeline and return early if the tab is no
     * longer active.
     */
    private var isTabActive = false

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Injects the [ScannerCamera] instance created by the fragment.
     * Must be called before [startManualScan].
     */
    fun setCamera(camera: ScannerCamera) {
        this.scannerCamera = camera
    }

    /**
     * Marks the tab as active (call from [onResume]).
     */
    fun onTabResumed() {
        isTabActive = true
    }

    /**
     * Marks the tab as inactive (call from [onPause]).
     */
    fun onTabPaused() {
        isTabActive = false
    }

    /**
     * Triggers a single manual scan cycle.
     *
     * 1. Sets [UiState.isScanning] = `true`.
     * 2. Captures [BURST_COUNT] frames at [BURST_INTERVAL_MS] intervals.
     * 3. Sends **all frames** to [LocalizationApiClient.navigateFusion]
     *    which runs ViT + VGGT in parallel on the server.
     * 4. Updates [UiState] with the fused result (position + bearing).
     *
     * If the camera is not set or any step fails, the error is published
     * via [uiState].
     */
    fun startManualScan() {
        // ── Lifecycle guard: abort if tab is no longer active ──
        if (!isTabActive) {
            Log.d(TAG, "Pedestrian: scan aborted — tab is inactive")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true, errorMessage = null)

            try {
                val camera = scannerCamera
                    ?: throw IllegalStateException("ScannerCamera not set — call setCamera() first")

                Log.d(TAG, "Pedestrian: starting manual scan (burst=$BURST_COUNT)")

                // ── Step 1: Capture burst ──────────────────────────────
                val burstFiles: List<File> = camera.captureBurst(
                    count = BURST_COUNT,
                    intervalMs = BURST_INTERVAL_MS
                )

                if (burstFiles.isEmpty()) {
                    throw IOException("Burst capture returned zero files")
                }

                Log.d(TAG, "Pedestrian: captured ${burstFiles.size} frames")

                // ── Step 2: Single fused request (ViT + VGGT in parallel) ──
                //
                // The server runs both models concurrently, so this single
                // network call replaces the previous two sequential round-trips.
                val fusionResponse: NavigateFusionResponse =
                    localizationApiClient.navigateFusion(burstFiles)

                val fusionResult = NavigateFusionResult.fromResponse(fusionResponse)

                Log.d(
                    TAG,
                    "Pedestrian: fusion → lat=${fusionResult.latitude}, " +
                            "lon=${fusionResult.longitude}, " +
                            "heading=(${fusionResult.headingVector.x}, " +
                            "${fusionResult.headingVector.y}), " +
                            "trajectory_len=${fusionResult.trajectory.size}"
                )

                // ── Step 3: Update UiState ─────────────────────────────
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

                Log.d(TAG, "Pedestrian: manual scan complete — state updated")

            } catch (e: IOException) {
                Log.e(TAG, "Pedestrian: scan failed (IO): ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    errorMessage = e.message ?: "Network error during scan"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Pedestrian: scan failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    errorMessage = e.message ?: "Unexpected error during scan"
                )
            }
        }
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
        // ── Lifecycle guard: abort if tab is no longer active ──
        if (!isTabActive) {
            Log.d(TAG, "Pedestrian (mock): scan aborted — tab is inactive")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true, errorMessage = null)

            try {
                require(mockFiles.isNotEmpty()) { "mockFiles must not be empty" }

                Log.d(TAG, "Pedestrian: simulating Khreshchatyk scan with ${mockFiles.size} files")

                // ── Single fusion call (no file duplication needed) ──
                val fusionResponse: NavigateFusionResponse =
                    localizationApiClient.navigateFusion(mockFiles)

                val fusionResult = NavigateFusionResult.fromResponse(fusionResponse)

                Log.d(
                    TAG,
                    "Pedestrian (mock): fusion → lat=${fusionResult.latitude}, " +
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

                Log.d(TAG, "Pedestrian (mock): scan complete — state updated")

            } catch (e: IOException) {
                Log.e(TAG, "Pedestrian (mock): scan failed (IO): ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    errorMessage = e.message ?: "Mock network error"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Pedestrian (mock): scan failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    errorMessage = e.message ?: "Unexpected mock error"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        isTabActive = false
        scannerCamera?.shutdown()
    }
}
