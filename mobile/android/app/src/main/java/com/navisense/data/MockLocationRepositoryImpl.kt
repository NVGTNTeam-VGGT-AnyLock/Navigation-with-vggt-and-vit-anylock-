package com.navisense.data

import android.content.Context
import com.navisense.R
import com.navisense.model.AppLocation
import com.navisense.model.AppLocationCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory mock implementation of [LocationRepository].
 *
 * **Localization:** Seed data is defined via string resource IDs so titles and
 * descriptions are resolved through [Context] at runtime. Call
 * [refreshLocalizedData] after the application locale changes to re-resolve all
 * strings from the current locale's resources.
 *
 * **State persistence across locale switches:** Mutable state (visited, favorite)
 * is tracked directly inside [SeedRef] instances stored in [seedRefs]. When
 * [toggleVisited], [toggleFavorite] or [updateLocation] touch a seed location,
 * the corresponding [SeedRef] is mutated **before** [resolveAll] is called, so
 * the state survives [refreshLocalizedData].
 *
 * **Anya:** When Room is ready, create a `RoomLocationRepositoryImpl` that
 * implements the same [LocationRepository] interface, inject it in place of
 * this class, and the entire app just works — no ViewModel / UI changes needed.
 */
class MockLocationRepositoryImpl(private val context: Context) : LocationRepository {

    /** Internal metadata for seed locations (resource IDs, not resolved strings). */
    private data class SeedRef(
        val id: Int,
        val titleRes: Int,
        val descRes: Int,
        val lat: Double,
        val lng: Double,
        val category: String,
        /** Mutable — preserved across [refreshLocalizedData] calls. */
        var visited: Boolean,
        /** Mutable — preserved across [refreshLocalizedData] calls. */
        var favorite: Boolean
    )

    /**
     * All seed references — defined once, mutated in-place by toggle/update
     * operations so state survives language switches.
     */
    private val seedRefs = mutableListOf(
        // 1: Kyiv Pechersk Lavra
        SeedRef(1, R.string.mock_loc_1_title, R.string.mock_loc_1_desc,
            50.4347, 30.5590, AppLocationCategory.MONUMENT.key, visited = true, favorite = true),
        // 2: St. Sophia's Cathedral
        SeedRef(2, R.string.mock_loc_2_title, R.string.mock_loc_2_desc,
            50.4547, 30.5190, AppLocationCategory.MONUMENT.key, visited = false, favorite = true),
        // 3: ARSENAL Art Metro Station Mural
        SeedRef(3, R.string.mock_loc_3_title, R.string.mock_loc_3_desc,
            50.4444, 30.5385, AppLocationCategory.MONUMENT.key, visited = true, favorite = false),
        // 4: Kyiv Food Market
        SeedRef(4, R.string.mock_loc_4_title, R.string.mock_loc_4_desc,
            50.4584, 30.5158, AppLocationCategory.RESTAURANT.key, visited = false, favorite = false),
        // 5: Silpo – Kontraktova Square
        SeedRef(5, R.string.mock_loc_5_title, R.string.mock_loc_5_desc,
            50.4664, 30.5150, AppLocationCategory.GROCERY.key, visited = false, favorite = false),
        // 6: OKKO Gas Station (Obolon)
        SeedRef(6, R.string.mock_loc_6_title, R.string.mock_loc_6_desc,
            50.5019, 30.4983, AppLocationCategory.GAS_STATION.key, visited = true, favorite = false),
        // 7: Pharmacy 3A (Khreschatyk)
        SeedRef(7, R.string.mock_loc_7_title, R.string.mock_loc_7_desc,
            50.4474, 30.5215, AppLocationCategory.PHARMACY.key, visited = false, favorite = true),
        // 8: Kanapa Restaurant
        SeedRef(8, R.string.mock_loc_8_title, R.string.mock_loc_8_desc,
            50.4605, 30.5140, AppLocationCategory.RESTAURANT.key, visited = true, favorite = false),
        // 9: Mural 'Usiky' by WIZ-ART
        SeedRef(9, R.string.mock_loc_9_title, R.string.mock_loc_9_desc,
            50.4385, 30.5490, AppLocationCategory.MONUMENT.key, visited = false, favorite = false),
        // 10: Novus – Lvivska Ploshcha
        SeedRef(10, R.string.mock_loc_10_title, R.string.mock_loc_10_desc,
            50.4498, 30.5100, AppLocationCategory.GROCERY.key, visited = false, favorite = false)
    )

