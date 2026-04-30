package com.navisense.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.chip.Chip
import com.navisense.R
import com.navisense.databinding.FragmentMapBinding
import com.navisense.model.AppLocation
import com.navisense.model.AppLocationCategory
import com.navisense.ui.MainViewModel
import com.navisense.ui.details.LocationDetailsBottomSheet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()
    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var isMapReady = false

    /** Maps AppLocation.id → Google Maps Marker for efficient updates. */
    private val markerMap = mutableMapOf<Int, Marker>()

    /** Currently visible circle (radius filter). */
    private var radiusCircle: Circle? = null

    // ── Permission launcher (Location only — camera is separate) ──
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            enableMyLocation()
        } else {
            Toast.makeText(requireContext(), R.string.permission_location_denied, Toast.LENGTH_LONG).show()
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

        initMapFragment()
        initCategoryChips()
        initRadiusFilter()
        initFabMyLocation()
        observeViewModel()
    }

    // ── Map Initialisation ─────────────────────────────────────────

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

            // Handle marker click → open Details BottomSheet
            map.setOnMarkerClickListener { marker ->
                val locationId = marker.tag as? Int
                if (locationId != null) {
                    LocationDetailsBottomSheet.newInstance(locationId)
                        .show(parentFragmentManager, LocationDetailsBottomSheet.TAG)
                }
                true
            }
        }
    }

    // ── Category Chips ─────────────────────────────────────────────

    private fun initCategoryChips() {
        val chipGroup = binding.chipGroupCategories

        // "All" chip first
        val allChip = Chip(requireContext(), null, com.google.android.material.R.style.Widget_Material3_Chip_Filter)
        allChip.text = getString(R.string.filter_all)
        allChip.isCheckable = true
        allChip.isCheckedIconVisible = true
        allChip.isChecked = true
        allChip.tag = null // null means "All"
        allChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) viewModel.setCategoryFilter(null)
        }
        chipGroup.addView(allChip)

        // One chip per category
        AppLocationCategory.entries.forEach { category ->
            val chip = Chip(requireContext(), null, com.google.android.material.R.style.Widget_Material3_Chip_Filter)
            chip.text = category.key
            chip.isCheckable = true
            chip.isCheckedIconVisible = true
            chip.tag = category.key
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) viewModel.setCategoryFilter(category.key)
            }
            chipGroup.addView(chip)
        }

        chipGroup.isSingleSelection = true
    }

    // ── Radius Filter ──────────────────────────────────────────────

    private fun initRadiusFilter() {
        val radii = listOf(null, 1, 2, 5, 10) // null = off
        val labels = radii.map {
            when (it) {
                null -> getString(R.string.radius_off)
                1 -> "1 km"
                2 -> "2 km"
                5 -> "5 km"
                10 -> "10 km"
                else -> "${it} km"
            }
        }

        binding.btnRadiusFilter.setOnClickListener {
            // Simple cycle through radius options on each tap
            val current = viewModel.selectedRadiusKm.value
            val currentIndex = radii.indexOf(current)
            val nextIndex = (currentIndex + 1) % radii.size
            val nextRadius = radii[nextIndex]

            viewModel.setRadiusFilter(nextRadius)
            binding.btnRadiusFilter.text = labels[nextIndex]
            updateRadiusCircle()
        }
    }

    private fun updateRadiusCircle() {
        val radiusKm = viewModel.selectedRadiusKm.value ?: run {
            radiusCircle?.remove()
            radiusCircle = null
            return
        }

        // Get user location (fallback to Kyiv centre)
        val center = try {
            val visibleRegion = map.projection.visibleRegion
            visibleRegion.latLngBounds.center
        } catch (e: Exception) {
            LatLng(50.4501, 30.5234)
        }

        // Draw circle (convert km to meters)
        radiusCircle?.remove()
        radiusCircle = map.addCircle(
            CircleOptions()
                .center(center)
                .radius(radiusKm * 1000.0)
                .strokeColor(ContextCompat.getColor(requireContext(), R.color.naviSense_primary))
                .strokeWidth(3f)
                .fillColor(
                    ContextCompat.getColor(requireContext(), R.color.radius_fill)
                )
        )
    }

    // ── My Location FAB ────────────────────────────────────────────

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

    // ── Observe ViewModel ─────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe filtered locations → render markers
                launch {
                    viewModel.filteredLocations.collectLatest { locations ->
                        renderMarkers(locations)
                    }
                }

                // Observe mock match result → drop a special marker
                launch {
                    viewModel.mockMatchLocation.collectLatest { match ->
                        if (match != null && isMapReady) {
                            dropMockMatchMarker(match)
                            viewModel.clearMockMatchResult()
                        }
                    }
                }
            }
        }
    }

    // ── Marker Rendering ──────────────────────────────────────────

    private fun renderMarkers(locations: List<AppLocation>) {
        if (!isMapReady) return

        val visibleIds = locations.map { it.id }.toSet()

        // Remove markers no longer in list
        markerMap.keys.filter { it !in visibleIds }.forEach { id ->
            markerMap[id]?.remove()
            markerMap.remove(id)
        }

        // Add / update markers
        locations.forEach { location ->
            val existing = markerMap[location.id]
            if (existing != null) {
                existing.position = LatLng(location.latitude, location.longitude)
                existing.title = location.title
                existing.snippet = location.description
            } else {
                val markerOptions = MarkerOptions()
                    .position(LatLng(location.latitude, location.longitude))
                    .title(location.title)
                    .snippet(location.description)
                    .icon(getMarkerIcon(location))

                val marker = map.addMarker(markerOptions)
                if (marker != null) {
                    marker.tag = location.id
                    markerMap[location.id] = marker
                }
            }
        }
    }

    /**
     * Returns a coloured marker: GRAY if visited, category colour otherwise.
     */
    private fun getMarkerIcon(location: AppLocation): BitmapDescriptor {
        return if (location.isVisited) {
            BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)
        } else {
            val hue = when (location.category) {
                AppLocationCategory.MONUMENT.key -> BitmapDescriptorFactory.HUE_RED
                AppLocationCategory.GROCERY.key -> BitmapDescriptorFactory.HUE_GREEN
                AppLocationCategory.GAS_STATION.key -> BitmapDescriptorFactory.HUE_ORANGE
                AppLocationCategory.RESTAURANT.key -> BitmapDescriptorFactory.HUE_CYAN
                AppLocationCategory.PHARMACY.key -> BitmapDescriptorFactory.HUE_BLUE
                else -> BitmapDescriptorFactory.HUE_RED
            }
            BitmapDescriptorFactory.defaultMarker(hue)
        }
    }

    /**
     * Drops a mock "Match Found" marker (from Visual Search) and animates camera to it.
     */
    private fun dropMockMatchMarker(location: AppLocation) {
        val latLng = LatLng(location.latitude, location.longitude)
        map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(getString(R.string.match_found))
                .snippet(location.title)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
        )
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        Toast.makeText(requireContext(), R.string.match_found, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
