package com.navisense.data

import com.navisense.model.AppLocation
import com.navisense.model.AppLocationCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory mock implementation of [LocationRepository].
 *
 * **Anya:** When Room is ready, create a `RoomLocationRepositoryImpl` that
 * implements the same [LocationRepository] interface, inject it in place of
 * this class, and the entire app just works — no ViewModel / UI changes needed.
 *
 * Seed data represents notable points of interest around Kyiv, Ukraine.
 */
class MockLocationRepositoryImpl : LocationRepository {

    private val _locations = MutableStateFlow<List<AppLocation>>(SEED_DATA)

    override fun getAllLocations(): StateFlow<List<AppLocation>> =
        _locations.asStateFlow()

    override suspend fun getLocationById(id: Int): AppLocation? =
        _locations.value.firstOrNull { it.id == id }

    override suspend fun insertLocation(location: AppLocation): Int {
        val newId = (_locations.value.maxOfOrNull { it.id } ?: 0) + 1
        val newLocation = location.copy(id = newId)
        _locations.value = _locations.value + newLocation
        return newId
    }

    override suspend fun updateLocation(location: AppLocation) {
        _locations.value = _locations.value.map {
            if (it.id == location.id) location else it
        }
    }

    override suspend fun deleteLocation(id: Int) {
        _locations.value = _locations.value.filter { it.id != id }
    }

    override suspend fun toggleVisited(id: Int) {
        _locations.value = _locations.value.map {
            if (it.id == id) it.copy(isVisited = !it.isVisited) else it
        }
    }

    companion object {
        /** Seed data: well-known landmarks / street art / points of interest
         *  around Kyiv, demonstrating varied categories. */
        private val SEED_DATA = listOf(
            AppLocation(
                id = 1,
                title = "Kyiv Pechersk Lavra",
                description = "Historic Orthodox Christian monastery, one of the most sacred sites in Ukraine.",
                latitude = 50.4347,
                longitude = 30.5590,
                category = AppLocationCategory.MONUMENT.key,
                isVisited = true
            ),
            AppLocation(
                id = 2,
                title = "St. Sophia's Cathedral",
                description = "UNESCO World Heritage site with stunning mosaics and frescoes from the 11th century.",
                latitude = 50.4547,
                longitude = 30.5190,
                category = AppLocationCategory.MONUMENT.key,
                isVisited = false
            ),
            AppLocation(
                id = 3,
                title = "ARSENAL Art Metro Station Mural",
                description = "Massive contemporary mural inside the ARSENAL metro station, Kyiv's street art gem.",
                latitude = 50.4444,
                longitude = 30.5385,
                category = AppLocationCategory.MONUMENT.key,
                isVisited = true
            ),
            AppLocation(
                id = 4,
                title = "Kyiv Food Market",
                description = "Trendy food court with local Ukrainian cuisine and international options.",
                latitude = 50.4584,
                longitude = 30.5158,
                category = AppLocationCategory.RESTAURANT.key,
                isVisited = false
            ),
            AppLocation(
                id = 5,
                title = "Silpo – Kontraktova Square",
                description = "Supermarket with a curated selection of local produce and groceries.",
                latitude = 50.4664,
                longitude = 30.5150,
                category = AppLocationCategory.GROCERY.key,
                isVisited = false
            ),
            AppLocation(
                id = 6,
                title = "OKKO Gas Station (Obolon)",
                description = "Full-service gas station with convenience store and car wash.",
                latitude = 50.5019,
                longitude = 30.4983,
                category = AppLocationCategory.GAS_STATION.key,
                isVisited = true
            ),
            AppLocation(
                id = 7,
                title = "Pharmacy 3A (Khreschatyk)",
                description = "24-hour pharmacy in central Kyiv with a wide range of medications.",
                latitude = 50.4474,
                longitude = 30.5215,
                category = AppLocationCategory.PHARMACY.key,
                isVisited = false
            ),
            AppLocation(
                id = 8,
                title = "Kanapa Restaurant",
                description = "Modern Ukrainian cuisine in a historic building overlooking Andriyivskyi descent.",
                latitude = 50.4605,
                longitude = 30.5140,
                category = AppLocationCategory.RESTAURANT.key,
                isVisited = true
            ),
            AppLocation(
                id = 9,
                title = "Mural 'Usiky' by WIZ-ART",
                description = "Famous street art mural on a residential building, part of the Kyiv Mural Project.",
                latitude = 50.4385,
                longitude = 30.5490,
                category = AppLocationCategory.MONUMENT.key,
                isVisited = false
            ),
            AppLocation(
                id = 10,
                title = "Novus – Lvivska Ploshcha",
                description = "Modern supermarket with organic section and bakery.",
                latitude = 50.4498,
                longitude = 30.5100,
                category = AppLocationCategory.GROCERY.key,
                isVisited = false
            )
        )
    }
}
