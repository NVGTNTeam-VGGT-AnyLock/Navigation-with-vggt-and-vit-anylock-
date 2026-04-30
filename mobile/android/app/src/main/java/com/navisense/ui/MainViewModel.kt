package com.navisense.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navisense.data.LocationRepository
import com.navisense.data.MockLocationRepositoryImpl
import com.navisense.model.AppLocation
import com.navisense.model.AppLocationCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.*

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

    // ── Repository (swap here when Room is ready) ──────────────────
    private val repository: LocationRepository = MockLocationRepositoryImpl()

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
                result = result.filter { it.category == category }
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
    private val _routePolylinePoints = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val routePolylinePoints: StateFlow<List<Pair<Double, Double>>> = _routePolylinePoints.asStateFlow()

    // ── State: Visual Search Mock Result ──────────────────────────
    private val _mockMatchLocation = MutableStateFlow<AppLocation?>(null)
    val mockMatchLocation: StateFlow<AppLocation?> = _mockMatchLocation.asStateFlow()

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
        recalculateRoute()
    }

    fun clearRouteWaypoints() {
        _routeWaypoints.value = emptyList()
        _routePolylinePoints.value = emptyList()
    }

    /**
     * Reorder middle waypoints to find the shortest total path (TSP heuristic).
     * First waypoint MUST remain start, last MUST remain finish.
     * Middle waypoints are permuted to minimize total Haversine distance.
     */
    fun optimizeRoute() {
        val waypoints = _routeWaypoints.value
        if (waypoints.size <= 3) {
            recalculateRoute()
            return
        }

        val start = waypoints.first()
        val end = waypoints.last()
        val middle = waypoints.subList(1, waypoints.size - 1)

        // Use nearest-neighbor heuristic for TSP on middle points
        val optimizedMiddle = mutableListOf<AppLocation>()
        val remaining = middle.toMutableList()
        var current = start

        while (remaining.isNotEmpty()) {
            val nearest = remaining.minByOrNull {
                haversineDistance(
                    current.latitude, current.longitude,
                    it.latitude, it.longitude
                )
            } ?: break
            optimizedMiddle.add(nearest)
            remaining.remove(nearest)
            current = nearest
        }

        val optimized = listOf(start) + optimizedMiddle + listOf(end)
        _routeWaypoints.value = optimized
        recalculateRoute()
    }

    /** Recalculate the polyline path for the current waypoints. */
    private fun recalculateRoute() {
        val waypoints = _routeWaypoints.value
        if (waypoints.isEmpty()) {
            _routePolylinePoints.value = emptyList()
            return
        }

        // Generate mock "road-aware" polyline by interpolating with slight
        // offsets to simulate streets between waypoints.
        val points = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until waypoints.size - 1) {
            val from = waypoints[i]
            val to = waypoints[i + 1]
            // Generate intermediate points with slight jitter to simulate roads
            val segments = maxOf(
                ((haversineDistance(from.latitude, from.longitude, to.latitude, to.longitude) / 500.0).toInt()),
                3
            )
            for (s in 0 until segments) {
                val fraction = s.toDouble() / segments
                // Linear interpolation with slight perpendicular offset
                val lat = from.latitude + (to.latitude - from.latitude) * fraction
                val lng = from.longitude + (to.longitude - from.longitude) * fraction
                val jitterLat = sin(fraction * PI * 4) * 0.001
                val jitterLng = cos(fraction * PI * 4) * 0.001
                points.add(Pair(lat + jitterLat, lng + jitterLng))
            }
        }
        // Add the final point exactly
        val last = waypoints.last()
        points.add(Pair(last.latitude, last.longitude))

        _routePolylinePoints.value = points
    }

    /** Haversine distance between two coordinates in meters. */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
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
}
