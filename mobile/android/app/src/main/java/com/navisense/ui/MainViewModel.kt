package com.navisense.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.google.maps.DirectionsApi
import com.google.maps.GeoApiContext
import com.google.maps.android.PolyUtil
import com.navisense.BuildConfig
import com.navisense.data.LocationRepository
import com.navisense.data.MockLocationRepositoryImpl
import com.navisense.model.AppLocation
import com.navisense.model.AppLocationCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared ViewModel for the entire Location Management App.
 *
 * Uses [LocationRepository] for all data access. Currently backed by
 * [MockLocationRepositoryImpl]; Anya will swap this for a Room-based
 * implementation without changing any ViewModel code.
 *
 * Exposes:
 * - [allLocations] — unfiltered list from the repository
 * - [selectedCategory] — active category filter (null = All)
 * - [searchQuery] — fuzzy search across Title, Description, Category
 * - [showFavoritesOnly] — filter for favorites only
 * - [showVisitedOnly] — visited status filter (null = all, true = visited, false = not visited)
 * - [filteredLocations] — derived StateFlow combining all filters
 * - [selectedRadiusKm] — radius filter for map
 * - [analyticsData] — computed stats for the Analytics screen
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /**
         * Singleton [GeoApiContext] initialised once with the API key from
         * [BuildConfig]. Used for all Google Maps API requests (Directions, etc.).
         */
        private val geoApiContext: GeoApiContext by lazy {
            GeoApiContext.Builder()
                .apiKey(BuildConfig.MAPS_API_KEY)
                .build()
        }
    }

    // ── Repository (swap here when Room is ready) ──────────────────
    private val repository = MockLocationRepositoryImpl(application)

    // ── State: All Locations ───────────────────────────────────────
    val allLocations: StateFlow<List<AppLocation>> = repository.getAllLocations()

    // ── State: Category Filter ─────────────────────────────────────
    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    // ── State: Search Query ────────────────────────────────────────
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ── State: Favorites Filter ────────────────────────────────────
    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    // ── State: Visited Status Filter ───────────────────────────────
    // null = no filter, true = visited only, false = not visited only
    private val _visitedFilter = MutableStateFlow<Boolean?>(null)
    val visitedFilter: StateFlow<Boolean?> = _visitedFilter.asStateFlow()

    /** Filtered list derived from all filter criteria. */
    val filteredLocations: StateFlow<List<AppLocation>> =
        combine(
            allLocations,
            _selectedCategory,
            _searchQuery,
            _showFavoritesOnly,
            _visitedFilter
        ) { locations, category, query, favoritesOnly, visitedFilter ->
            var result = locations

            // Category filter
            if (category != null) {
                // КРИТИЧНИЙ ФІКС: Безпечне порівняння без врахування регістру
                result = result.filter { it.category.equals(category, ignoreCase = true) }
            }

            // Fuzzy search: query matches title, description, OR category
            if (query.isNotBlank()) {
                val q = query.lowercase().trim()
                result = result.filter { loc ->
                    loc.title.lowercase().contains(q) ||
                    loc.description.lowercase().contains(q) ||
                    loc.category.lowercase().contains(q)
                }
            }

            // Favorites filter
            if (favoritesOnly) {
                result = result.filter { it.isFavorite }
            }

            // Visited status filter
            if (visitedFilter != null) {
                result = result.filter { it.isVisited == visitedFilter }
            }

            result
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── State: Radius Filter (km) ──────────────────────────────────
    private val _selectedRadiusKm = MutableStateFlow<Int?>(null) // null = no filter
    val selectedRadiusKm: StateFlow<Int?> = _selectedRadiusKm.asStateFlow()

    // ── State: Route Builder selections ────────────────────────────
    private val _routeWaypoints = MutableStateFlow<List<AppLocation>>(emptyList())
    val routeWaypoints: StateFlow<List<AppLocation>> = _routeWaypoints.asStateFlow()

    // ── State: Optimized route polyline points ─────────────────────
    private val _routePolylinePoints = MutableStateFlow<List<LatLng>>(emptyList())
    val routePolylinePoints: StateFlow<List<LatLng>> = _routePolylinePoints.asStateFlow()

    // ── State: Optimisation in progress ────────────────────────────
    private val _isOptimizing = MutableStateFlow(false)
    val isOptimizing: StateFlow<Boolean> = _isOptimizing.asStateFlow()

    // ── State: Visual Search Mock Result ──────────────────────────
    private val _mockMatchLocation = MutableStateFlow<AppLocation?>(null)
    val mockMatchLocation: StateFlow<AppLocation?> = _mockMatchLocation.asStateFlow()

    // ── State: Visual Pin (from ViT backend) ──────────────────────
    private val _visualPinLocation = MutableStateFlow<AppLocation?>(null)
    val visualPinLocation: StateFlow<AppLocation?> = _visualPinLocation.asStateFlow()

    // ── Analytics (computed) ───────────────────────────────────────
    data class AnalyticsData(
        val categoryCounts: Map<String, Int>,
        val visitedCount: Int,
        val notVisitedCount: Int,
        val favoriteCount: Int,
        val notFavoriteCount: Int,
        val districtCounts: Map<String, Int>,
        val totalCount: Int
    )

    val analyticsData: StateFlow<AnalyticsData> =
        allLocations.combine(MutableStateFlow(Unit)) { locations, _ ->
            val categoryCounts = locations.groupBy { it.category }.mapValues { it.value.size }
            val visitedCount = locations.count { it.isVisited }
            val favoriteCount = locations.count { it.isFavorite }
            val districtCounts = locations.groupBy { detectDistrict(it.latitude, it.longitude) }
                .mapValues { it.value.size }
            AnalyticsData(
                categoryCounts = categoryCounts,
                visitedCount = visitedCount,
                notVisitedCount = locations.size - visitedCount,
                favoriteCount = favoriteCount,
                notFavoriteCount = locations.size - favoriteCount,
                districtCounts = districtCounts,
                totalCount = locations.size
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            AnalyticsData(emptyMap(), 0, 0, 0, 0, emptyMap(), 0))

    /**
     * Mock district detection based on geographic bounds of Kyiv.
     * Maps a coordinate to one of Kyiv's districts.
     */
    private fun detectDistrict(lat: Double, lng: Double): String {
        return when {
            // Shevchenkivskyi (central-west)
            lat in 50.440..50.470 && lng in 30.490..30.520 -> "Shevchenkivskyi"
            // Pecherskyi (central-east, government district)
            lat in 50.420..50.450 && lng in 30.530..30.560 -> "Pecherskyi"
            // Podilskyi (north, historic port area)
            lat in 50.460..50.520 && lng in 30.490..30.520 -> "Podilskyi"
            // Obolonskyi (north-west, residential)
            lat > 50.490 && lng < 30.530 -> "Obolonskyi"
            // Darnyrskyi (east, left bank)
            lng > 30.560 -> "Darnyrskyi"
            // Solomyanskyi (south-west, railway hub)
            lat < 50.430 && lng < 30.530 -> "Solomyanskyi"
            // Holosiivskyi (south, green area)
            lat < 50.420 -> "Holosiivskyi"
            // Desnyanskyi (north-east, left bank)
            else -> "Desnyanskyi"
        }
    }

    // ── Localization ──────────────────────────────────────────────

    /**
     * Re-resolve all seed location strings from the current locale.
     * Call this after the application locale changes (e.g. from
     * [com.navisense.ui.map.MapFragment]'s language toggle).
     */
    fun refreshLocalizedData() {
        repository.refreshLocalizedData()
    }

    // ── Public API ─────────────────────────────────────────────────

    /** Set the active category filter. Pass `null` to show all. */
    fun setCategoryFilter(category: String?) {
        _selectedCategory.value = category
    }

    /** Update the search query string. */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Toggle the favorites-only filter. */
    fun toggleFavoritesFilter() {
        _showFavoritesOnly.value = !_showFavoritesOnly.value
    }

    /** Set the visited status filter. null = no filter, true = visited, false = not visited. */
    fun setVisitedFilter(visited: Boolean?) {
        _visitedFilter.value = visited
    }

    /** Set the radius filter in km. Pass `null` to clear. */
    fun setRadiusFilter(radiusKm: Int?) {
        _selectedRadiusKm.value = radiusKm
    }

    /** Insert a new location (Map → Add flow). */
    fun addLocation(
        title: String,
        description: String,
        latitude: Double,
        longitude: Double,
        category: String,
        imageUri: String
    ) {
        viewModelScope.launch {
            repository.insertLocation(
                AppLocation(
                    title = title,
                    description = description,
                    latitude = latitude,
                    longitude = longitude,
                    category = category,
                    imageUri = imageUri
                )
            )
        }
    }

    /** Update an existing location. */
    fun updateLocation(location: AppLocation) {
        viewModelScope.launch { repository.updateLocation(location) }
    }

    /** Delete a location by ID. */
    fun deleteLocation(id: Int) {
        viewModelScope.launch { repository.deleteLocation(id) }
    }

    /** Toggle the visited flag. */
    fun toggleVisited(id: Int) {
        viewModelScope.launch { repository.toggleVisited(id) }
    }

    /** Toggle the favorite flag. */
    fun toggleFavorite(id: Int) {
        viewModelScope.launch { repository.toggleFavorite(id) }
    }

    // ── Route Builder ──────────────────────────────────────────────

    /** Toggle a location in/out of the route waypoint list. */
    fun toggleRouteWaypoint(location: AppLocation) {
        val current = _routeWaypoints.value.toMutableList()
        if (current.any { it.id == location.id }) {
            _routeWaypoints.value = current.filter { it.id != location.id }
        } else {
            _routeWaypoints.value = current + location
        }
        // Clear stale polyline — user must tap "Optimize Route" to re-request
        _routePolylinePoints.value = emptyList()
    }

    fun clearRouteWaypoints() {
        _routeWaypoints.value = emptyList()
        _routePolylinePoints.value = emptyList()
    }

    /**
     * Optimize the route order via the **Google Directions API** with
     * `optimizeWaypoints=true`.
     *
     * - First waypoint → `origin`
     * - Last waypoint  → `destination`
     * - Middle waypoints are passed to the API and re-ordered by Google's
     *   built-in TSP solver.
     *
     * On success the [routePolylinePoints] are updated with the decoded
     * road-aware polyline, and [routeWaypoints] reflects the optimised order.
     * On failure the error is logged and straight-line segments are drawn
     * as a fallback.
     */
    fun optimizeRoute() {
        val waypoints = _routeWaypoints.value
        if (waypoints.size < 2) {
            _routePolylinePoints.value = emptyList()
            return
        }

        _isOptimizing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val originStr = "${waypoints.first().latitude},${waypoints.first().longitude}"
                val destinationStr = "${waypoints.last().latitude},${waypoints.last().longitude}"

                // Build the Directions API request using the correct fluent API
                val request = DirectionsApi.newRequest(geoApiContext)
                    .origin(originStr)
                    .destination(destinationStr)
                    .optimizeWaypoints(true)

                // Add middle waypoints (if any) with TSP optimisation
                if (waypoints.size > 2) {
                    val middleStrs = waypoints.subList(1, waypoints.size - 1)
                        .map { wpt -> "${wpt.latitude},${wpt.longitude}" }
                        .toTypedArray()
                    request.waypoints(*middleStrs)
                }

                // Await the blocking API call (on Dispatchers.IO)
                val result = request.await()

                if (result.routes.isNotEmpty()) {
                    val route = result.routes.first()

                    // decodePath() returns List<com.google.maps.model.LatLng>
                    // Map each to com.google.android.gms.maps.model.LatLng for the UI
                    val apiDecodedPath = route.overviewPolyline.decodePath()
                    val mappedPath = apiDecodedPath.map { pt ->
                        LatLng(pt.lat, pt.lng)
                    }

                    withContext(Dispatchers.Main) {
                        _routePolylinePoints.value = mappedPath

                        // Reorder middle waypoints per the API's waypointOrder
                        if (waypoints.size > 2 && route.waypointOrder != null) {
                            val middle = waypoints.subList(1, waypoints.size - 1)
                            val reorderedMiddle = route.waypointOrder.map { idx -> middle[idx] }
                            val newOrder = mutableListOf<AppLocation>()
                            newOrder.add(waypoints.first())
                            newOrder.addAll(reorderedMiddle)
                            newOrder.add(waypoints.last())
                            _routeWaypoints.value = newOrder
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        fallbackToStraightLines(waypoints)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Directions API call failed", e)
                withContext(Dispatchers.Main) {
                    fallbackToStraightLines(waypoints)
                }
            } finally {
                _isOptimizing.value = false
            }
        }
    }

    /**
     * Fallback: draw straight-line segments between waypoints when the
     * Directions API request fails or returns no routes.
     */
    private fun fallbackToStraightLines(waypoints: List<AppLocation>) {
        _routePolylinePoints.value = waypoints.map { LatLng(it.latitude, it.longitude) }
    }

    // ── Visual Search Mock ─────────────────────────────────────────

    /**
     * Stores a mock match result. Called by [com.navisense.ui.search.VisualSearchFragment]
     * after the 2-second loading spinner completes.
     */
    fun setMockMatchResult(location: AppLocation) {
        _mockMatchLocation.value = location
    }

    fun clearMockMatchResult() {
        _mockMatchLocation.value = null
    }

    // ── Visual Pin (from ViT backend) ───────────────────────────────

    /**
     * Stores the visual-locate result from the ViT backend.
     * Called by [VisualSearchFragment] after a successful API call.
     * The [MapFragment] observes this to render a special "Visual Pin".
     */
    fun setVisualPinResult(location: AppLocation) {
        _visualPinLocation.value = location
    }

    fun clearVisualPinResult() {
        _visualPinLocation.value = null
    }
}
