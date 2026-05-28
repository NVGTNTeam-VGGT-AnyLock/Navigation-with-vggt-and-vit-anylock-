package com.navisense.ui.pedestrian

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.navisense.R
import com.navisense.core.FileManagerService
import com.navisense.core.ScannerCamera
import com.navisense.databinding.FragmentPedestrianBinding
import com.navisense.model.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * NaviSense unified debug tag for all Logcat output.
 */
private const val TAG = "NaviSense_Debug"

/**
 * Pedestrian (walking) scanning fragment.
 *
 * ## Layout
 * - Full‑screen [PreviewView] showing the live camera feed.
 * - A translucent overlay with a progress spinner shown during the capture burst + API calls.
 * - An [ExtendedFloatingActionButton] "Locate Me" at the bottom centre.
 * - A countdown text label that shows "3", "2", "1" during the 2‑second burst.
 * - A floating result card (top) that displays coordinates and bearing after a successful scan.
 * - A "Mock: Khreshchatyk" button (top‑right) for development/testing.
 *
 * ## Behaviour
 * On start, the fragment creates a [ScannerCamera] and binds it to the full‑screen preview.
 * The user taps "Locate Me" to trigger a manual scan (4‑frame burst over 2 s).
 * The countdown provides visual feedback during the burst.
 * Results are displayed via the floating result card and a Snackbar.
 */
class PedestrianFragment : Fragment() {

    private var _binding: FragmentPedestrianBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PedestrianViewModel by viewModels()

    /** Camera controller – created in [onViewCreated], released in [onDestroyView]. */
    private var scannerCamera: ScannerCamera? = null

    /** File manager for temporary image storage. */
    private var fileManagerService: FileManagerService? = null

    /** Job that drives the countdown animation during burst capture. */
    private var countdownJob: Job? = null

