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
 * - [filteredLocations] — derived StateFlow combining the two above
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

    /** Filtered list derived from category + all locations. */
    val filteredLocations: StateFlow<List<AppLocation>> =
        combine(allLocations, _selectedCategory) { locations, category ->
            if (category == null || category == AppLocationCategory.MONUMENT.key) {
                locations
            } else {
                locations.filter { it.category == category }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── State: Radius Filter (km) ──────────────────────────────────
    private val _selectedRadiusKm = MutableStateFlow<Int?>(null) // null = no filter
    val selectedRadiusKm: StateFlow<Int?> = _selectedRadiusKm.asStateFlow()

    // ── State: Route Builder selections ────────────────────────────
    private val _routeWaypoints = MutableStateFlow<List<AppLocation>>(emptyList())
    val routeWaypoints: StateFlow<List<AppLocation>> = _routeWaypoints.asStateFlow()

    // ── State: Visual Search Mock Result ──────────────────────────
    private val _mockMatchLocation = MutableStateFlow<AppLocation?>(null)
    val mockMatchLocation: StateFlow<AppLocation?> = _mockMatchLocation.asStateFlow()

    // ── Analytics (computed) ───────────────────────────────────────
    data class AnalyticsData(
        val categoryCounts: Map<String, Int>,
        val visitedCount: Int,
        val notVisitedCount: Int,
        val totalCount: Int
    )

    val analyticsData: StateFlow<AnalyticsData> =
        allLocations.combine(MutableStateFlow(Unit)) { locations, _ ->
            val categoryCounts = locations.groupBy { it.category }.mapValues { it.value.size }
            val visitedCount = locations.count { it.isVisited }
            AnalyticsData(
                categoryCounts = categoryCounts,
                visitedCount = visitedCount,
                notVisitedCount = locations.size - visitedCount,
                totalCount = locations.size
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            AnalyticsData(emptyMap(), 0, 0, 0))

    // ── Public API ─────────────────────────────────────────────────

    /** Set the active category filter. Pass `null` to show all. */
    fun setCategoryFilter(category: String?) {
        _selectedCategory.value = category
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

    // ── Route Builder ──────────────────────────────────────────────

    /** Toggle a location in/out of the route waypoint list. */
    fun toggleRouteWaypoint(location: AppLocation) {
        val current = _routeWaypoints.value.toMutableList()
        if (current.any { it.id == location.id }) {
            _routeWaypoints.value = current.filter { it.id != location.id }
        } else {
            _routeWaypoints.value = current + location
        }
    }

    fun clearRouteWaypoints() {
        _routeWaypoints.value = emptyList()
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
