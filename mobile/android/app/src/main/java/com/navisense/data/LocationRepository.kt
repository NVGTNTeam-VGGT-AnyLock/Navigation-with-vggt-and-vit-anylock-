package com.navisense.data

import com.navisense.model.AppLocation
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository contract for [AppLocation] CRUD operations.
 *
 * **Design note for Anya (Room implementer):**
 * - The interface is intentionally suspend / Flow-based so it maps cleanly
 *   to Room DAO + Kotlin Coroutines.
 * - [getAllLocations] returns a [StateFlow] so the UI auto-updates whenever
 *   the underlying data changes (Room + Flow does this natively).
 * - When you replace [MockLocationRepositoryImpl] with a real Room-based
 *   implementation, just implement this same interface and swap the
 *   provider in [com.navisense.ui.MainViewModel].
 */
interface LocationRepository {

    /** Observe all locations as a reactive stream. */
    fun getAllLocations(): StateFlow<List<AppLocation>>

    /**
     * Get a single location by its [id].
     * Returns `null` if no location with that [id] exists.
     */
    suspend fun getLocationById(id: Int): AppLocation?

    /** Insert a new location and return its assigned [AppLocation.id]. */
    suspend fun insertLocation(location: AppLocation): Int

    /** Update an existing location (matched by [AppLocation.id]). */
    suspend fun updateLocation(location: AppLocation)

    /** Delete a location by its [id]. */
    suspend fun deleteLocation(id: Int)

    /**
     * Toggle the [AppLocation.isVisited] flag for a given [id].
     * Convenience method used by the "Mark as Visited" button.
     */
    suspend fun toggleVisited(id: Int)
}
