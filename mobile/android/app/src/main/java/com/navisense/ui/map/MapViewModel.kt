package com.navisense.ui.map

import androidx.lifecycle.ViewModel
import com.navisense.core.FusionResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigation mode for the single-screen NaviSense 2.0 UI.
 */
enum class NavMode {
    PEDESTRIAN,
    TRANSPORT,
}

/**
 * Single ViewModel for the NaviSense 2.0 map screen.
 *
 * Manages:
 * - [currentMode] — Pedestrian or Transport
 * - [isTracking] — whether a trip is actively running
 * - [destinationQuery] — the user-entered destination text
 * - [pathHistory] — accumulated list of fusion results for map rendering
 */
class MapViewModel : ViewModel() {

    // ── Mode ───────────────────────────────────────────────────────────
    private val _currentMode = MutableStateFlow(NavMode.PEDESTRIAN)
    val currentMode: StateFlow<NavMode> = _currentMode.asStateFlow()

    /**
     * Switch the navigation mode.
     * Does NOT reset [isTracking] — tracking continues across mode changes
     * as per the spec ("History is NEVER deleted when switching modes").
     */
    fun setMode(mode: NavMode) {
        _currentMode.value = mode
    }

    // ── Tracking ───────────────────────────────────────────────────────
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    /** Start a trip. */
    fun startTracking() {
        _isTracking.value = true
    }

    /** End the current trip. */
    fun stopTracking() {
        _isTracking.value = false
    }

    // ── Destination ────────────────────────────────────────────────────
    private val _destinationQuery = MutableStateFlow("")
    val destinationQuery: StateFlow<String> = _destinationQuery.asStateFlow()

    /** Update the destination search text. */
    fun setDestinationQuery(query: String) {
        _destinationQuery.value = query
    }

    // ── Path History (Fusion Results) ──────────────────────────────────

    private val _pathHistory = MutableStateFlow<List<FusionResponse>>(emptyList())
    val pathHistory: StateFlow<List<FusionResponse>> = _pathHistory.asStateFlow()

    /**
     * Append a new fusion result point to the path history.
     * The map fragment observes this list and re-renders the green polyline
     * + current-location marker whenever it changes.
     */
    fun addPathPoint(point: FusionResponse) {
        _pathHistory.value = _pathHistory.value + point
    }
}
