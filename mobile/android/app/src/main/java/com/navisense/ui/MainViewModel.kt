package com.navisense.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.google.maps.DirectionsApi
import com.google.maps.GeoApiContext
import com.google.maps.android.PolyUtil
import com.navisense.BuildConfig
import com.navisense.core.NaviSenseApi
import com.navisense.core.VisualLocateResponse
import com.navisense.core.VggtOdometryResponse
import com.navisense.data.LocationRepository
import com.navisense.data.RoomLocationRepositoryImpl
import com.navisense.data.local.AppDatabase
import com.navisense.model.AppLocation
import com.navisense.model.AppLocationCategory
import com.navisense.model.LocationState
import com.navisense.model.NavMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Shared ViewModel for the entire Location Management App.
 *
 * Uses [LocationRepository] for all data access. Backed by
 * [RoomLocationRepositoryImpl] which persists all location data in a Room
 * SQLite database — local-first architecture with no hardcoded seed data.
 *
 * Exposes:
 * - [allLocations] — unfiltered list from the repository
 * - [selectedCategory] — active category filter (null = All)
 * - [searchQuery] — fuzzy search across Title, Description, Category
 * - [showFavoritesOnly] — filter for favorites only
 * - [showVisitedOnly] — visited status filter (null = all, true = visited, false = not visited)
 * - [filteredLocations] — derived StateFlow combining all filters
 * - [selectedRadiusKm] — radius filter for map
 * - [analyticsData] — computed stats for the Analytics screen
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /**
         * Singleton [GeoApiContext] initialised once with the API key from
         * [BuildConfig]. Used for all Google Maps API requests (Directions, etc.).
         */
        private val geoApiContext: GeoApiContext by lazy {
            GeoApiContext.Builder()
                .apiKey(BuildConfig.MAPS_API_KEY)
                .build()
        }

        /** Retrofit [NaviSenseApi] singleton for the sensor‑fusion pipeline. */
        private val naviSenseApi: NaviSenseApi by lazy {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(loggingInterceptor)
                .build()

            Retrofit.Builder()
                .baseUrl(BuildConfig.BACKEND_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(NaviSenseApi::class.java)
        }

        /** Number of frames in the burst capture sequence. */
        private const val BURST_FRAME_COUNT = 5

        /** Asset filenames for the simulated burst sequence. */
        private val BURST_ASSET_NAMES = (1..BURST_FRAME_COUNT).map { "ref$it.jpg" }

        /** Tag for [Log] statements. */
        private const val TAG = "MainViewModel"
    }

    // ── Database ────────────────────────────────────────────────────
    private val db = AppDatabase.getInstance(application)

    // ── Delivery‑history DAO (used by the KPI cards in Analytics) ───
    private val deliveryHistoryDao = db.deliveryHistoryDao()

    // ── Repository (Room-backed, local-first) ───────────────────────
    private val repository: LocationRepository = RoomLocationRepositoryImpl(
        savedLocationDao = db.savedLocationDao(),
        deliveryHistoryDao = deliveryHistoryDao,
        scope = viewModelScope
    )

    // ── State: All Locations ───────────────────────────────────────
    val allLocations: StateFlow<List<AppLocation>> = repository.getAllLocations()

    // ── State: Category Filter ─────────────────────────────────────
    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    // ── State: Search Query ────────────────────────────────────────
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ── State: Favorites Filter ────────────────────────────────────
    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    // ── State: Visited Status Filter ───────────────────────────────
    // null = no filter, true = visited only, false = not visited only
    private val _visitedFilter = MutableStateFlow<Boolean?>(null)
    val visitedFilter: StateFlow<Boolean?> = _visitedFilter.asStateFlow()

    /** Filtered list derived from all filter criteria. */
    val filteredLocations: StateFlow<List<AppLocation>> =
        combine(
            allLocations,
            _selectedCategory,
            _searchQuery,
            _showFavoritesOnly,
            _visitedFilter
        ) { locations, category, query, favoritesOnly, visitedFilter ->
            var result = locations

            // Category filter
            if (category != null) {
                // КРИТИЧНИЙ ФІКС: Безпечне порівняння без врахування регістру
                result = result.filter { it.category.equals(category, ignoreCase = true) }
            }

            // Fuzzy search: query matches title, description, OR category
            if (query.isNotBlank()) {
                val q = query.lowercase().trim()
                result = result.filter { loc ->
                    loc.title.lowercase().contains(q) ||
                    loc.description.lowercase().contains(q) ||
                    loc.category.lowercase().contains(q)
                }
            }

            // Favorites filter
            if (favoritesOnly) {
                result = result.filter { it.isFavorite }
            }

            // Visited status filter
            if (visitedFilter != null) {
                result = result.filter { it.isVisited == visitedFilter }
            }

            result
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── State: Radius Filter (km) ──────────────────────────────────
    private val _selectedRadiusKm = MutableStateFlow<Int?>(null) // null = no filter
    val selectedRadiusKm: StateFlow<Int?> = _selectedRadiusKm.asStateFlow()

    // ── State: Route Builder selections ────────────────────────────
    private val _routeWaypoints = MutableStateFlow<List<AppLocation>>(emptyList())
    val routeWaypoints: StateFlow<List<AppLocation>> = _routeWaypoints.asStateFlow()

    // ── State: Optimized route polyline points ─────────────────────
    private val _routePolylinePoints = MutableStateFlow<List<LatLng>>(emptyList())
    val routePolylinePoints: StateFlow<List<LatLng>> = _routePolylinePoints.asStateFlow()

    // ── State: Optimisation in progress ────────────────────────────
    private val _isOptimizing = MutableStateFlow(false)
    val isOptimizing: StateFlow<Boolean> = _isOptimizing.asStateFlow()

    // ── State: Visual Search Mock Result ──────────────────────────
    private val _mockMatchLocation = MutableStateFlow<AppLocation?>(null)
    val mockMatchLocation: StateFlow<AppLocation?> = _mockMatchLocation.asStateFlow()

    // ── State: Navigation Mode ────────────────────────────────────
    private val _navMode = MutableStateFlow(NavMode.SCANNER)
    val navMode: StateFlow<NavMode> = _navMode.asStateFlow()

    // ── State: Location Freshness (Scanner mode only) ────────────
    private val _locationState = MutableStateFlow(LocationState.FRESH)
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    /** Timestamp (System.currentTimeMillis()) when the last visual pin was placed. */
    private var lastLocationTimestampMs: Long = 0L

    // ── State: Visual Pin (from ViT backend) ──────────────────────
    private val _visualPinLocation = MutableStateFlow<AppLocation?>(null)
    val visualPinLocation: StateFlow<AppLocation?> = _visualPinLocation.asStateFlow()

    // ── Analytics (computed) ───────────────────────────────────────
    data class AnalyticsData(
        val categoryCounts: Map<String, Int>,
        val visitedCount: Int,
        val notVisitedCount: Int,
        val favoriteCount: Int,
        val notFavoriteCount: Int,
        val districtCounts: Map<String, Int>,
        val totalCount: Int
    )

    val analyticsData: StateFlow<AnalyticsData> =
        allLocations.combine(MutableStateFlow(Unit)) { locations, _ ->
            val categoryCounts = locations.groupBy { it.category }.mapValues { it.value.size }
            val visitedCount = locations.count { it.isVisited }
            val favoriteCount = locations.count { it.isFavorite }
            val districtCounts = locations.groupBy { detectDistrict(it.latitude, it.longitude) }
                .mapValues { it.value.size }
            AnalyticsData(
                categoryCounts = categoryCounts,
                visitedCount = visitedCount,
                notVisitedCount = locations.size - visitedCount,
                favoriteCount = favoriteCount,
                notFavoriteCount = locations.size - favoriteCount,
                districtCounts = districtCounts,
                totalCount = locations.size
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            AnalyticsData(emptyMap(), 0, 0, 0, 0, emptyMap(), 0))

    // ── Delivery History Summary (for KPI cards) ────────────────────

    /**
     * Aggregated delivery statistics derived from the Room [DeliveryHistory] database.
     *
     * @property totalDistanceKm   Summed Haversine distance of all completed deliveries.
     * @property timeSavedMin      Total estimated time saved (from delivery_history).
     * @property gpsStabilityScore 0–100 score: 100 = no GPS drops across all trips.
     */
    data class DeliverySummary(
        val totalDistanceKm: Double,
        val timeSavedMin: Long,
        val gpsStabilityScore: Int
    )

    /**
     * Reactive StateFlow that re-computes the delivery summary whenever the
     * [DeliveryHistoryDao.getAllDeliveries] Flow emits new data.
     */
    val deliverySummary: StateFlow<DeliverySummary> =
        deliveryHistoryDao.getAllDeliveries().map { deliveries ->
            if (deliveries.isEmpty()) {
                DeliverySummary(0.0, 0L, 100)
            } else {
                // Compute total distance using Haversine approximation
                val totalMeters = deliveries.sumOf { delivery ->
                    haversineMeters(
                        delivery.startPointLat, delivery.startPointLng,
                        delivery.endPointLat, delivery.endPointLng
                    )
                }
                val totalDistanceKm = totalMeters / 1000.0
                val totalTimeSavedSec = deliveries.sumOf { it.timeSavedSeconds }
                val totalTimeSavedMin = totalTimeSavedSec / 60

                // GPS Stability: 100 - (totalDrops / totalDeliveries) * 20, clamped to 0..100
                val totalDrops = deliveries.sumOf { it.gpsDropsCount }
                val rawScore = 100 - ((totalDrops.toDouble() / deliveries.size) * 20)
                val gpsScore = rawScore.coerceIn(0.0, 100.0).toInt()

                DeliverySummary(totalDistanceKm, totalTimeSavedMin, gpsScore)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            DeliverySummary(0.0, 0L, 100))

    /**
     * Approximate Haversine distance between two WGS‑84 coordinates.
     * @return Distance in meters.
     */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6_371_000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = (Math.sin(dLat / 2)).pow(2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                (Math.sin(dLng / 2)).pow(2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    /**
     * Mock district detection based on geographic bounds of Kyiv.
     * Maps a coordinate to one of Kyiv's districts.
     */
    private fun detectDistrict(lat: Double, lng: Double): String {
        return when {
            // Shevchenkivskyi (central-west)
            lat in 50.440..50.470 && lng in 30.490..30.520 -> "Shevchenkivskyi"
            // Pecherskyi (central-east, government district)
            lat in 50.420..50.450 && lng in 30.530..30.560 -> "Pecherskyi"
            // Podilskyi (north, historic port area)
            lat in 50.460..50.520 && lng in 30.490..30.520 -> "Podilskyi"
            // Obolonskyi (north-west, residential)
            lat > 50.490 && lng < 30.530 -> "Obolonskyi"
            // Darnyrskyi (east, left bank)
            lng > 30.560 -> "Darnyrskyi"
            // Solomyanskyi (south-west, railway hub)
            lat < 50.430 && lng < 30.530 -> "Solomyanskyi"
            // Holosiivskyi (south, green area)
            lat < 50.420 -> "Holosiivskyi"
            // Desnyanskyi (north-east, left bank)
            else -> "Desnyanskyi"
        }
    }

    // ── Public API ─────────────────────────────────────────────────

    /** Set the active category filter. Pass `null` to show all. */
    fun setCategoryFilter(category: String?) {
        _selectedCategory.value = category
    }

    /** Update the search query string. */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Toggle the favorites-only filter. */
    fun toggleFavoritesFilter() {
        _showFavoritesOnly.value = !_showFavoritesOnly.value
    }

    /** Set the visited status filter. null = no filter, true = visited, false = not visited. */
    fun setVisitedFilter(visited: Boolean?) {
        _visitedFilter.value = visited
    }

    /** Set the radius filter in km. Pass `null` to clear. */
    fun setRadiusFilter(radiusKm: Int?) {
        _selectedRadiusKm.value = radiusKm
    }

    /** Insert a new location (Map → Add flow). */
    fun addLocation(
        title: String,
        description: String,
        latitude: Double,
        longitude: Double,
        category: String,
        imageUri: String
    ) {
        viewModelScope.launch {
            repository.insertLocation(
                AppLocation(
                    title = title,
                    description = description,
                    latitude = latitude,
                    longitude = longitude,
                    category = category,
                    imageUri = imageUri
                )
            )
        }
    }

    /** Update an existing location. */
    fun updateLocation(location: AppLocation) {
        viewModelScope.launch { repository.updateLocation(location) }
    }

    /** Delete a location by ID. */
    fun deleteLocation(id: Int) {
        viewModelScope.launch { repository.deleteLocation(id) }
    }

    /** Toggle the visited flag. */
    fun toggleVisited(id: Int) {
        viewModelScope.launch { repository.toggleVisited(id) }
    }

    /** Toggle the favorite flag. */
    fun toggleFavorite(id: Int) {
        viewModelScope.launch { repository.toggleFavorite(id) }
    }

    // ── Route Builder ──────────────────────────────────────────────

    /** Toggle a location in/out of the route waypoint list. */
    fun toggleRouteWaypoint(location: AppLocation) {
        val current = _routeWaypoints.value.toMutableList()
        if (current.any { it.id == location.id }) {
            _routeWaypoints.value = current.filter { it.id != location.id }
        } else {
            _routeWaypoints.value = current + location
        }
        // Clear stale polyline — user must tap "Optimize Route" to re-request
        _routePolylinePoints.value = emptyList()
    }

    fun clearRouteWaypoints() {
        _routeWaypoints.value = emptyList()
        _routePolylinePoints.value = emptyList()
    }

    /**
     * Optimize the route order via the **Google Directions API** with
     * `optimizeWaypoints=true`.
     *
     * - First waypoint → `origin`
     * - Last waypoint  → `destination`
     * - Middle waypoints are passed to the API and re-ordered by Google's
     *   built-in TSP solver.
     *
     * On success the [routePolylinePoints] are updated with the decoded
     * road-aware polyline, and [routeWaypoints] reflects the optimised order.
     * On failure the error is logged and straight-line segments are drawn
     * as a fallback.
     */
    fun optimizeRoute() {
        val currentWaypoints = _routeWaypoints.value
        if (currentWaypoints.size < 3) return // Оптимізація потрібна від 3 точок

        val unvisited = currentWaypoints.toMutableList()
        val optimized = mutableListOf<AppLocation>()

        // 1. Перша точка завжди залишається Стартом (Green marker)
        val start = unvisited.removeAt(0)
        optimized.add(start)

        // 2. Останній елемент — це Фініш (Red marker), його поки не чіпаємо
        val finish = unvisited.removeAt(unvisited.size - 1)

        // 3. Сортуємо середні точки за алгоритмом Найближчого Сусіда
        var current = start
        while (unvisited.isNotEmpty()) {
            var nearestIdx = 0
            var minDist = Double.MAX_VALUE

            for (i in unvisited.indices) {
                val dist = haversineDistance(
                    current.latitude, current.longitude,
                    unvisited[i].latitude, unvisited[i].longitude
                )
                if (dist < minDist) {
                    minDist = dist
                    nearestIdx = i
                }
            }
            current = unvisited.removeAt(nearestIdx)
            optimized.add(current)
        }

        // 4. Повертаємо Фініш в кінець списку
        optimized.add(finish)

        // Оновлюємо StateFlow, що запустить перемальовування карти
        _routeWaypoints.value = optimized
        fallbackToStraightLines(optimized) // Малюємо лінію маршруту
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rEarth = 6371000.0 // Радіус Землі в метрах
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { Math.pow(it, 2.0) } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { Math.pow(it, 2.0) }
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return rEarth * c
    }
    /**
     * Fallback: draw straight-line segments between waypoints when the
     * Directions API request fails or returns no routes.
     */
    private fun fallbackToStraightLines(waypoints: List<AppLocation>) {
        _routePolylinePoints.value = waypoints.map { LatLng(it.latitude, it.longitude) }
    }

    // ── Visual Search Mock ─────────────────────────────────────────

    /**
     * Stores a mock match result. Called by [com.navisense.ui.search.VisualSearchFragment]
     * after the 2-second loading spinner completes.
     */
    fun setMockMatchResult(location: AppLocation) {
        _mockMatchLocation.value = location
    }

    fun clearMockMatchResult() {
        _mockMatchLocation.value = null
    }

    // ── Navigation Mode ─────────────────────────────────────────────

    /** Toggle between Scanner (on-demand) and Dashcam (continuous/live) modes. */
    fun toggleNavMode() {
        _navMode.value = when (_navMode.value) {
            NavMode.SCANNER -> {
                // Switch to Dashcam → state is always FRESH
                _locationState.value = LocationState.FRESH
                NavMode.DASHCAM
            }
            NavMode.DASHCAM -> {
                NavMode.SCANNER
            }
        }
    }

    // ── Visual Pin (from ViT backend) ───────────────────────────────

    /**
     * Stores the visual-locate result from the ViT backend.
     * Called by [VisualSearchFragment] after a successful API call.
     * The [MapFragment] observes this to render a special "Visual Pin".
     *
     * In [NavMode.SCANNER] the location timestamp is recorded and an
     * age-checking coroutine is launched to track freshness.
     */
    fun setVisualPinResult(location: AppLocation) {
        _visualPinLocation.value = location

        // Record timestamp and start age tracking in Scanner mode
        if (_navMode.value == NavMode.SCANNER) {
            lastLocationTimestampMs = System.currentTimeMillis()
            _locationState.value = LocationState.FRESH
            startLocationAgeChecker()
        } else {
            // Dashcam mode → always fresh
            _locationState.value = LocationState.FRESH
        }
    }

    fun clearVisualPinResult() {
        _visualPinLocation.value = null
        _locationState.value = LocationState.FRESH
    }

    // ── Sensor‑Fusion: Visual Burst Localisation ────────────────────

    /**
     * Simulates a **burst‑capture** localisation cycle by reading 5 dummy
     * images from `src/main/assets/` and firing two backend requests in
     * **parallel** (`async`/`await`):
     *
     * 1. **`POST /api/visual-locate`** — ViT‑based visual place recognition
     *    on the **first** frame (`ref1.jpg`) with scope `"Kyiv"`.
     * 2. **`POST /api/v1/vggt-odometry`** — VGGT‑1B visual odometry on
     *    **all 5 frames** as a chronological burst sequence.
     *
     * ### Sensor‑Fusion Mathematics
     *
     * | Source | Returns | Role |
     * |--------|---------|------|
     * | `visual‑locate` | `(lat, lon, confidence)` | Base anchor (frame 1) |
     * | `vggt‑odometry` | `{x, y, z}` (metres) | Relative offset of the **last** frame |
     *
     * The VGGT `camera_center_offset` is interpreted in a local ENU
     * (East‑North‑Up) coordinate system: **x → East → Δlon**,
     * **z → North → Δlat**.  The 5‑point trajectory is linearly
     * interpolated with a sinusoidal road‑aware jitter, then emitted
     * to [routePolylinePoints].
     *
     * A heading bearing (0–360°) is computed from `atan2(x, z)` and
     * attached to a final `AppLocation("Khreshchatyk Visual Fix")`
     * emitted via [mockMatchLocation].
     *
     * All errors are caught and logged — no uncaught exceptions escape.
     */
    fun executeVisualBurstLocalization() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            // Track temp files for cleanup in finally block
            val tempFiles = mutableListOf<File>()
            try {
                // ── 1. Copy assets to disk cache (streaming, not in-memory) ──
                tempFiles += BURST_ASSET_NAMES.map { name ->
                    copyAssetToCache(ctx, name)
                }
                Log.d(TAG, "All $BURST_FRAME_COUNT burst frames cached to disk")

                // ── 2. Wrap into MultipartBody.Part via File.asRequestBody() ──
                // streams directly from disk — no ByteArray in heap
                val firstFile = tempFiles.first()
                val firstFileBody = firstFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                val visualLocatePart = MultipartBody.Part.createFormData(
                    "file", "ref1.jpg", firstFileBody
                )

                val vggtParts: List<MultipartBody.Part> = tempFiles.mapIndexed { idx, file ->
                    val body = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                    MultipartBody.Part.createFormData(
                        "files", "ref${idx + 1}.jpg", body
                    )
                }

                // ── 3. Fire both requests in parallel ────────────────
                val scopeBody: RequestBody =
                    "Kyiv".toRequestBody("text/plain".toMediaTypeOrNull())

                val visualDeferred = async {
                    naviSenseApi.visualLocate(visualLocatePart, scopeBody)
                }
                val vggtDeferred = async {
                    naviSenseApi.vggtOdometry(vggtParts)
                }

                val visualResponse: Response<VisualLocateResponse> = visualDeferred.await()
                val vggtResponse: Response<VggtOdometryResponse> = vggtDeferred.await()

                // ── 4. Validate responses ────────────────────────────
                if (!visualResponse.isSuccessful) {
                    val code = visualResponse.code()
                    val body = visualResponse.errorBody()?.string() ?: "—"
                    throw IOException("Visual-locate HTTP $code: $body")
                }
                if (!vggtResponse.isSuccessful) {
                    val code = vggtResponse.code()
                    val body = vggtResponse.errorBody()?.string() ?: "—"
                    throw IOException("VGGT-odometry HTTP $code: $body")
                }

                val visualLocate = visualResponse.body()!!
                val vggtOdometry = vggtResponse.body()!!

                Log.i(TAG, "Visual-locate → lat=${visualLocate.latitude}, " +
                        "lon=${visualLocate.longitude}, " +
                        "confidence=${visualLocate.confidence_score}")
                Log.i(TAG, "VGGT-odometry → status=${vggtOdometry.status}, " +
                        "offset=${vggtOdometry.camera_center_offset}")

                // ── 5. Sensor‑fusion mathematics ─────────────────────
                val baseLat = visualLocate.latitude
                val baseLon = visualLocate.longitude

                // VGGT camera_center_offset interpreted as ENU (metres):
                //   x → East  → Δlon   (positive East)
                //   y → Up    → ignored for 2-D trajectory
                //   z → North → Δlat   (positive North)
                val offsetX = vggtOdometry.camera_center_offset["x"] ?: 0.0
                val offsetZ = vggtOdometry.camera_center_offset["z"] ?: 0.0

                // Metres → degrees (WGS‑84 approximation)
                val latPerMetre = 1.0 / 111_320.0
                val lonPerMetre = 1.0 / (111_320.0 * cos(Math.toRadians(baseLat)))

                val deltaLat = offsetZ * latPerMetre   // North → latitude
                val deltaLon = offsetX * lonPerMetre   // East → longitude

                // ── 6. Reconstruct 5‑point road‑aware trajectory ─────
                val trajectory = (0 until BURST_FRAME_COUNT).map { i ->
                    val fraction = if (BURST_FRAME_COUNT > 1) {
                        i.toDouble() / (BURST_FRAME_COUNT - 1)
                    } else 0.0

                    val lat = baseLat + deltaLat * fraction
                    val lon = baseLon + deltaLon * fraction

                    // Sinusoidal jitter to simulate road curves (±15 % of total delta)
                    val jitterAmplitude = 0.15
                    val jitterLat = deltaLat * jitterAmplitude * sin(fraction * Math.PI)
                    val jitterLon = deltaLon * jitterAmplitude * cos(fraction * Math.PI * 1.3)

                    LatLng(lat + jitterLat, lon + jitterLon)
                }

                // ── 7. Compute heading bearing (0–360°) ──────────────
                // Bearing from frame 1 → frame 5 using the VGGT offset
                val bearingDeg = computeBearing(
                    baseLat, baseLon,
                    baseLat + deltaLat, baseLon + deltaLon
                )

                // ── 8. Emit results to StateFlows ────────────────────
                withContext(Dispatchers.Main) {
                    _routePolylinePoints.value = trajectory

                    // Fine-tuned coordinates: mid‑point of the trajectory
                    // (weighted toward the final frame for accuracy)
                    val fineLat = baseLat + deltaLat * 0.85
                    val fineLon = baseLon + deltaLon * 0.85

                    _mockMatchLocation.value = AppLocation(
                        id = 0,
                        title = "Khreshchatyk Visual Fix",
                        description = "Sensor‑fusion fix: ViT anchor + " +
                                "VGGT odometry. Heading ${"%.1f".format(bearingDeg)}°.",
                        latitude = fineLat,
                        longitude = fineLon,
                        category = AppLocationCategory.MONUMENT.key,
                        imageUri = "",
                        isVisited = false,
                        isFavorite = false
                    )
                }

                Log.i(TAG, "Burst localisation complete. " +
                        "Heading=${"%.1f".format(bearingDeg)}°, " +
                        "trajectory=${trajectory.size} points.")

            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "Burst localisation timed out: ${e.message}")
            } catch (e: IOException) {
                Log.e(TAG, "Burst localisation I/O error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Burst localisation unexpected error", e)
            } finally {
                // ── 9. Cleanup: delete all temp cache files ──────────
                tempFiles.forEach { file ->
                    if (file.exists()) {
                        file.delete()
                        Log.d(TAG, "Deleted temp cache: ${file.name}")
                    }
                }
            }
        }
    }

    /**
     * Copies an asset file to a temporary file in [context.cacheDir].
     * This avoids loading the entire JPEG into a [ByteArray] in the JVM
     * heap, which caused OOM when holding 5 full-resolution images
     * concurrently. The resulting [File] is used with
     * [File.asRequestBody] so OkHttp streams the bytes directly from
     * disk to the network socket.
     */
    private fun copyAssetToCache(ctx: Application, assetName: String): File {
        val tempFile = File(ctx.cacheDir, "burst_$assetName")
        // Ensure a clean slate — delete any stale file from a previous run
        if (tempFile.exists()) tempFile.delete()
        ctx.assets.open(assetName).use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "Cached $assetName → ${tempFile.absolutePath} (${tempFile.length()} bytes)")
        return tempFile
    }

    /**
     * Computes the great‑circle bearing from point A to point B.
     *
     * @return Bearing in degrees (0–360), measured clockwise from true north.
     */
    private fun computeBearing(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        val bearingRad = atan2(y, x)
        return (Math.toDegrees(bearingRad) + 360.0) % 360.0
    }

    // ── Location Age Checker (Scanner mode) ─────────────────────────

    /**
     * Launches a coroutine that periodically checks the age of the last
     * visual-pin location and updates [_locationState] accordingly.
     *
     * Thresholds:
     * - 0 … 30 seconds   → [LocationState.FRESH]
     * - 31 … 120 seconds → [LocationState.DEGRADING]
     * - > 120 seconds    → [LocationState.STALE]
     *
     * The loop exits when a new location is set (a new coroutine replaces it),
     * when the mode switches to [NavMode.DASHCAM], or when the pin is cleared.
     */
    private fun startLocationAgeChecker() {
        viewModelScope.launch {
            while (isActive) {
                delay(5_000) // Check every 5 seconds

                // Stop if mode switched to Dashcam or pin cleared
                if (_navMode.value != NavMode.SCANNER || _visualPinLocation.value == null) {
                    if (_navMode.value != NavMode.SCANNER) {
                        _locationState.value = LocationState.FRESH
                    }
                    break
                }

                val ageSeconds = (System.currentTimeMillis() - lastLocationTimestampMs) / 1000
                _locationState.value = when {
                    ageSeconds <= 30 -> LocationState.FRESH
                    ageSeconds <= 120 -> LocationState.DEGRADING
                    else -> LocationState.STALE
                }
            }
        }
    }
}
