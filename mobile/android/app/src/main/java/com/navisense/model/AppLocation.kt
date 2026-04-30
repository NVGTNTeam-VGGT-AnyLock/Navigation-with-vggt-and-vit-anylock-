package com.navisense.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Core data model for a location in the Location Management App.
 *
 * This is the single source of truth used across all screens (Map, Analytics,
 * Route Builder, etc.). Anya will map this to a Room @Entity when the
 * SQL layer is ready.
 *
 * @property id          Unique identifier.
 * @property title       Human-readable name (e.g., "Kyiv Pechersk Lavra").
 * @property description Detailed description of the location.
 * @property latitude    WGS‑84 latitude.
 * @property longitude   WGS‑84 longitude.
 * @property category    Category string (one of [AppLocationCategory.names]).
 * @property imageUri    URI string of an attached photo (local content URI or empty).
 * @property isVisited   Whether the user has visited this location.
 */
@Parcelize
data class AppLocation(
    val id: Int = 0,
    val title: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val category: String = AppLocationCategory.MONUMENT.key,
    val imageUri: String = "",
    val isVisited: Boolean = false
) : Parcelable
