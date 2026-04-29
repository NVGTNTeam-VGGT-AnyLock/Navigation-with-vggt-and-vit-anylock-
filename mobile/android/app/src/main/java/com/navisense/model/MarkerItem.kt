package com.navisense.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * Represents a single map marker placed by the user.
 *
 * Each marker is assigned a [tag] from a predefined set of transport modes:
 * - "Walking" — pedestrian routes
 * - "Bicycle" — cycling routes
 * - "Car" — driving routes
 *
 * @property id Unique identifier for this marker.
 * @property title Human-readable label displayed in the info window.
 * @property snippet Additional description shown in the info window.
 * @property latitude WGS‑84 latitude of the marker.
 * @property longitude WGS‑84 longitude of the marker.
 * @property tag Transport-mode tag used for filtering.
 */
@Parcelize
data class MarkerItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val snippet: String,
    val latitude: Double,
    val longitude: Double,
    val tag: String
) : Parcelable {

    companion object {
        /** Supported transport-mode filter tags. */
        val TAGS = listOf("Walking", "Bicycle", "Car")

        /** The tag assigned when no specific filter is active ("All"). */
        const val TAG_ALL = "All"
    }
}
