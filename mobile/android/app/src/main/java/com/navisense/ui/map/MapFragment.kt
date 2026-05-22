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
import androidx.fragment.app.activityViewModels
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
import com.navisense.model.LocationState
import com.navisense.model.NavMode
import com.navisense.ui.MainViewModel
import com.navisense.ui.details.LocationDetailsBottomSheet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.core.os.LocaleListCompat
import java.util.Locale

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var isMapReady = false

    /** Maps AppLocation.id → Google Maps Marker for efficient updates. */
    private val markerMap = mutableMapOf<Int, Marker>()

    /** Currently visible circle (radius filter). */
    private var radiusCircle: Circle? = null

    /** Reference to the currently displayed "Visual Pin" marker (ViT result). */
    private var visualPinMarker: Marker? = null

    /**
     * Polyline rendered for the burst-capture trajectory (5-point path from
     * [MainViewModel.routePolylinePoints]).  Removed and re-added whenever
     * the trajectory changes.
     */
    private var burstPolyline: Polyline? = null

    /** Yellow marker at the end of the burst trajectory with heading rotation. */
    private var burstDirectionMarker: Marker? = null

    /**
     * Live Dashcam tracking marker.  Updated in real‑time (every ~5 s) by
     * [MainViewModel.liveTrackingLocation].  The marker position is updated
     * in‑place rather than being removed/re‑added for smooth animation.
     */
    private var dashcamMarker: Marker? = null

    /** The last known Dashcam location, persisted across [map.clear] calls. */
    private var lastDashcamLocation: AppLocation? = null

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

        // Locations are now persisted in Room SQLite and always reflect the
        // user's saved data — no seed-data re-resolution needed after locale switch.

        initMapFragment()
        initSearchBar()
        initCategoryChips()
        initAdvancedFilters()
        initRadiusFilter()
        initFabMyLocation()
        initLanguageToggle()
        initModeToggle()
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

    // ── Mode Toggle (Scanner / Dashcam) ──────────────────────────

    /** Guard to prevent [switchNavMode] listener from reacting to programmatic changes. */
    private var isModeToggleInitialised = false

    private fun initModeToggle() {
        // Set initial state without triggering listener
        updateModeToggleText(viewModel.navMode.value)
        isModeToggleInitialised = true

        binding.switchNavMode.setOnCheckedChangeListener { _, isChecked ->
            if (!isModeToggleInitialised) return@setOnCheckedChangeListener
            // Only toggle if the actual mode doesn't match the switch state
            val currentMode = viewModel.navMode.value
            val shouldBeDashcam = isChecked
            if ((currentMode == NavMode.DASHCAM) != shouldBeDashcam) {
                viewModel.toggleNavMode()
            }
            // The navMode observer will sync the text/checked state
        }
    }

    private fun updateModeToggleText(mode: NavMode) {
        binding.switchNavMode.text = when (mode) {
            NavMode.SCANNER -> getString(R.string.mode_scanner)
            NavMode.DASHCAM -> getString(R.string.mode_dashcam)
        }
        binding.switchNavMode.isChecked = mode == NavMode.DASHCAM
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

                // Observe burst-capture trajectory polyline → draw on map
                launch {
                    viewModel.routePolylinePoints.collectLatest { points ->
                        if (isMapReady) {
                            drawBurstTrajectory(points)
                        }
                    }
                }

                // Observe navigation mode toggle → update switch text
                launch {
                    viewModel.navMode.collectLatest { mode ->
                        updateModeToggleText(mode)
                        // In Dashcam mode the visual pin should look FRESH
                        if (mode == NavMode.DASHCAM) {
                            updateVisualPinAppearance(LocationState.FRESH)
                        }
                    }
                }

                // Observe location state (freshness) → update visual pin appearance
                launch {
                    viewModel.locationState.collectLatest { state ->
                        updateVisualPinAppearance(state)
                    }
                }

                // Observe live Dashcam tracking location → smoothly move marker
                launch {
                    viewModel.liveTrackingLocation.collectLatest { location ->
                        if (location != null && isMapReady) {
                            lastDashcamLocation = location
                            dropDashcamMarker(location)
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

        // Re-add dashcam marker if it was cleared by map.clear()
        lastDashcamLocation?.let { location ->
            dashcamMarker?.remove()
            dashcamMarker = null
            dropDashcamMarker(location)
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
     * Drops a mock "Match Found" marker.
     *
     * If [MainViewModel.routePolylinePoints] contains data (burst‑capture
     * trajectory), the marker is placed at the **final** trajectory point
     * with its [MarkerOptions.rotation] set to the heading extracted from
     * [AppLocation.description], and the camera animates to the bounding
     * box of the full 5‑point path.
     *
     * Otherwise falls back to the standard single‑point marker with a
     * zoom‑in animation.
     */
    private fun dropMockMatchMarker(location: AppLocation) {
        val trajectory = viewModel.routePolylinePoints.value

        if (trajectory.isNotEmpty()) {
            // ── Burst‑capture mode: directional marker at last point ──
            val lastPoint = trajectory.last()
            val headingDeg = extractHeading(location.description)

            // Remove previous burst marker if any
            burstDirectionMarker?.remove()

            burstDirectionMarker = map.addMarker(
                MarkerOptions()
                    .position(lastPoint)
                    .title(getString(R.string.match_found))
                    .snippet(location.title)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
                    .rotation(headingDeg.toFloat())
                    .anchor(0.5f, 0.5f)   // centre the marker for accurate rotation
                    .flat(true)            // rotate with the map (compass-aware)
            )

            // Animate camera to the bounding box of the whole trajectory
            animateToBurstBounds(trajectory)
        } else {
            // ── Standard mock‑match mode (single point) ──────────────
            val latLng = LatLng(location.latitude, location.longitude)
            map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(getString(R.string.match_found))
                    .snippet(location.title)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
            )
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        }

        Toast.makeText(requireContext(), R.string.match_found, Toast.LENGTH_SHORT).show()
    }

    // ── Burst‑capture trajectory helpers ─────────────────────────────

    /**
     * Draws (or updates) a [Polyline] connecting the 5‑point burst‑capture
     * trajectory.  Styled with colour `#1565C0`, 6dp width and rounded
     * joint caps.
     *
     * If [points] is empty the polyline is removed from the map.
     */
    private fun drawBurstTrajectory(points: List<LatLng>) {
        // Remove previous polyline
        burstPolyline?.remove()
        burstPolyline = null

        if (points.isEmpty()) return

        burstPolyline = map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(6f)
                .color(0xFF1565C0.toInt())
                .jointType(JointType.ROUND)
                .startCap(RoundCap())
                .endCap(RoundCap())
        )
    }

    /**
     * Smoothly animates the camera to a bounding box that tightly fits all
     * points in [trajectory] with generous padding.
     */
    private fun animateToBurstBounds(trajectory: List<LatLng>) {
        if (trajectory.isEmpty()) return

        val builder = LatLngBounds.builder()
        trajectory.forEach { builder.include(it) }
        val bounds = builder.build()

        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, 120) // 120px padding
        )
    }

    /**
     * Extracts a heading value (degrees 0–360) from the [description]
     * stored by [com.navisense.ui.MainViewModel.executeVisualBurstLocalization].
     *
     * Expected format: `"... Heading XX.X°."`
     *
     * @return The heading in degrees, or `0.0` if parsing fails.
     */
    private fun extractHeading(description: String): Double {
        val regex = Regex("""Heading\s+([\d.]+)°""")
        return regex.find(description)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
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
     * The icon is styled according to the current [LocationState].
     * Separated so [renderMarkers] can re-add the pin after a `map.clear()`.
     */
    private fun dropVisualPinMarkerInternal(location: AppLocation): Marker {
        val latLng = LatLng(location.latitude, location.longitude)
        val currentState = viewModel.locationState.value
        val marker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(getString(R.string.visual_pin_title))
                .snippet(location.description)
                .icon(getLocationStateIcon(currentState))
                .alpha(getLocationStateAlpha(currentState))
        ) ?: error("Failed to add visual pin marker")
        // Store the AppLocation as the marker tag so renderMarkers()
        // can re-add the pin after map.clear().
        marker.tag = location
        return marker
    }

    /**
     * Updates the appearance of the existing visual pin marker to reflect
     * the given [LocationState] without re-creating it.
     */
    private fun updateVisualPinAppearance(state: LocationState) {
        val marker = visualPinMarker ?: return
        marker.setIcon(getLocationStateIcon(state))
        marker.alpha = getLocationStateAlpha(state)
    }

    /**
     * Returns the marker alpha (opacity) for the given [LocationState].
     * - [LocationState.FRESH]      → 1.0 (fully opaque)
     * - [LocationState.DEGRADING]  → 0.5 (semi-transparent)
     * - [LocationState.STALE]      → 0.3 (very transparent)
     */
    private fun getLocationStateAlpha(state: LocationState): Float {
        return when (state) {
            LocationState.FRESH -> 1.0f
            LocationState.DEGRADING -> 0.5f
            LocationState.STALE -> 0.3f
        }
    }

    /**
     * Returns a [BitmapDescriptor] for the visual pin marker based on the
     * given [LocationState].
     *
     * - [LocationState.FRESH]      → Solid GREEN (default primary-like hue)
     * - [LocationState.DEGRADING]  → Greyish-azure tint (semi-transparent feel)
     * - [LocationState.STALE]      → HUE_ROSE with a "?" from ic_search or falls
     *                                back to a rose marker indicating uncertainty.
     */
    private fun getLocationStateIcon(state: LocationState): BitmapDescriptor {
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
                // Fallback to default marker with state-based hue
                val hue = when (state) {
                    LocationState.FRESH -> BitmapDescriptorFactory.HUE_GREEN
                    LocationState.DEGRADING -> BitmapDescriptorFactory.HUE_CYAN
                    LocationState.STALE -> BitmapDescriptorFactory.HUE_ROSE
                }
                BitmapDescriptorFactory.defaultMarker(hue)
            }
        } catch (e: Exception) {
            val hue = when (state) {
                LocationState.FRESH -> BitmapDescriptorFactory.HUE_GREEN
                LocationState.DEGRADING -> BitmapDescriptorFactory.HUE_CYAN
                LocationState.STALE -> BitmapDescriptorFactory.HUE_ROSE
            }
            BitmapDescriptorFactory.defaultMarker(hue)
        }
    }

    /**
     * Returns a custom BitmapDescriptor for the Visual Pin marker (legacy).
     * Uses a camera/search icon drawable with a distinct hue background.
     * Falls back to HUE_AZURE if drawable conversion fails.
     */
    private fun getVisualPinIcon(): BitmapDescriptor {
        return getLocationStateIcon(viewModel.locationState.value)
    }

    // ── Dashcam Live Tracking Marker ──────────────────────────────

    /**
     * Drops or updates the Dashcam live‑tracking marker on the map.
     *
     * If a marker already exists, only its **position** is updated (Google Maps
     * will animate the marker smoothly when [Marker.position] is changed).
     * Otherwise a new marker is created with a distinct BLUE icon.
     *
     * @param location The latest [AppLocation] from the Dashcam sensor‑fusion pipeline.
     */
    private fun dropDashcamMarker(location: AppLocation) {
        val latLng = LatLng(location.latitude, location.longitude)

        if (dashcamMarker != null) {
            // Smoothly move existing marker — Google Maps animates the position
            dashcamMarker?.position = latLng
            dashcamMarker?.tag = location
        } else {
            dashcamMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(getString(R.string.dashcam_marker_title))
                    .snippet(location.description)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    .anchor(0.5f, 0.5f)
                    .flat(true)
            )
            dashcamMarker?.tag = location
        }
    }

    /**
     * Removes the Dashcam marker from the map and clears the stored location.
     * Called when switching back to Scanner mode.
     */
    private fun clearDashcamMarker() {
        dashcamMarker?.remove()
        dashcamMarker = null
        lastDashcamLocation = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
