package com.navisense.model

/**
 * Represents the "freshness" of the last known visual-pin location.
 *
 * Used by the Scanner mode to indicate how reliable the current
 * visual-locate result is.
 *
 * - [FRESH]:      0–30 seconds since the location was set.
 * - [DEGRADING]:  31–120 seconds since the location was set.
 * - [STALE]:      More than 120 seconds since the location was set.
 */
enum class LocationState {
    FRESH,
    DEGRADING,
    STALE
}
