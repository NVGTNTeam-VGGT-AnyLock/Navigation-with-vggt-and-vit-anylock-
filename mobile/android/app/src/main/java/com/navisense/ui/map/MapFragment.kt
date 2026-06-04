package com.navisense.ui.map

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
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.navisense.R
import com.navisense.core.FileManagerService
import com.navisense.core.LocalizationApiClient
import com.navisense.databinding.FragmentMapBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by activityViewModels()
    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var apiClient: LocalizationApiClient
    private lateinit var fileManager: FileManagerService

    private var isMapReady = false

    /** Reference to the current-location marker so we can update its rotation. */
    private var currentLocationMarker: Marker? = null

    // ── Permission launcher ──────────────────────────────────────────
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            enableMyLocation()
        } else {
            Toast.makeText(
                requireContext(),
                R.string.permission_location_denied,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        fileManager = FileManagerService(requireContext())
        apiClient = LocalizationApiClient.create(requireContext())

        initMapFragment()
        initModeToggle()
        initDestinationSearch()
        initFabMyLocation()
        initActionButtons()
        observeViewModel()
    }

    // ── Map Initialisation ──────────────────────────────────────────

    private fun initMapFragment() {
        val mapFragment = childFragmentManager
            .findFragmentById(R.id.map_container) as SupportMapFragment

        mapFragment.getMapAsync { googleMap ->
            map = googleMap
            isMapReady = true

            // Default camera: Kyiv city centre
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(50.4501, 30.5234), 13f))

            map.uiSettings.isZoomControlsEnabled = true
            map.uiSettings.isMapToolbarEnabled = false
        }
    }

    // ── Mode Toggle ─────────────────────────────────────────────────

    private fun initModeToggle() {
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btn_pedestrian -> NavMode.PEDESTRIAN
                R.id.btn_transport -> NavMode.TRANSPORT
                else -> return@addOnButtonCheckedListener
            }
            viewModel.setMode(mode)
        }
    }

    // ── Destination Search ──────────────────────────────────────────

    private fun initDestinationSearch() {
        binding.etDestination.setOnEditorActionListener { _, _, _ ->
            val query = binding.etDestination.text?.toString()?.trim() ?: ""
            if (query.isNotEmpty()) {
                viewModel.setDestinationQuery(query)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.searching_for, query),
                    Toast.LENGTH_SHORT
                ).show()
            }
            true
        }
    }

    // ── My Location FAB ─────────────────────────────────────────────

    private fun initFabMyLocation() {
        binding.fabMyLocation.setOnClickListener {
            requestLocationPermission()
        }
    }

    private fun requestLocationPermission() {
        val fineGranted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            enableMyLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun enableMyLocation() {
        if (!isMapReady) return
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        map.isMyLocationEnabled = true
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val userLatLng = LatLng(location.latitude, location.longitude)
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 16f))
            }
        }
    }

    // ── Action Buttons ──────────────────────────────────────────────

    private fun initActionButtons() {
        // Update Location — placeholder (wired in next phase)
        binding.btnUpdateLocation.setOnClickListener {
            Toast.makeText(
                requireContext(),
                R.string.update_location_placeholder,
                Toast.LENGTH_SHORT
            ).show()
        }

        // Test — run SLAM Fusion with mock frames from assets
        binding.btnTest.setOnClickListener {
            runFusionTest()
        }

        // Start Trip
        binding.btnStartTrip.setOnClickListener {
            viewModel.startTracking()
        }

        // End Trip
        binding.btnEndTrip.setOnClickListener {
            viewModel.stopTracking()
        }
    }

    // ── SLAM Fusion Test ────────────────────────────────────────────

    /**
     * Runs the SLAM Fusion test:
     * 1. Extracts 4 mock frames from assets/mock_frames/ into cache.
     * 2. Calls [LocalizationApiClient.navigateFusion].
     * 3. On success, appends the result to [MapViewModel.pathHistory].
     * 4. Cleans up temporary files.
     */
    private fun runFusionTest() {
        if (!isMapReady) {
            Toast.makeText(requireContext(), "Map not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), R.string.testing_fusion, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                // 1. Copy mock frames from assets to cache
                val mockFiles = withContext(Dispatchers.IO) {
                    (1..4).map { i ->
                        fileManager.copyAssetToCache("mock_frames/mock_frame_$i.jpg")
                    }
                }

                // 2. Call the fusion API
                val result = apiClient.navigateFusion(mockFiles)

                // 3. Append result to path history
                viewModel.addPathPoint(result)

                // 4. Cleanup temporary files
                withContext(Dispatchers.IO) {
                    mockFiles.forEach { file ->
                        fileManager.deleteImage(file)
                    }
                }

                Toast.makeText(
                    requireContext(),
                    getString(R.string.fusion_test_success),
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.fusion_test_failed, e.message ?: "Unknown error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Observe ViewModel ──────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe current mode → update button visibility
                launch {
                    viewModel.currentMode.collect { mode ->
                        updateUiForMode(mode)
                    }
                }

                // Observe tracking state → toggle Start/End buttons
                launch {
                    viewModel.isTracking.collect { tracking ->
                        updateUiForTracking(tracking)
                    }
                }

                // Observe path history → re-render map
                launch {
                    viewModel.pathHistory.collect { path ->
                        renderPath(path)
                    }
                }
            }
        }
    }

    // ── Map Rendering ───────────────────────────────────────────────

    /**
     * Renders the accumulated path history on the map:
     * 1. Clears all existing overlays.
     * 2. Draws a **Green Polyline** connecting all points.
     * 3. Drops a **Marker** at the LAST point (current location).
     * 4. Sets the marker's rotation to [FusionResponse.heading].
     * 5. Animates the camera to the last point at zoom ~18f.
     */
    private fun renderPath(path: List<com.navisense.core.FusionResponse>) {
        if (!isMapReady || path.isEmpty()) return

        // 1. Clear old overlays (but keep the map itself)
        map.clear()
        currentLocationMarker = null

        // 2. Build LatLng list for the polyline
        val latLngs = path.map { LatLng(it.lat, it.lon) }

        // 3. Draw Green Polyline
        val polylineOptions = PolylineOptions()
            .addAll(latLngs)
            .color(ContextCompat.getColor(requireContext(), R.color.path_history_green))
            .width(8f)
            .geodesic(true)
        map.addPolyline(polylineOptions)

        // 4. Drop a marker at the LAST point (current location)
        val lastPoint = path.last()
        val lastLatLng = LatLng(lastPoint.lat, lastPoint.lon)

        val markerOptions = MarkerOptions()
            .position(lastLatLng)
            .title(getString(R.string.current_location_title))
            .snippet(getString(R.string.heading_format, lastPoint.heading.toInt()))
            .rotation(lastPoint.heading.toFloat())
            .flat(true) // rotate with map
            .anchor(0.5f, 0.5f) // centre the marker on the point
            .icon(getArrowIcon())

        currentLocationMarker = map.addMarker(markerOptions)

        // 5. Animate camera to the last point
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(lastLatLng, 18f))
    }

    /**
     * Returns a small arrow [BitmapDescriptor] pointing up (North by default).
     * The marker's [MarkerOptions.rotation] will rotate it to match the heading.
     */
    private fun getArrowIcon(): BitmapDescriptor {
        return BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
    }

    // ── UI Helpers ──────────────────────────────────────────────────

    /**
     * Update UI elements based on the current [NavMode].
     *
     * - "Update Location" button is VISIBLE only in Pedestrian mode.
     * - "Update Location" button is GONE in Transport mode.
     */
    private fun updateUiForMode(mode: NavMode) {
        binding.btnUpdateLocation.visibility = when (mode) {
            NavMode.PEDESTRIAN -> View.VISIBLE
            NavMode.TRANSPORT -> View.GONE
        }
    }

    /**
     * Update UI elements based on the current tracking state.
     *
     * - When tracking is active: show "End Trip", hide "Start Trip".
     * - When tracking is inactive: show "Start Trip", hide "End Trip".
     */
    private fun updateUiForTracking(tracking: Boolean) {
        if (tracking) {
            binding.btnStartTrip.visibility = View.GONE
            binding.btnEndTrip.visibility = View.VISIBLE
        } else {
            binding.btnStartTrip.visibility = View.VISIBLE
            binding.btnEndTrip.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