    /** Launcher for the system camera permission dialog. */
    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                // Permission granted – proceed with camera init
                initCamera()
            } else {
                // Permission denied – inform the user
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        R.string.permission_camera_denied,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPedestrianBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── 1. Wire Locate Me Button ──────────────────────────────
        initLocateMeButton()

        // ── 2. Wire Mock Button ───────────────────────────────────
        initMockButton()

        // ── 3. Observe ViewModel state ────────────────────────────
        observeUiState()
    }

    // ── Camera Initialisation ──────────────────────────────────────

    private fun initCamera() {
        val context = requireContext()
        val lifecycleOwner = viewLifecycleOwner

        // Check camera permission
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Request camera permission via system dialog
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        fileManagerService = FileManagerService(context)

        val camera = ScannerCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            fileManagerService = fileManagerService!!,
            previewView = binding.cameraPreviewView // Full‑screen preview
        )
        scannerCamera = camera

        // Inject camera into ViewModel
        viewModel.setCamera(camera)
    }

    // ── Locate Me Button ───────────────────────────────────────────

    private fun initLocateMeButton() {
        binding.fabLocateMe.setOnClickListener {
            // Disable the button during the scan to prevent double‑trigger
            binding.fabLocateMe.isEnabled = false

            // Start countdown (3…2…1) then trigger the scan
            startCountdownAndScan()
        }
    }

    /**
     * Runs a 3‑2‑1 countdown overlay, then triggers [PedestrianViewModel.startManualScan].
     *
     * The countdown provides visual feedback during the ~2 s burst capture period.
     */
    private fun startCountdownAndScan() {
        countdownJob?.cancel()
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Show countdown overlay
                binding.tvCountdown.visibility = View.VISIBLE

                // Countdown: 3, 2, 1
                for (i in 3 downTo 1) {
                    binding.tvCountdown.text = i.toString()
                    delay(500L) // 500 ms per digit
                }

                // "Scan!" flash
                binding.tvCountdown.text = getString(R.string.pedestrian_scan_now)
                delay(200L)

                // Hide countdown
                binding.tvCountdown.visibility = View.GONE

                // Trigger the actual scan
                viewModel.startManualScan()

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Fragment destroyed or re‑triggered – clean up
                binding.tvCountdown.visibility = View.GONE
                binding.fabLocateMe.isEnabled = true
            }
        }
    }

    // ── Mock Button ────────────────────────────────────────────────

    private fun initMockButton() {
        binding.btnMockKhreshchatyk.setOnClickListener {
            loadMockFilesAndSimulate()
        }
    }

    /**
     * Loads the 4 mock JPEG frames from [R.raw] and feeds them into the
     * [PedestrianViewModel.simulateKhreshchatykScan] pipeline.
     */
    private fun loadMockFilesAndSimulate() {
        val context = requireContext()
        val mockFiles = listOf(
            R.raw.mock_frame_1,
            R.raw.mock_frame_2,
            R.raw.mock_frame_3,
            R.raw.mock_frame_4
        ).map { resId ->
            val inputStream = context.resources.openRawResource(resId)
            val tempFile = java.io.File.createTempFile("mock_frame_", ".jpg", context.cacheDir)
            inputStream.use {
                java.nio.file.Files.copy(it, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            tempFile
        }

        viewModel.simulateKhreshchatykScan(mockFiles)

        Toast.makeText(context, R.string.mock_scan_triggered, Toast.LENGTH_SHORT).show()
    }

    // ── Observe UI State ───────────────────────────────────────────

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    updateScanningOverlay(state)
                    updateResultCard(state)
                    handleScanCompletion(state)
                    showError(state)
                }
            }
        }
    }

    /**
     * Shows/hides the scanning overlay (semi‑transparent dark layer + spinner).
     */
    private fun updateScanningOverlay(state: UiState) {
        binding.overlayScanning.isVisible = state.isScanning

        // Re‑enable the locate button once scanning is done
        if (!state.isScanning) {
            binding.fabLocateMe.isEnabled = true
        }
    }

    /**
     * Updates the floating result card with the latest position and bearing.
     * The card becomes visible once [UiState.hasData] is true.
     */
    private fun updateResultCard(state: UiState) {
        if (!state.hasData) {
            binding.cardScanResult.visibility = View.GONE
            return
        }

        binding.cardScanResult.visibility = View.VISIBLE

        // Coordinates
        val coordsText = if (state.latitude != null && state.longitude != null) {
            getString(R.string.pedestrian_position_format, state.latitude, state.longitude)
        } else {
            getString(R.string.pedestrian_position_unknown)
        }
        binding.tvResultCoords.text = coordsText

        // Bearing
        val bearingText = state.bearing?.let {
            getString(R.string.pedestrian_bearing_format, it.toInt())
        } ?: getString(R.string.pedestrian_bearing_unknown)
        binding.tvResultBearing.text = bearingText
    }

    /**
     * After a scan completes successfully (not scanning, no error, has data),
     * show a brief Snackbar with the result summary.
     */
    private fun handleScanCompletion(state: UiState) {
        if (state.isScanning || state.errorMessage != null || !state.hasData) return

        val lat = state.latitude ?: return
        val lng = state.longitude ?: return

        val message = getString(
            R.string.pedestrian_scan_complete,
            String.format("%.6f", lat),
            String.format("%.6f", lng)
        )
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.fabLocateMe)
            .show()
    }

    /**
     * Shows error messages via Toast and clears them from the state.
     */
    private fun showError(state: UiState) {
        val error = state.errorMessage ?: return
        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
        viewModel.clearError()
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()

        // Signal the ViewModel that this tab is now active (defensive
        // guard against stale coroutines on the backstack).
        viewModel.onTabResumed()

        // Re-initialize camera every time the fragment becomes active.
        // This ensures the camera is bound when navigating back from
        // another tab (e.g. Transport), preventing the "Not bound to a
        // valid Camera" black preview issue.
        initCamera()

        // If the camera was already initialised (e.g. coming back from
        // a brief pause), explicitly rebuild the Preview use case with
        // a fresh surface provider to eliminate black preview.
        scannerCamera?.rebindPreview()
    }

    override fun onPause() {
        super.onPause()

        // Signal the ViewModel that this tab is now inactive.
        viewModel.onTabPaused()

        // Release camera resources when the fragment is no longer visible.
        scannerCamera?.shutdown()
        scannerCamera = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countdownJob?.cancel()
        countdownJob = null
        // Camera should already be shut down in onPause(), but this
        // is a safety net in case the fragment is destroyed without
        // going through onPause() (e.g., activity destruction).
        scannerCamera?.shutdown()
        scannerCamera = null
        fileManagerService = null
        _binding = null
    }
}
