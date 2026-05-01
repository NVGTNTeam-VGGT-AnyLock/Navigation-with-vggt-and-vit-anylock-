package com.navisense.ui.map

import android.Manifest
import android.content.pm.PackageManager
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

        // After locale switch (Activity recreation), re-resolve mock data strings
        // so seed location titles/descriptions reflect the new language.
        viewModel.refreshLocalizedData()

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
        chipGroup.removeAllViews()
        chipGroup.isSingleSelection = true
        chipGroup.isSelectionRequired = true

        // "All" chip
        val allChip = Chip(requireContext()).apply {
            id = View.generateViewId()
            text = getString(R.string.filter_all)
            tag = null // null means "All"
            isClickable = true
            isCheckable = true
            isFocusable = true
        }
        chipGroup.addView(allChip)

        // Category chips
        AppLocationCategory.entries.filter { it != AppLocationCategory.NO_CATEGORY }.forEach { category ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()

                // Bilingual: if a translated string resource exists (e.g. cat_monument), use it.
                // Otherwise fall back to the English key.
                val resName = "cat_${category.name.lowercase(Locale.ROOT)}"
                val resId = resources.getIdentifier(resName, "string", requireContext().packageName)
                text = if (resId != 0) getString(resId) else category.key

                tag = category.key
                isClickable = true
                isCheckable = true
                isFocusable = true
            }
            chipGroup.addView(chip)
        }

        // Listen for chip selection changes on the group level
        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val selectedChip = group.findViewById<Chip>(checkedIds.first())
                val categoryKey = selectedChip?.tag as? String
                viewModel.setCategoryFilter(categoryKey)
            } else {
                viewModel.setCategoryFilter(null)
            }
        }

        // Select "All" by default
        chipGroup.check(allChip.id)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
