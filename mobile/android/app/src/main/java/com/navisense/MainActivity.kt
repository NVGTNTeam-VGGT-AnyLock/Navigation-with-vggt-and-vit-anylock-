package com.navisense

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.chip.Chip
import com.navisense.core.FileManagerService
import com.navisense.databinding.ActivityMainBinding
import com.navisense.model.MarkerItem
import com.navisense.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main map Activity for NaviSense MVP.
 *
 * Responsibilities:
 * - Hosts the Google Map and overlays the search bar + filter chips.
 * - Allows users to tap on the map to drop custom markers.
 * - Filters displayed markers by transport-mode chips (Walking, Bicycle, Car, All).
 * - Provides live search across marker titles and snippets.
 * - Requests CAMERA + Location runtime permissions on launch.
 * - Defers My-Location and CameraX init until permissions are granted.
 *
 * The ML backend integration ([ScannerCamera], [LocalizationApiClient], [FileManagerService])
 * will be connected in Sprint 2.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: GoogleMap
    private lateinit var viewModel: MainViewModel
    private lateinit var fileManagerService: FileManagerService
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    /**
     * Maps [MarkerItem.id] to Google Maps [Marker] for efficient updates.
     */
    private val markerIdToGoogleMarker = mutableMapOf<String, Marker>()

    /**
     * Maps Google Maps [Marker] to [MarkerItem.id] for click handling.
     */
    private val googleMarkerToMarkerId = mutableMapOf<Marker, String>()

    /**
     * Currently visible markers after filtering (used for search filtering).
     */
    private var currentFilteredItems: List<MarkerItem> = emptyList()

    /**
     * Whether the map has been fully initialised (permission-dependent features
     * should check this flag before proceeding).
     */
    private var isMapReady = false

    // ── Runtime permission launcher (Camera + Location) ────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions.getOrDefault(Manifest.permission.CAMERA, false)
        val fineLocationGranted = permissions.getOrDefault(
            Manifest.permission.ACCESS_FINE_LOCATION, false
        )
        val coarseLocationGranted = permissions.getOrDefault(
            Manifest.permission.ACCESS_COARSE_LOCATION, false
        )
        val anyLocationGranted = fineLocationGranted || coarseLocationGranted

        when {
            cameraGranted && anyLocationGranted -> {
                // ── All critical permissions granted ──
                enableMyLocation()

                // Sprint 2: initialise CameraX ScannerCamera here
                // initCameraX()
            }

            cameraGranted -> {
                // Only Camera granted, location denied
                Toast.makeText(
                    this, R.string.permission_location_denied, Toast.LENGTH_LONG
                ).show()

                // Sprint 2: initialise CameraX ScannerCamera here
                // initCameraX()
            }

            anyLocationGranted -> {
                // Only Location granted, Camera denied
                enableMyLocation()
                Toast.makeText(
                    this, R.string.permission_camera_denied, Toast.LENGTH_LONG
                ).show()
            }

            else -> {
                // All permissions denied
                Toast.makeText(
                    this, R.string.permission_location_denied, Toast.LENGTH_LONG
                ).show()
                Toast.makeText(
                    this, R.string.permission_camera_denied, Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        fileManagerService = FileManagerService(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialise UI components that do NOT need runtime permissions.
        initMapFragment()
        initSearchBar()
        initFilterChips()
        observeViewModel()

        // Request all runtime permissions immediately.
        // The map is already loading asynchronously via getMapAsync;
        // permission-gated features (My Location, CameraX) are set up
        // inside the permission callback above.
        requestPermissions()
    }

    // ── Map Initialisation (permission-independent) ───────────────

    private fun initMapFragment() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map_container) as SupportMapFragment

        mapFragment.getMapAsync { googleMap ->
            map = googleMap
            isMapReady = true

            // Default camera: Kyiv city centre (demo area)
            val defaultLocation = LatLng(50.4501, 30.5234)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 15f))

            // Map UI settings (My-Location button is enabled only after
            // permission is granted — see enableMyLocation()).
            map.uiSettings.isZoomControlsEnabled = true
            map.uiSettings.isMapToolbarEnabled = false

            // Handle map taps → add marker
            map.setOnMapClickListener { latLng ->
                viewModel.addMarker(latLng.latitude, latLng.longitude)
                Toast.makeText(
                    this,
                    getString(R.string.marker_added, latLng.latitude, latLng.longitude),
                    Toast.LENGTH_SHORT
                ).show()
            }

            // Handle marker clicks → show info window
            map.setOnMarkerClickListener { marker ->
                marker.showInfoWindow()
                true
            }

            // Handle info window click → remove marker
            map.setOnInfoWindowClickListener { marker ->
                val markerId = googleMarkerToMarkerId[marker]
                if (markerId != null) {
                    viewModel.removeMarker(markerId)
                    Toast.makeText(this, R.string.marker_removed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Location Permission ───────────────────────────────────────

    /**
     * Requests CAMERA, ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION
     * if they have not already been granted.
     */
    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        // Only request location if NEITHER fine nor coarse is granted.
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation && !hasCoarseLocation) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // All permissions already granted on a previous launch.
            enableMyLocation()
            // Sprint 2: initialise CameraX ScannerCamera here
            // initCameraX()
        }
    }

    /**
     * Enables the My Location layer on the map and animates the camera
     * to the user's last known position.
     *
     * Safe to call only after [isMapReady] is true AND the user has granted
     * at least one location permission.
     */
    private fun enableMyLocation() {
        if (!isMapReady) return

        val hasLocationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission) return

        // Show the blue dot and My Location button
        map.isMyLocationEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true

        // Animate camera to the user's last known position.
        // If no cached location is available, the map stays at the
        // default Kyiv-centre position set in initMapFragment().
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val userLatLng = LatLng(location.latitude, location.longitude)
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(userLatLng, 16f)
                )
            }
        }
    }

    // ── Search Bar ────────────────────────────────────────────────

    private fun initSearchBar() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // No-op
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // No-op
            }

            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                applySearchFilter(query)
            }
        })
    }

    /**
     * Filters the currently displayed markers by the search query,
     * matching against [MarkerItem.title] and [MarkerItem.snippet].
     */
    private fun applySearchFilter(query: String) {
        if (query.isEmpty()) {
            renderMarkers(currentFilteredItems)
            return
        }

        val filtered = currentFilteredItems.filter { marker ->
            marker.title.lowercase().contains(query) ||
                    marker.snippet.lowercase().contains(query) ||
                    marker.tag.lowercase().contains(query)
        }
        renderMarkers(filtered)
    }

    // ── Filter Chips ──────────────────────────────────────────────

    private fun initFilterChips() {
        val chipGroup = binding.chipGroupFilters

        // Chip definitions: (labelKey, stringResourceId)
        val chipDefs = listOf(
            MarkerItem.TAG_ALL to R.string.filter_all,
            "Walking" to R.string.filter_walking,
            "Bicycle" to R.string.filter_bicycle,
            "Car" to R.string.filter_car
        )

        chipDefs.forEach { (labelKey, stringRes) ->
            val chip = Chip(this, null, com.google.android.material.R.style.Widget_Material3_Chip_Filter)
            chip.text = getString(stringRes)
            chip.isCheckable = true
            chip.isCheckedIconVisible = true

            // Store the logical tag as a tag on the view itself
            chip.tag = labelKey

            // Default: "All" is selected
            if (labelKey == MarkerItem.TAG_ALL) {
                chip.isChecked = true
            }

            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    val tag = chip.tag as? String
                    viewModel.setFilter(tag)
                }
            }

            chipGroup.addView(chip)
        }

        // Ensure single selection
        chipGroup.isSingleSelection = true
    }

    // ── Observe ViewModel ─────────────────────────────────────────

    private fun observeViewModel() {
        // Observe filtered markers → update map
        lifecycleScope.launch {
            viewModel.filteredMarkers.collectLatest { markers ->
                currentFilteredItems = markers
                renderMarkers(markers)
            }
        }

        // Observe selected tag → update chip visual state
        lifecycleScope.launch {
            viewModel.selectedTag.collectLatest { tag ->
                updateChipSelection(tag)
            }
        }
    }

    /**
     * Updates chip selection state when the filter changes programmatically.
     */
    private fun updateChipSelection(selectedTag: String?) {
        val chipGroup = binding.chipGroupFilters
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            val chipTag = chip.tag as? String

            val isSelected = when {
                selectedTag == null && chipTag == MarkerItem.TAG_ALL -> true
                selectedTag != null && chipTag == selectedTag -> true
                else -> false
            }

            if (chip.isChecked != isSelected) {
                chip.isChecked = isSelected
            }
        }
    }

    // ── Marker Rendering ──────────────────────────────────────────

    /**
     * Renders the given list of markers on the map, performing a minimal diff
     * against existing markers to avoid unnecessary flickering or recreation.
     *
     * If the map is not yet ready, this is a no-op.
     */
    private fun renderMarkers(markers: List<MarkerItem>) {
        if (!isMapReady) return

        // Determine which markers to remove (no longer in the filtered list)
        val visibleIds = markers.map { it.id }.toSet()
        val toRemove = markerIdToGoogleMarker.keys.filter { it !in visibleIds }

        toRemove.forEach { id ->
            markerIdToGoogleMarker[id]?.remove()
            markerIdToGoogleMarker.remove(id)
        }

        // Clean up reverse map for removed markers
        googleMarkerToMarkerId.entries.removeAll { (_, markerId) ->
            markerId !in visibleIds
        }

        // Add or update markers
        markers.forEach { item ->
            val existingMarker = markerIdToGoogleMarker[item.id]
            if (existingMarker != null) {
                // Update position / title / snippet in place
                val position = LatLng(item.latitude, item.longitude)
                existingMarker.apply {
                    this.position = position
                    this.title = item.title
                    this.snippet = item.snippet
                }
            } else {
                // Create a new marker with a tag-specific colour
                val markerOptions = MarkerOptions()
                    .position(LatLng(item.latitude, item.longitude))
                    .title(item.title)
                    .snippet(item.snippet)
                    .icon(getMarkerIconForTag(item.tag))

                val googleMarker = map.addMarker(markerOptions)
                if (googleMarker != null) {
                    markerIdToGoogleMarker[item.id] = googleMarker
                    googleMarkerToMarkerId[googleMarker] = item.id
                }
            }
        }
    }

    /**
     * Returns a coloured default marker based on the transport tag.
     *
     * - "Walking" → Green
     * - "Bicycle" → Blue (Azure)
     * - "Car" → Orange
     * - Default → Red
     */
    private fun getMarkerIconForTag(tag: String): com.google.android.gms.maps.model.BitmapDescriptor {
        val hue = when (tag) {
            "Walking" -> BitmapDescriptorFactory.HUE_GREEN
            "Bicycle" -> BitmapDescriptorFactory.HUE_AZURE
            "Car" -> BitmapDescriptorFactory.HUE_ORANGE
            else -> BitmapDescriptorFactory.HUE_RED
        }
        return BitmapDescriptorFactory.defaultMarker(hue)
    }
}
