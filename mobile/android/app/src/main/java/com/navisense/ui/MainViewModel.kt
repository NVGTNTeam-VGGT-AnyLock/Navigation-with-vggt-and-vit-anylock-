package com.navisense.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navisense.model.MarkerItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the main map screen.
 *
 * Manages:
 * - The list of all placed markers ([_allMarkers]).
 * - The currently active filter tag ([_selectedTag]).
 * - A derived, automatically-updating list of filtered markers ([filteredMarkers]).
 * - Adding new markers via user map-tap.
 */
class MainViewModel : ViewModel() {

    /** All markers placed by the user (mutable internal state). */
    private val _allMarkers = MutableStateFlow<List<MarkerItem>>(emptyList())

    /** Currently selected filter tag. `null` means "All" (show everything). */
    private val _selectedTag = MutableStateFlow<String?>(null)

    /** Expose the selected tag as an immutable [StateFlow]. */
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    /**
     * The currently displayed markers, derived from both the full list and the
     * active filter tag. Automatically updates whenever either source changes.
     *
     * - When [selectedTag] is `null` or `"All"`, all markers are returned.
     * - Otherwise, only markers whose [MarkerItem.tag] matches are returned.
     */
    val filteredMarkers: StateFlow<List<MarkerItem>> =
        combine(_allMarkers, _selectedTag) { markers, tag ->
            if (tag == null || tag == MarkerItem.TAG_ALL) {
                markers
            } else {
                markers.filter { it.tag == tag }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // ── Mock initial markers ─────────────────────────────────────

    init {
        loadMockMarkers()
    }

    /**
     * Seeds the map with mock markers across different transport tags.
     * In the final MVP, these will be replaced by real data from the backend.
     */
    private fun loadMockMarkers() {
        _allMarkers.value = listOf(
            MarkerItem(
                title = "Warehouse A \u2013 Entrance",
                snippet = "Walking entrance for pedestrians",
                latitude = 50.4501,
                longitude = 30.5234,
                tag = "Walking"
            ),
            MarkerItem(
                title = "Warehouse A \u2013 Bike Rack",
                snippet = "Bicycle parking and docking station",
                latitude = 50.4507,
                longitude = 30.5240,
                tag = "Bicycle"
            ),
            MarkerItem(
                title = "Warehouse A \u2013 Loading Bay",
                snippet = "Vehicle loading/unloading zone",
                latitude = 50.4498,
                longitude = 30.5228,
                tag = "Car"
            ),
            MarkerItem(
                title = "Courier Hub \u2013 Entrance",
                snippet = "Main pedestrian entrance to courier hub",
                latitude = 50.4515,
                longitude = 30.5255,
                tag = "Walking"
            ),
            MarkerItem(
                title = "Courier Hub \u2013 Parking",
                snippet = "Car parking for courier vehicles",
                latitude = 50.4510,
                longitude = 30.5262,
                tag = "Car"
            ),
            MarkerItem(
                title = "Courier Hub \u2013 Bike Lane",
                snippet = "Dedicated bicycle lane access point",
                latitude = 50.4520,
                longitude = 30.5250,
                tag = "Bicycle"
            ),
            MarkerItem(
                title = "Underground Facility \u2013 Stairs B1",
                snippet = "Walking access to basement level B1",
                latitude = 50.4495,
                longitude = 30.5230,
                tag = "Walking"
            ),
            MarkerItem(
                title = "Underground Facility \u2013 Ramp",
                snippet = "Vehicle ramp to basement parking",
                latitude = 50.4492,
                longitude = 30.5225,
                tag = "Car"
            )
        )
    }

    // ── Public API ───────────────────────────────────────────────

    /**
     * Sets the current filter tag.
     *
     * @param tag The tag to filter by. Pass `null` or [MarkerItem.TAG_ALL] to show all markers.
     */
    fun setFilter(tag: String?) {
        _selectedTag.value = tag
    }

    /**
     * Adds a new marker at the given coordinates.
     * The marker is assigned the currently selected tag (or "Walking" if "All" is active).
     *
     * @param latitude  WGS‑84 latitude.
     * @param longitude WGS‑84 longitude.
     */
    fun addMarker(latitude: Double, longitude: Double) {
        val currentTag = _selectedTag.value
        val assignedTag = when {
            currentTag == null || currentTag == MarkerItem.TAG_ALL -> "Walking"
            else -> currentTag
        }

        val newMarker = MarkerItem(
            title = "Delivery Point",
            snippet = "Tag: $assignedTag \u2014 Tap to edit",
            latitude = latitude,
            longitude = longitude,
            tag = assignedTag
        )

        _allMarkers.value = _allMarkers.value + newMarker
    }

    /**
     * Removes a marker by its ID.
     */
    fun removeMarker(markerId: String) {
        _allMarkers.value = _allMarkers.value.filter { it.id != markerId }
    }

    /**
     * Returns the total count of placed markers.
     */
    fun markerCount(): Int = _allMarkers.value.size
}
