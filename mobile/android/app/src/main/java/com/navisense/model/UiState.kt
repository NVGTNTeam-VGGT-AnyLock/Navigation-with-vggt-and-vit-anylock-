package com.navisense.model

/**
 * Shared UI state for the scanning/navigation ViewModels ([TransportViewModel],
 * [PedestrianViewModel]).
 *
 * This is the single source of truth that each scanning fragment observes
 * and renders. All fields are nullable / defaulted so the initial state
 * represents "no data available yet".
 *
 * @property latitude     WGS‑84 latitude estimated by ViT visual place
 *                        recognition, or `null` if not yet determined.
 * @property longitude    WGS‑84 longitude estimated by ViT visual place
 *                        recognition, or `null` if not yet determined.
 * @property bearing      Heading in degrees [0, 360) computed from the
 *                        VGGT-1B camera‑centre offset, or `null` if not
 *                        yet available. 0° = forward, 90° = right.
 * @property isStale      `true` when the last successful scan is older than
 *                        the staleness threshold (typically 120 s). The UI
 *                        should show a visual warning (e.g. orange indicator).
 * @property isScanning   `true` while a capture-and-upload cycle is in
 *                        progress. The UI should show a loading spinner or
 *                        progress overlay.
 * @property errorMessage Non‑null when the last scan cycle failed. The UI
 *                        should display an error snackbar / toast and
 *                        automatically clear after being shown.
 */
data class UiState(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val bearing: Double? = null,
    val isStale: Boolean = false,
    val isScanning: Boolean = false,
    val errorMessage: String? = null
) {

    /**
     * Returns `true` if any geolocation data (lat, lng or bearing) is
     * present — useful for the fragment to decide whether to show a
     * "no data" placeholder vs. a live map overlay.
     */
    val hasData: Boolean
        get() = latitude != null || longitude != null || bearing != null

    /**
     * Returns a copy with [errorMessage] cleared — call this after the
     * UI has displayed the error to the user so a subsequent recomposition
     * does not re‑trigger the same error snackbar.
     */
    fun clearError(): UiState = copy(errorMessage = null)
}
