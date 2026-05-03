package com.navisense.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.core.os.LocaleListCompat
import java.util.Locale

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

    /** Reference to the currently displayed "Visual Pin" marker (ViT result). */
    private var visualPinMarker: Marker? = null

    // Track filter button states
    private var visitedFilterActive = false
    private var favoritesFilterActive = false
    private var visitedFilterMode: Boolean? = null // null=all, true=visited, false=not

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
        initSearchBar()
        initCategoryChips()
        initAdvancedFilters()
        initRadiusFilter()
        initFabMyLocation()
        initLanguageToggle()
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

    // ── Search Bar ─────────────────────────────────────────────────

    private fun initSearchBar() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
        })
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

        // One chip per category (excluding NO_CATEGORY from chip filter)
        AppLocationCategory.entries.filter { it != AppLocationCategory.NO_CATEGORY }.forEach { category ->
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

    // ── Advanced Filters: Visited & Favorites ──────────────────────

    private fun initAdvancedFilters() {
        binding.btnFilterVisited.setOnClickListener {
            when (visitedFilterMode) {
                null -> {
                    // Show visited only
                    visitedFilterMode = true
                    visitedFilterActive = true
                    viewModel.setVisitedFilter(true)
                    binding.btnFilterVisited.setText(R.string.filter_visited_only)
                    binding.btnFilterVisited.alpha = 1.0f
                }
                true -> {
                    // Show not visited only
                    visitedFilterMode = false
                    viewModel.setVisitedFilter(false)
                    binding.btnFilterVisited.setText(R.string.filter_not_visited)
                }
                false -> {
                    // Clear filter
                    visitedFilterMode = null
                    visitedFilterActive = false
                    viewModel.setVisitedFilter(null)
                    binding.btnFilterVisited.setText(R.string.filter_visited)
                    binding.btnFilterVisited.alpha = 0.6f
                }
            }
        }
        binding.btnFilterVisited.alpha = 0.6f

        binding.btnFilterFavorites.setOnClickListener {
            favoritesFilterActive = !favoritesFilterActive
            viewModel.toggleFavoritesFilter()
            if (favoritesFilterActive) {
                binding.btnFilterFavorites.setText(R.string.filter_favorites_only)
                binding.btnFilterFavorites.alpha = 1.0f
            } else {
                binding.btnFilterFavorites.setText(R.string.filter_favorites)
                binding.btnFilterFavorites.alpha = 0.6f
            }
        }
        binding.btnFilterFavorites.alpha = 0.6f
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

    // ── Language Toggle ────────────────────────────────────────────

    private fun initLanguageToggle() {
        updateLanguageButtonText()
        binding.btnLanguageToggle.setOnClickListener {
            val currentLocale = resources.configuration.locales[0]
            val isEnglish = currentLocale.language == "en"
            val langTag = if (isEnglish) "uk" else "en"
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(langTag)
            )
        }
    }

    private fun updateLanguageButtonText() {
        val currentLocale = resources.configuration.locales[0]
        binding.btnLanguageToggle.text = if (currentLocale.language == "en") "EN" else "UK"
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

                // Observe visual pin (from ViT backend) → drop a distinct marker
                launch {
                    viewModel.visualPinLocation.collectLatest { pin ->
                        if (pin != null && isMapReady) {
                            dropVisualPinMarker(pin)
                            viewModel.clearVisualPinResult()
                        }
                    }
                }
            }
        }
    }

    // ── Marker Rendering ──────────────────────────────────────────

    private fun renderMarkers(locations: List<AppLocation>) {
        if (!isMapReady) return

        // Clear all existing markers and re-add fresh.
        // This ensures isVisited/isFavorite state changes are reflected
        // in marker icons (visited→gray, favorite→distinct hue).
        map.clear()
        markerMap.clear()

        // Rebuild radius circle if active (cleared by map.clear())
        updateRadiusCircle()

        // Re-add visual pin marker if it was cleared
        if (visualPinMarker != null) {
            val pinTag = visualPinMarker?.tag
            if (pinTag is AppLocation) {
                visualPinMarker = dropVisualPinMarkerInternal(pinTag)
            }
        }

        locations.forEach { location ->
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

    /**
     * Returns a coloured marker: GRAY if visited, category colour otherwise.
     * Favorite locations get a slight alpha or distinct treatment.
     */
    private fun getMarkerIcon(location: AppLocation): BitmapDescriptor {
        return if (location.isVisited) {
            BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)
        } else {
            val hue = AppLocationCategory.markerHue(location.category)
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

    // ── Visual Pin Marker (from ViT backend) ───────────────────────

    /**
     * Drops a special "Visual Pin" marker at the location returned by the
     * ViT backend. Uses a distinct CYAN colour and an info window showing
     * confidence. The camera animates to this marker and zooms in.
     */
    private fun dropVisualPinMarker(location: AppLocation) {
        // Remove any previous visual pin marker
        visualPinMarker?.remove()

        visualPinMarker = dropVisualPinMarkerInternal(location)

        // Animate camera to the new pin
        val latLng = LatLng(location.latitude, location.longitude)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))

        // Show a toast indicating the visual locate result
        Toast.makeText(
            requireContext(),
            getString(R.string.visual_pin_placed),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Internal helper that actually creates the marker on the map.
     * Separated so [renderMarkers] can re-add the pin after a `map.clear()`.
     */
    private fun dropVisualPinMarkerInternal(location: AppLocation): Marker {
        val latLng = LatLng(location.latitude, location.longitude)
        val marker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(getString(R.string.visual_pin_title))
                .snippet(location.description)
                .icon(getVisualPinIcon())
        ) ?: error("Failed to add visual pin marker")
        // Store the AppLocation as the marker tag so renderMarkers()
        // can re-add the pin after map.clear().
        marker.tag = location
        return marker
    }

    /**
     * Returns a custom BitmapDescriptor for the Visual Pin marker.
     * Uses a camera/search icon drawable with a distinct hue background.
     * Falls back to HUE_AZURE if drawable conversion fails.
     */
    private fun getVisualPinIcon(): BitmapDescriptor {
        return try {
            val drawable: Drawable? = ContextCompat.getDrawable(
                requireContext(), R.drawable.ic_search_photo
            )
            if (drawable != null) {
                val width = 48
                val height = 48
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                BitmapDescriptorFactory.fromBitmap(bitmap)
            } else {
                // Fallback: use default marker with a distinct hue
                BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
            }
        } catch (e: Exception) {
            // Fallback on any error
            BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
