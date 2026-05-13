package com.navisense.data

import com.navisense.data.local.DeliveryHistoryDao
import com.navisense.data.local.SavedLocation
import com.navisense.data.local.SavedLocationDao
import com.navisense.model.AppLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Room-backed implementation of [LocationRepository].
 *
 * Maps between the domain model ([AppLocation]) and the Room entity
 * ([SavedLocation]) using explicit two-way mappers. All read operations
 * are reactive — [getAllLocations] returns a [StateFlow] that auto-emits
 * whenever the underlying `saved_locations` table changes via Room's
 * invalidation tracker.
 *
 * Write operations are delegated directly to [SavedLocationDao] suspend
 * functions. For toggle operations, atomic SQL `NOT` expressions are used
 * so no read-modify-write cycle is needed.
 *
 * **Local‑first architecture:** this is the only repository implementation
 * used at runtime. There are no hardcoded seed locations — the app relies
 * entirely on what the user saves locally via the Room database.
 *
 * @param savedLocationDao    The DAO for the `saved_locations` table.
 * @param deliveryHistoryDao  The DAO for the `delivery_history` table
 *                            (injected to satisfy the interface contract;
 *                             delivery logic is handled separately).
 * @param scope               The [CoroutineScope] used to convert the
 *                            Room [Flow] into a hot [StateFlow] via
 *                            [stateIn]. Typically [androidx.lifecycle.viewModelScope].
 */
class RoomLocationRepositoryImpl(
    private val savedLocationDao: SavedLocationDao,
    private val deliveryHistoryDao: DeliveryHistoryDao,
    private val scope: CoroutineScope
) : LocationRepository {

    // ── Mapping helpers ─────────────────────────────────────────────

    /** Map a Room entity to the domain model. */
    private fun SavedLocation.toAppLocation(): AppLocation = AppLocation(
        id = id.toInt(),
        title = name,
        description = description,
        latitude = latitude,
        longitude = longitude,
        category = category,
        imageUri = imageUri,
        isVisited = isVisited,
        isFavorite = isFavorite
    )

    /** Map a domain model back to a Room entity. */
    private fun AppLocation.toSavedLocation(): SavedLocation = SavedLocation(
        id = id.toLong(),
        name = title,
        description = description,
        category = category,
        latitude = latitude,
        longitude = longitude,
        imageUri = imageUri,
        isVisited = isVisited,
        isFavorite = isFavorite
    )

    // ── Reactive stream ─────────────────────────────────────────────

    /**
     * Observe all saved locations as a reactive [StateFlow].
     *
     * Room's [SavedLocationDao.getAll] returns a **cold** [Flow] that emits
     * whenever the underlying table changes. This is converted to a **hot**
     * [StateFlow] via [stateIn] so the ViewModel can observe it safely
     * across configuration changes.
     */
    override fun getAllLocations(): StateFlow<List<AppLocation>> =
        savedLocationDao.getAll()
            .map { entities -> entities.map { it.toAppLocation() } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Single-shot reads ───────────────────────────────────────────

    /** Look up a single location by its domain [id]. */
    override suspend fun getLocationById(id: Int): AppLocation? =
        savedLocationDao.getById(id.toLong())?.toAppLocation()

    // ── Writes ──────────────────────────────────────────────────────

    /**
     * Insert a new location.
     *
     * The [AppLocation.id] is ignored (set to `0`) so Room auto-generates
     * the primary key. The newly assigned database row ID is returned.
     */
    override suspend fun insertLocation(location: AppLocation): Int {
        val entity = location.toSavedLocation().copy(id = 0L)
        return savedLocationDao.insert(entity).toInt()
    }

    /** Update an existing location (matched by [AppLocation.id]). */
    override suspend fun updateLocation(location: AppLocation) {
        savedLocationDao.update(location.toSavedLocation())
    }

    /** Delete a location by its domain [id]. */
    override suspend fun deleteLocation(id: Int) {
        savedLocationDao.deleteById(id.toLong())
    }

    /**
     * Atomically toggle the [AppLocation.isVisited] flag at the database
     * level using a SQL `NOT` expression.
     */
    override suspend fun toggleVisited(id: Int) {
        savedLocationDao.toggleVisited(id.toLong())
    }

    /**
     * Atomically toggle the [AppLocation.isFavorite] flag at the database
     * level using a SQL `NOT` expression.
     */
    override suspend fun toggleFavorite(id: Int) {
        savedLocationDao.toggleFavorite(id.toLong())
    }
}