    /**
     * Locations added at runtime by the user (stored as-is, not resource-backed).
     * When a seed location is edited via [updateLocation], it is removed from
     * [seedRefs] and moved here as a plain [AppLocation].
     */
    private val addedLocations = mutableListOf<AppLocation>()

    /** Resolved + user locations emitted as a reactive stream. */
    private val _locations = MutableStateFlow(resolveAll())

    /** Resolve a [SeedRef] into a fully-localized [AppLocation] using the current [Context]. */
    private fun SeedRef.toAppLocation(): AppLocation = AppLocation(
        id = id,
        title = context.getString(titleRes),
        description = context.getString(descRes),
        latitude = lat,
        longitude = lng,
        category = category,
        isVisited = visited,
        isFavorite = favorite
    )

    /** Combine resolved seed data with user-added locations. Reads current [SeedRef] state. */
    private fun resolveAll(): List<AppLocation> =
        seedRefs.map { it.toAppLocation() } + addedLocations

    // ── Public API ──────────────────────────────────────────────────

    override fun getAllLocations(): StateFlow<List<AppLocation>> =
        _locations.asStateFlow()

    /**
     * Re-resolve all seed location strings from the current locale's resources
     * and emit the updated list. Call this after [android.os.LocaleList] changes
     * so the UI reflects the newly selected language.
     *
     * **State safety:** Visited/favorite toggles applied via [toggleVisited] /
     * [toggleFavorite] are stored inside [seedRefs] and will **not** be wiped
     * by this call.
     */
    fun refreshLocalizedData() {
        _locations.value = resolveAll()
    }

    override suspend fun getLocationById(id: Int): AppLocation? =
        _locations.value.firstOrNull { it.id == id }

    override suspend fun insertLocation(location: AppLocation): Int {
        val newId = (_locations.value.maxOfOrNull { it.id } ?: 0) + 1
        val newLocation = location.copy(id = newId)
        addedLocations.add(newLocation)
        _locations.value = _locations.value + newLocation
        return newId
    }

    override suspend fun updateLocation(location: AppLocation) {
        // If it was a seed location, remove from seeds and track as user-modified
        seedRefs.removeAll { it.id == location.id }

        // Update in addedLocations or add as user-modified
        val addedIndex = addedLocations.indexOfFirst { it.id == location.id }
        if (addedIndex >= 0) {
            addedLocations[addedIndex] = location
        } else {
            addedLocations.add(location)
        }

        _locations.value = resolveAll()
    }

    override suspend fun deleteLocation(id: Int) {
        // Remove from user-added list
        addedLocations.removeAll { it.id == id }
        // Remove from seeds (user explicitly deleted it)
        seedRefs.removeAll { it.id == id }
        // Re-emit
        _locations.value = resolveAll()
    }

    override suspend fun toggleVisited(id: Int) {
        // Mutate SeedRef directly so state survives refreshLocalizedData()
        seedRefs.firstOrNull { it.id == id }?.let { seed ->
            seed.visited = !seed.visited
        }
        // Also update in addedLocations if present
        addedLocations.replaceAll { if (it.id == id) it.copy(isVisited = !it.isVisited) else it }
        // Re-emit combined list
        _locations.value = resolveAll()
    }

    override suspend fun toggleFavorite(id: Int) {
        // Mutate SeedRef directly so state survives refreshLocalizedData()
        seedRefs.firstOrNull { it.id == id }?.let { seed ->
            seed.favorite = !seed.favorite
        }
        // Also update in addedLocations if present
        addedLocations.replaceAll { if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it }
        // Re-emit combined list
        _locations.value = resolveAll()
    }
}
