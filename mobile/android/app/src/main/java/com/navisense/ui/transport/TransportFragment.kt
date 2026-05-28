package com.navisense.ui.transport

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.navisense.R
import com.navisense.core.FileManagerService
import com.navisense.core.ScannerCamera
import com.navisense.databinding.FragmentTransportBinding
import com.navisense.model.UiState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * NaviSense unified debug tag for all Logcat output.
 */
private const val TAG = "NaviSense_Debug"

/**
 * Transport (vehicle) scanning fragment.
 *
 * ## Layout
 * - Full‑screen Google Map as the primary view.
 * - A small [PreviewView] PiP overlay (bottom‑right) showing the live camera feed.
 * - A status bar at the bottom showing current bearing and position.
 * - A stale‑warning card that appears when [UiState.isStale] becomes true.
 * - A "Mock: Khreshchatyk" button (top‑right) for development/testing.
 *
 * ## Behaviour
 * On start, the fragment creates a [ScannerCamera], binds it to a PiP preview,
 * injects it into [TransportViewModel], and starts the auto‑scan loop.
 * [UiState] is observed to dynamically update the Google Map marker (position + rotation)
 * and handle alpha decay when the location becomes stale.
 */
class TransportFragment : Fragment() {

    private var _binding: FragmentTransportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransportViewModel by viewModels()

    private lateinit var map: GoogleMap
    private var isMapReady = false

    /** The single marker on the map that represents the current estimated position. */
    private var positionMarker: Marker? = null

    /** Camera controller – created in [onViewCreated], released in [onDestroyView]. */
    private var scannerCamera: ScannerCamera? = null

    /** File manager for temporary image storage. */
    private var fileManagerService: FileManagerService? = null

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
        _binding = FragmentTransportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── 1. Init Map ─────────────────────────────────────────────
        initMap()

        // ── 2. Init Camera (PiP preview) ───────────────────────────
        initCamera()

        // ── 3. Wire Mock Button ────────────────────────────────────
        initMockButton()

        // ── 4. Observe ViewModel state ─────────────────────────────
        observeUiState()
    }

    // ── Google Map Initialisation ──────────────────────────────────

    private fun initMap() {
        val mapFragment = childFragmentManager
            .findFragmentById(R.id.map_container) as SupportMapFragment

        mapFragment.getMapAsync { googleMap ->
            map = googleMap
            isMapReady = true

            // Default camera: Kyiv city centre
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(50.4501, 30.5234), 14f
            ))

            map.uiSettings.isZoomControlsEnabled = true
            map.uiSettings.isMapToolbarEnabled = false
            map.uiSettings.isMyLocationButtonEnabled = false
        }
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
            previewView = binding.pipPreviewView // PiP preview
        )
        scannerCamera = camera

        // Inject camera into ViewModel
        viewModel.setCamera(camera)

        // Start auto‑scan now that the camera is ready
        viewModel.startAutoScan()
    }

    // ── Mock Button ────────────────────────────────────────────────

    private fun initMockButton() {
        binding.btnMockKhreshchatyk.setOnClickListener {
            loadMockFilesAndSimulate()
        }
    }

    /**
     * Loads the 4 mock JPEG frames from [R.raw] and feeds them into the
     * [TransportViewModel.simulateKhreshchatykScan] pipeline.
     */
    private fun loadMockFilesAndSimulate() {
        val context = requireContext()
        val mockFiles = listOf(
            R.raw.mock_frame_1,
            R.raw.mock_frame_2,
            R.raw.mock_frame_3,
            R.raw.mock_frame_4
        ).map { resId ->
            // Copy raw resource to a temp file for the API pipeline
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
                    updateMapMarker(state)
                    updateStatusBar(state)
                    updateStaleIndicator(state)
                    updateScanningIndicator(state)
                    showError(state)
                }
            }
        }
    }

    /**
     * Updates the Google Map position marker based on the latest [UiState].
     *
     * - Creates or moves the marker to [UiState.latitude]/[UiState.longitude].
     * - Rotates the marker to match [UiState.bearing] (bearing = rotation on map).
     * - Adjusts marker alpha based on [UiState.isStale].
     */
    private fun updateMapMarker(state: UiState) {
        if (!isMapReady) return

        val lat = state.latitude ?: return
        val lng = state.longitude ?: return
        val latLng = LatLng(lat, lng)

        if (positionMarker == null) {
            // Create marker
            positionMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(getString(R.string.transport_current_position))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    .anchor(0.5f, 0.5f) // Centre on position
                    .flat(true) // Rotate with map
            )
            // Animate camera to the first fix
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        } else {
            positionMarker?.position = latLng

            // Smoothly nudge camera if the marker moves significantly
            val currentCamera = map.cameraPosition.target
            val distance = haversineMeters(
                currentCamera.latitude, currentCamera.longitude, lat, lng
            )
            if (distance > 100) { // Move camera if >100m from centre
                map.animateCamera(CameraUpdateFactory.newLatLng(latLng))
            }
        }

        // ── Apply bearing (rotation) ──────────────────────────────
        val bearing = state.bearing ?: 0.0
        positionMarker?.rotation = bearing.toFloat()

        // ── Apply alpha based on staleness ────────────────────────
        positionMarker?.alpha = if (state.isStale) 0.35f else 1.0f
    }

    /**
     * Updates the bottom status bar with bearing and coordinate text.
     */
    private fun updateStatusBar(state: UiState) {
        val bearingText = state.bearing?.let {
            getString(R.string.transport_bearing_format, it.toInt())
        } ?: getString(R.string.transport_bearing_unknown)

        val positionText = if (state.latitude != null && state.longitude != null) {
            getString(R.string.transport_position_format, state.latitude, state.longitude)
        } else {
            getString(R.string.transport_position_unknown)
        }

        binding.tvBearing.text = bearingText
        binding.tvPosition.text = positionText
    }

    /**
     * Shows/hides the stale warning card and updates its alpha.
     */
    private fun updateStaleIndicator(state: UiState) {
        binding.cardStaleIndicator.visibility =
            if (state.isStale) View.VISIBLE else View.GONE
    }

    /**
     * Shows/hides the scanning progress spinner.
     */
    private fun updateScanningIndicator(state: UiState) {
        binding.progressScanning.visibility =
            if (state.isScanning) View.VISIBLE else View.GONE
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
        // Ensure camera is initialized and auto‑scan is running
        // when the fragment becomes active. If the camera was released
        // in onPause(), re‑initialize it here.
        if (scannerCamera == null) {
            initCamera() // also starts auto‑scan internally
        } else {
            viewModel.startAutoScan()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopAutoScan()
        // Release camera resources when fragment is no longer visible.
        // The camera will be re‑initialized in onResume().
        scannerCamera?.shutdown()
        scannerCamera = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopAutoScan()
        // Safety net: camera should already be shut down in onPause()
        scannerCamera?.shutdown()
        scannerCamera = null
        fileManagerService = null
        positionMarker = null
        _binding = null
    }

    // ── Utility ────────────────────────────────────────────────────

    /**
     * Approximate Haversine distance between two WGS‑84 coordinates.
     * @return Distance in meters.
     */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).pow(2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2).pow(2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    private fun Double.pow(exp: Int): Double {
        var result = 1.0
        repeat(exp) { result *= this }
        return result
    }
}
