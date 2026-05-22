# NaviSense — Single Source of Truth

> **Maintainer:** Android Team  
> **Audience:** All developers (frontend, backend, database)  
> **Last Updated:** 2026-05-22  
> **App Version:** 1.0.0

---

## Table of Contents

1. [Project Overview & Tech Stack](#1-project-overview--tech-stack)
2. [Architecture: MVVM + Repository Pattern](#2-architecture-mvvm--repository-pattern)
3. [Security Protocols](#3-security-protocols)
4. [Data Model & State Management](#4-data-model--state-management)
5. [Core Features & Screens](#5-core-features--screens)
   - [A. Map Screen (Home)](#a-map-screen-home)
   - [B. Location Details BottomSheet](#b-location-details-bottomsheet)
   - [C. Add Location (+)](#c-add-location-)
   - [D. Route Builder](#d-route-builder)
   - [E. Analytics](#e-analytics)
   - [F. Visual Search](#f-visual-search)
6. [Reactive Filtering Architecture](#6-reactive-filtering-architecture)
7. [Bilingual UI (Runtime Locale Switching)](#7-bilingual-ui-runtime-locale-switching)
8. [Room Database Layer](#8-room-database-layer)
9. [Future Integration Notes (For Database Developer — Anya)](#9-future-integration-notes-for-database-developer--anya)
10. [Backend API Reference](#10-backend-api-reference)
11. [Build & Run Instructions](#11-build--run-instructions)
12. [Known Issues & Troubleshooting](#12-known-issues--troubleshooting)
13. [Late May 2026 — Changes & Fixes](#13-late-may-2026--changes--fixes)
14. [Appendix: Complete File Inventory](#14-appendix-complete-file-inventory)

---

## 1. Project Overview & Tech Stack

NaviSense is a native Android Location Management application with visual positioning capabilities. The app allows couriers to save, organise, and navigate to points of interest, with route optimisation and analytics.

### Mobile Frontend
- **Platform:** Native Android (minimum SDK 26, target SDK 34)
- **Language:** Kotlin (no cross‑platform frameworks)
- **Architecture:** Single‑Activity with Navigation Component + BottomNavigationView (5 tabs)
- **Key Libraries:**
  - **Navigation Component 2.7.7** — fragment-based navigation with `NavHostFragment` and Bottom Navigation
  - **CameraX 1.4.1** for single‑frame image capture (fragment) and **headless capture** (Dashcam foreground service)
    - Resolution selector targeting 1080×1920 (portrait)
    - Capture mode: `MINIMIZE_LATENCY`
    - Built‑in `ImageProxy.toBitmap()` (CameraX 1.4+)
    - `ServiceLifecycleOwner` — custom `LifecycleOwner` using `LifecycleRegistry` for CameraX binding in a `Service` context (no `PreviewView`)
  - **Google Maps SDK (play-services-maps:18.2.0)** for map display
  - **Maps-Utils-KTx (5.0.0)** for enhanced map utilities
  - **Play Services Location (21.1.0)** for FusedLocationProviderClient
  - **Retrofit2 + OkHttp4** for REST communication
    - Base URL configurable via `BuildConfig.BACKEND_URL`
    - Timeout: 15 seconds connect, 30 seconds read/write
    - Multipart file upload with JPEG compression quality 85%
    - Logging interceptor set to **HEADERS** level (tuned down from `BODY` to prevent binary stream dumps from freezing the log console — see [Section 13](#13-late-may-2026--changes--fixes))
  - **Coil 2.5.0** for image loading
  - **Room 2.6.1** (with KSP) for local SQLite persistence — [`DeliveryHistory`](mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistory.kt) entity, [`DeliveryHistoryDao`](mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistoryDao.kt), [`SavedLocation`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocation.kt) entity, [`SavedLocationDao`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocationDao.kt), [`AppDatabase`](mobile/android/app/src/main/java/com/navisense/data/local/AppDatabase.kt) singleton via [`NaviSenseApplication`](mobile/android/app/src/main/java/com/navisense/NaviSenseApplication.kt)
  - **Material3** for UI components (chips, cards, bottom sheets, bottom navigation, tonal buttons)
  - OpenCV‑Android *not used* — custom Kotlin Laplacian variance used for blur detection

### Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| **Language** | Kotlin | 1.9.22 |
| **Minimum SDK** | API 26 (Android 8.0) | — |
| **Target SDK** | API 34 (Android 14) | — |
| **Architecture** | MVVM (Model-View-ViewModel) | — |
| **UI Framework** | Material Design 3 (Dark Theme focused) | 1.11.0 |
| **Navigation** | Navigation Component (fragment-based) | 2.7.7 |
| **Reactive Streams** | Kotlin Coroutines + StateFlow | 1.7.3 |
| **Dependency Injection** | Manual (ViewModel + Repository constructor injection) | — |
| **Maps** | Google Maps SDK | 18.2.0 |
| **Maps Utilities** | Maps-Utils-KTx | 5.0.0 |
| **Location** | Play Services Location | 21.1.0 |
| **Networking** | Retrofit 2 + OkHttp 4 | 2.9.0 / 4.12.0 |
| **Camera** | CameraX | 1.4.1 |
| **Image Loading** | Coil | 2.5.0 |
| **Local Database** | Room (SQLite) | 2.6.1 |
| **Code Generation** | KSP (Room compiler) | — |
| **Build System** | Gradle + AGP | 8.2.2 / 8.5 |

---

## 2. Architecture: MVVM + Repository Pattern

The app follows a strict **MVVM** pattern with a **Repository** abstraction layer.

```
┌─────────────────────────────────────────────────────────┐
│                    UI Layer (Fragments)                  │
│  MapFragment | RouteBuilderFragment | AddLocation       │
│  AnalyticsFragment | VisualSearchFragment               │
│  LocationDetailsBottomSheet                              │
└──────────────────────────┬──────────────────────────────┘
                           │ observes StateFlow
                           ▼
┌─────────────────────────────────────────────────────────┐
│              ViewModel Layer (MainViewModel)             │
│  • Shared AndroidViewModel (scoped to Activity)         │
│  • Exposes StateFlow for all UI state                   │
│  • Combines filters reactively via combine()            │
│  • Computes analytics, route optimisation               │
│  • Sensor-fusion burst pipeline (async/await)           │
└──────────────────────────┬──────────────────────────────┘
                           │ calls suspend functions
                           ▼
┌─────────────────────────────────────────────────────────┐
│              Repository Layer (Interface)                │
│  LocationRepository (interface)                          │
│    ▲                                                    │
│    │ implements                                         │
│    │                                                    │
│  RoomLocationRepositoryImpl (Room-backed, local-first)   │
│  • Persists all location data in SQLite via Room        │
│  • Reactive StateFlow via Room invalidation tracker     │
│  • Atomic SQL `NOT` for toggle operations               │
└─────────────────────────────────────────────────────────┘
```

### Key Architectural Decisions

- **Single Activity** ([`MainActivity.kt`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt)) hosts a [`NavHostFragment`](mobile/android/app/src/main/res/layout/activity_main.xml:12) with 5 bottom-navigation destinations.
- **Shared ViewModel** ([`MainViewModel`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt)) is scoped to the Activity, meaning all fragments share the same instance and react to the same state flows.
- **Repository Pattern** — [`LocationRepository`](mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt) is an interface. The production implementation [`RoomLocationRepositoryImpl`](mobile/android/app/src/main/java/com/navisense/data/RoomLocationRepositoryImpl.kt) is backed by Room SQLite persistence. The [`MockLocationRepositoryImpl`](mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt) (in-memory with 10 Kyiv landmarks) is retained for testing but **not used at runtime**.
- **Navigation Graph** — defined in [`nav_graph.xml`](mobile/android/app/src/main/res/navigation/nav_graph.xml) with 5 fragment destinations.
- **`deliveryHistoryDao` is properly scoped** — initialised from `AppDatabase.getInstance(application).deliveryHistoryDao()` at [`MainViewModel.kt:121`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:121), flowing into both `RoomLocationRepositoryImpl` and the reactive `deliverySummary` StateFlow.
- **`StateFlow` with `repeatOnLifecycle` (deprecates LiveData `observe()`)** — All reactive state in [`MainActivity`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt:139) and [`MapFragment`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt) is collected using `lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.someFlow.collect { … } } }`. This replaces the legacy `LiveData.observe()` pattern:
  - Automatically cancels collection when the lifecycle drops below `STARTED` (e.g., Activity goes to background).
  - Prevents wasted emissions and potential crashes from UI updates on a stopped lifecycle.
  - Works naturally with `StateFlow` (which is `LiveData`-like but Kotlin-native and testable without Android dependencies).
  - The `Lifecycle.State.STARTED` boundary is preferred over `RESUMED` because it covers both the visible (onStart) and interactive (onResume) states.
- **Single `companion object` per class** — Each class defines exactly one `companion object` that holds only static constants and factory methods. For example, [`DashcamBackgroundService.Companion`](mobile/android/app/src/main/java/com/navisense/core/DashcamBackgroundService.kt:408) contains `ACTION_DASHCAM_LOCATION_UPDATE`, `EXTRA_*` keys, `NOTIFICATION_ID`, `CAPTURE_INTERVAL_MS`, `start()`, and `stop()` — all scoped in one predictable location. This avoids scattered top-level constants and keeps the class's public API surface explicit.

---

## 3. Security Protocols

### 3.1 Google Maps API Key — Secure Injection

The Maps API key is **never hardcoded** in source code. The injection chain is:

1. **Storage:** Key is stored in [`local.properties`](.gitignore:40) at the project root (file is in `.gitignore`, so it is never committed).
2. **Build-time injection:** [`build.gradle.kts`](mobile/android/app/build.gradle.kts:44) reads the key via `Properties` and injects it into `AndroidManifest.xml` via `manifestPlaceholders`.
3. **Manifest reference:** [`AndroidManifest.xml`](mobile/android/app/src/main/AndroidManifest.xml:70) uses the placeholder syntax `${MAPS_API_KEY}`.
4. **CI/CD fallback:** If `local.properties` is absent, the build uses a dummy placeholder key so CI/CD builds still succeed (maps will show a degraded view).

```kotlin
// build.gradle.kts — secure key injection
val localProperties = File(rootProject.rootDir, "local.properties")
val mapsApiKey: String = if (localProperties.exists()) {
    val props = Properties()
    localProperties.inputStream().use { stream -> props.load(stream) }
    props.getProperty("MAPS_API_KEY")?.trim()
        ?: error("MAPS_API_KEY is missing in local.properties")
} else {
    "AIzaSyDUMMYKEY_FOR_CI_CD_DO_NOT_USE_IN_PRODUCTION"
}
// ...
manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
```

### 3.2 Backend URL

Configured via `BuildConfig.BACKEND_URL` in [`build.gradle.kts`](mobile/android/app/build.gradle.kts:52). Defaults to `http://10.0.2.2:8000/` (Android emulator loopback to host machine).

### 3.3 Camera & Storage Permissions

- CAMERA permission is **not required** at install time (`required="false"` in manifest). Requested at runtime in [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:60).
- Location permissions (FINE + COARSE) requested at runtime in [`MapFragment`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:61).
- TempScans folder lives in **app-internal storage** (sandboxed, inaccessible to other apps).

### 3.4 Android 14 Foreground Service Security Rules

All rules below apply when `targetSdk = 34` (Android 14) and are enforced at the OS level.

| Rule | Requirement | Enforced In | Reference |
|------|-------------|-------------|-----------|
| **`FOREGROUND_SERVICE_CAMERA` manifest permission** | The `<uses-permission>` tag must use the string `"android.permission.FOREGROUND_SERVICE_CAMERA"` — **not** the service-type constant `FOREGROUND_SERVICE_TYPE_CAMERA` (a common copy-paste pitfall). The incorrect `_TYPE_` suffix compiles silently but the system ignores it, causing a silent denial of foreground service type. | [`AndroidManifest.xml`](mobile/android/app/src/main/AndroidManifest.xml:6) | [Android Docs: Foreground Service Types](https://developer.android.com/about/versions/14/changes/fgs-types#camera) |
| **`CAMERA` runtime permission** | On Android 14, starting a foreground service with `FOREGROUND_SERVICE_TYPE_CAMERA` requires the `CAMERA` runtime permission to be **granted before** `startForeground()` is called. Without it, the system throws [`ForegroundServiceStartNotAllowedException`](https://developer.android.com/reference/android/app/ForegroundServiceStartNotAllowedException). | [`MainActivity.requestDashcamPermissionAndStart()`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt:188) | [Android Docs: FG Service Camera Restriction](https://developer.android.com/about/versions/14/changes/restrict-fgs-start#camera) |
| **`ServiceCompat.startForeground()`** | Use `androidx.core.app.ServiceCompat.startForeground()` instead of raw `startForeground()`. The Compat version accepts a `foregroundServiceType` argument (API 30+) which is required for camera-type foreground services. | [`DashcamBackgroundService.startForegroundWithNotification()`](mobile/android/app/src/main/java/com/navisense/core/DashcamBackgroundService.kt:171) | [ServiceCompat docs](https://developer.android.com/reference/androidx/core/app/ServiceCompat#startForeground(android.app.Service,int,android.app.Notification,int)) |
| **`RECEIVER_NOT_EXPORTED` for BroadcastReceiver** | On API 34+, a `BroadcastReceiver` registered via `Context.registerReceiver()` **must** specify a flag: `RECEIVER_NOT_EXPORTED` (internal-only) or `RECEIVER_EXPORTED` (exported). Omitting the flag throws a `SecurityException`. Use `androidx.core.content.ContextCompat.registerReceiver()` with `ContextCompat.RECEIVER_NOT_EXPORTED`. | [`MainActivity.onResume()`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt:151) | [Android Docs: Context.registerReceiver](https://developer.android.com/reference/android/content/Context#registerReceiver(android.content.BroadcastReceiver,android.content.IntentFilter,int)) |

---

## 4. Data Model & State Management

### 4.1 AppLocation Data Class

[`AppLocation`](mobile/android/app/src/main/java/com/navisense/model/AppLocation.kt) is the core data model, a `@Parcelize` data class used across all screens.

```kotlin
@Parcelize
data class AppLocation(
    val id: Int = 0,
    val title: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val category: String = AppLocationCategory.MONUMENT.key,  // nullable via "No Category"
    val imageUri: String = "",
    val isVisited: Boolean = false,
    val isFavorite: Boolean = false
) : Parcelable
```

**Field semantics:**

| Field | Type | Notes |
|-------|------|-------|
| `id` | `Int` | Auto-incremented by Room (via `RoomLocationRepositoryImpl`) |
| `title` | `String` | Human-readable name (e.g., "Kyiv Pechersk Lavra") |
| `description` | `String` | Free-text description |
| `latitude` | `Double` | WGS‑84 latitude |
| `longitude` | `Double` | WGS‑84 longitude |
| `category` | `String?` | One of `AppLocationCategory.names`; nullable — users can select "No Category" |
| `imageUri` | `String` | Content URI string of an attached photo, or empty string |
| `isVisited` | `Boolean` | Toggled via BottomSheet "Mark as Visited" button; visited markers render as violet |
| `isFavorite` | `Boolean` | Toggled via BottomSheet heart icon; used for "Favorites Only" filter |

### 4.2 AppLocationCategory Enum

[`AppLocationCategory`](mobile/android/app/src/main/java/com/navisense/model/AppLocationCategory.kt) provides predefined categories with associated marker colors and chart colors.

```kotlin
enum class AppLocationCategory(val key: String) {
    MONUMENT("Monument"),       // marker: Red (0°),   chart: #E53935
    GROCERY("Grocery"),         // marker: Green (120°), chart: #43A047
    GAS_STATION("Gas Station"), // marker: Orange (30°), chart: #FB8C00
    RESTAURANT("Restaurant"),   // marker: Cyan (180°),  chart: #00ACC1
    PHARMACY("Pharmacy"),       // marker: Blue (240°),  chart: #1E88E5
    NO_CATEGORY("No Category"); // marker: Red (0°),    chart: #9E9E9E
}
```

### 4.3 Repository — Room-Backed (Local-First)

Unlike earlier Sprint versions that used an in-memory mock, the **production app now uses [`RoomLocationRepositoryImpl`](mobile/android/app/src/main/java/com/navisense/data/RoomLocationRepositoryImpl.kt)**, which persists all location data in a Room SQLite database. This means data survives app restarts — there are no hardcoded seed locations. The app relies entirely on what the user saves locally.

The `MockLocationRepositoryImpl` (10 Kyiv landmarks) is retained in the source tree but **not wired** at runtime. To switch back for testing, change the one-line repository instantiation in [`MainViewModel`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:124).

### 4.4 State Flows Exposed by MainViewModel

| StateFlow | Type | Description |
|-----------|------|-------------|
| `allLocations` | `StateFlow<List<AppLocation>>` | Unfiltered list from repository (source of truth) |
| `selectedCategory` | `StateFlow<String?>` | Active category filter (`null` = All) |
| `searchQuery` | `StateFlow<String>` | Fuzzy search across Title, Description, Category |
| `showFavoritesOnly` | `StateFlow<Boolean>` | Favorites-only toggle |
| `visitedFilter` | `StateFlow<Boolean?>` | `null`=no filter, `true`=visited only, `false`=not visited only |
| `filteredLocations` | `StateFlow<List<AppLocation>>` | Derived via `combine()` of all filters above |
| `selectedRadiusKm` | `StateFlow<Int?>` | Radius filter in km (`null` = off) |
| `routeWaypoints` | `StateFlow<List<AppLocation>>` | Selected waypoints for route builder |
| `routePolylinePoints` | `StateFlow<List<LatLng>>` | Polyline points (road-aware or straight-line fallback) |
| `mockMatchLocation` | `StateFlow<AppLocation?>` | Visual Search / Burst localisation result |
| `analyticsData` | `StateFlow<AnalyticsData>` | Computed analytics (category counts, visited, favorites, district counts) |
| `deliverySummary` | `StateFlow<DeliverySummary>` | KPI cards from `delivery_history` table (Haversine distance, GPS stability) |
| `navMode` | `StateFlow<NavMode>` | SCANNER or DASHCAM navigation mode |
| `locationState` | `StateFlow<LocationState>` | FRESH / DEGRADING / STALE (age-based freshness) |
| `visualPinLocation` | `StateFlow<AppLocation?>` | Visual pin from ViT backend result |
| `isOptimizing` | `StateFlow<Boolean>` | Route optimisation in progress flag |

---

## 5. Core Features & Screens

### A. Map Screen (Home)

**Fragment:** [`MapFragment`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt)  
**Layout:** [`fragment_map.xml`](mobile/android/app/src/main/res/layout/fragment_map.xml)

The Map screen is the primary user interface. It displays a full-screen Google Map with overlaying control elements.

#### Map Controls & UI Elements

1. **Search Bar** — `EditText` at top with search icon. Uses `TextWatcher` to call [`viewModel.setSearchQuery()`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:130) on each keystroke. Triggers fuzzy matching against Title, Description, and Category fields.

2. **Category Filter Chips** — Horizontally scrollable `ChipGroup` with single-selection. "All" chip (`tag = null`) plus one chip per category (excluding `NO_CATEGORY`). Selection calls [`viewModel.setCategoryFilter()`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:148).

3. **Visited Filter Button** — 3-state toggle cycling through:
   - Show visited only → calls `viewModel.setVisitedFilter(true)`
   - Show not visited only → calls `viewModel.setVisitedFilter(false)`
   - Clear filter → calls `viewModel.setVisitedFilter(null)`

4. **Favorites Filter Button** — Binary toggle. Calls [`viewModel.toggleFavoritesFilter()`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:201).

5. **Radius Filter Button** — Cycles through `Off → 1 km → 2 km → 5 km → 10 km`. Draws a [`Circle`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:258) overlay on the map centered on the visible region.

6. **My Location FAB** — Bottom-right FAB. Requests runtime location permissions, then calls `FusedLocationProviderClient.lastLocation` and animates camera to user position.

7. **Language Toggle Button** — Left side button. Toggles between English (`en`) and Ukrainian (`uk`) using `AppCompatDelegate.setApplicationLocales()`. See [Section 7](#7-bilingual-ui-runtime-locale-switching).

#### Marker Rendering

Markers are rendered reactively via [`renderMarkers()`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:363), which:
1. Calls `map.clear()` to remove all existing markers.
2. Re-adds the radius circle if active (since `clear()` also removes overlays).
3. For each location in `filteredLocations`, creates a marker with:
   - **Color:** `HUE_VIOLET` if `isVisited == true`; otherwise the category's assigned hue from `AppLocationCategory.markerHue()`.
   - **Tag:** `location.id` (used by `OnMarkerClickListener` to open the BottomSheet).

Tapping a marker opens [`LocationDetailsBottomSheet`](mobile/android/app/src/main/java/com/navisense/ui/details/LocationDetailsBottomSheet.kt) via `parentFragmentManager`.

---

### B. Location Details BottomSheet

**Fragment:** [`LocationDetailsBottomSheet`](mobile/android/app/src/main/java/com/navisense/ui/details/LocationDetailsBottomSheet.kt)  
**Layout:** [`bottom_sheet_location_details.xml`](mobile/android/app/src/main/res/layout/bottom_sheet_location_details.xml)

A `BottomSheetDialogFragment` displayed when a map marker is tapped. Observes `viewModel.allLocations` to reactively update UI as the user performs actions.

#### Actions

| Action | Implementation | Effect |
|--------|---------------|--------|
| **Toggle Visited** | [`viewModel.toggleVisited(id)`](mobile/android/app/src/main/java/com/navisense/ui/details/LocationDetailsBottomSheet.kt:86) | Flips `isVisited` in repository; map marker turns violet/gray reactively |
| **Toggle Favorite** | [`viewModel.toggleFavorite(id)`](mobile/android/app/src/main/java/com/navisense/ui/details/LocationDetailsBottomSheet.kt:91) | Flips `isFavorite`; heart icon switches between `ic_heart_outline` and `ic_heart_filled` |
| **Delete** | [`viewModel.deleteLocation(id)`](mobile/android/app/src/main/java/com/navisense/ui/details/LocationDetailsBottomSheet.kt:97) | Removes from repository; map clears/redraws; sheet dismisses |
| **Edit** | Opens [`AddLocationFragment`](mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt) in edit mode via `newInstance()` with pre-filled fields | User can modify title, description, category, photo; save updates existing location |

#### UI Components

- **Large Photo** — Loaded via Coil from `AppLocation.imageUri`. Falls back to a placeholder card if no image (`imageUri` is blank).
- **Title** — `tvTitle` (plain text).
- **Coordinates** — Displayed as `"latitude, longitude"`.
- **Category** — Plain text label.
- **Description** — Full text description.
- **Visited Button** — Text changes between "Mark as Visited" / "Visited Already" based on state.
- **Heart Icon** — Toggles between filled/outline drawables.
- **Delete Button** — Removes location and dismisses sheet.

---

### C. Add Location (+)

**Fragment:** [`AddLocationFragment`](mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt)  
**Layout:** [`fragment_add_location.xml`](mobile/android/app/src/main/res/layout/fragment_add_location.xml)

Supports two modes:

#### Add Mode (Default)

1. **Map Picker** — Interactive `SupportMapFragment` where the user taps to select coordinates. A marker drops at the tapped position; coordinates are displayed below the map.
2. **Title Input** — Required field (validation: empty → error). 
3. **Description Input** — Optional free-text.
4. **Category Dropdown** — `AutoCompleteTextView` populated with `AppLocationCategory.names`. Default selection is "Monument". Includes "No Category" option.
5. **Photo Attachment** — Button opens system gallery picker via `GetContent("image/*")`. Photo preview shown in `ImageView`.
6. **Save** — Calls [`viewModel.addLocation()`](mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt:240) with all fields, then pops back stack.

#### Edit Mode

Activated via [`newInstance(locationId, title, description, latitude, longitude, category, imageUri, isVisited, isFavorite)`](mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt:282). All fields are pre-filled. The map picker is **disabled** (coordinates cannot be changed). Save calls [`viewModel.updateLocation()`](mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt:224), preserving `isVisited` and `isFavorite` flags from the original.

---

### D. Route Builder

**Fragment:** [`RouteBuilderFragment`](mobile/android/app/src/main/java/com/navisense/ui/route/RouteBuilderFragment.kt)  
**Layout:** [`fragment_route_builder.xml`](mobile/android/app/src/main/res/layout/fragment_route_builder.xml)

Split-screen UI: Google Map (top 50%) + scrollable waypoint list (bottom 50%).

#### "Pac-Man" / Shortest Path Algorithm

The route optimisation logic is implemented in [`MainViewModel.optimizeRoute()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:443):

```
Constraints:
  • First selected waypoint MUST remain the Start (green marker)
  • Last selected waypoint MUST remain the Finish (red marker)
  • Middle waypoints are algorithmically reordered for shortest total path

Algorithm (Nearest-Neighbor TSP heuristic):
  1. Fix the first waypoint as the current position
  2. From the remaining middle waypoints, select the one with the
     shortest Haversine distance from the current position
  3. Move to that waypoint and repeat until all middle waypoints are placed
  4. Append the final (end) waypoint
```

**Important:** The TSP optimiser is **completely decoupled from any active database insertions**. It operates purely on the in-memory `_routeWaypoints` StateFlow. On error or when the Google Directions API is unavailable, it safely falls back to [`fallbackToStraightLines()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:499), drawing straight polylines via `LatLng` coordinates without touching the database.

The Haversine formula used for distance calculation:

```kotlin
private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0 // Earth radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}
```

#### Route Polyline

[`MainViewModel.fallbackToStraightLines()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:499) generates direct `LatLng` points between consecutive waypoints. The polyline is drawn in blue (`#1565C0`) at 6px width.

#### Waypoint List

- Uses a `RecyclerView` with a custom `WaypointAdapter`.
- Tapping a location toggles it in/out of the waypoint set.
- Selected waypoints are highlighted (activated state).
- **Clear Route** button empties the waypoint list.
- **Optimize Route** button triggers `viewModel.optimizeRoute()` (requires ≥3 waypoints).
- **Start Navigation** button launches external Google Maps app via implicit intent:

```kotlin
val gmmIntentUri = Uri.parse("google.navigation:q=${destination.latitude},${destination.longitude}")
val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
mapIntent.setPackage("com.google.android.apps.maps")
```

If Google Maps is not installed, falls back to a web URL with all waypoints.

---

### E. Analytics

**Fragment:** [`AnalyticsFragment`](mobile/android/app/src/main/java/com/navisense/ui/analytics/AnalyticsFragment.kt)  
**Layout:** [`fragment_analytics.xml`](mobile/android/app/src/main/res/layout/fragment_analytics.xml)

All charts are **custom Canvas-drawn Views** (no third-party charting library).

#### Charts

| Chart | View Class | Data | Visual |
|-------|-----------|------|--------|
| **Pie Chart** | [`PieChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/PieChartView.kt) | Category distribution | Coloured slices with percentage labels and legend |
| **Vertical Bar Chart** | [`BarChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/BarChartView.kt) | Visited / Not Visited / Favorites / Others | 4 bars with value labels and X-axis labels |
| **Horizontal Bar Chart** | [`DistrictBarChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictBarChartView.kt) | Locations per Kyiv district | Horizontal bars with district name labels and count values |

#### Analytics Data Model

```kotlin
data class AnalyticsData(
    val categoryCounts: Map<String, Int>,    // e.g., "Monument" → 4
    val visitedCount: Int,
    val notVisitedCount: Int,
    val favoriteCount: Int,
    val notFavoriteCount: Int,
    val districtCounts: Map<String, Int>,     // e.g., "Pecherskyi" → 3
    val totalCount: Int
)
```

#### District Detection

[`MainViewModel.detectDistrict()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:319) maps coordinates to one of 8 Kyiv districts using simple bounding-box logic:

| District | Bounding Box |
|----------|-------------|
| Shevchenkivskyi | `lat 50.440..50.470, lng 30.490..30.520` |
| Pecherskyi | `lat 50.420..50.450, lng 30.530..30.560` |
| Podilskyi | `lat 50.460..50.520, lng 30.490..30.520` |
| Obolonskyi | `lat > 50.490, lng < 30.530` |
| Darnyrskyi | `lng > 30.560` |
| Solomyanskyi | `lat < 50.430, lng < 30.530` |
| Holosiivskyi | `lat < 50.420` |
| Desnyanskyi | (fallback) |

**Note:** This is a mock implementation for MVP. A production system would use a proper geocoding API or pre-computed district polygons.

#### Delivery Summary KPI Cards

The analytics screen also displays KPI cards derived from the Room `DeliveryHistory` database, computed reactively via [`MainViewModel.deliverySummary`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:274):

| KPI | Source | Formula |
|-----|--------|---------|
| **Total Distance** | `DeliveryHistory` | Sum of Haversine distances between all start/end point pairs |
| **Time Saved** | `DeliveryHistory.timeSavedSeconds` | Summed, converted to minutes |
| **GPS Stability** | `DeliveryHistory.gpsDropsCount` | `100 - (totalDrops / totalDeliveries) * 20`, clamped to 0–100 |

---

### F. Visual Search

**Fragment:** [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt)  
**Layout:** [`fragment_visual_search.xml`](mobile/android/app/src/main/res/layout/fragment_visual_search.xml)

#### CameraX Integration (Wired)

The CameraX pipeline is **fully wired** in this fragment:

1. **Permission Check** — On fragment creation, checks for CAMERA + LOCATION permissions. If either is missing, shows a "Permissions Required" dialog with an "Open Settings" button.
2. **Camera Initialization** — Instantiates [`ScannerCamera`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt) with a live `PreviewView`, `FileManagerService`, and the fragment's lifecycle owner.
3. **Live Preview** — Camera preview appears immediately after initialization.
4. **Capture Flow** — User taps the capture FAB → `ScannerCamera.captureSharpImage()`:
   - CameraX captures a single frame at 1080×1920 resolution with `CAPTURE_MODE_MINIMIZE_LATENCY`.
   - Laplacian variance blur detection (threshold 100.0) — rejects blurry images.
   - Sharp images are saved to `TempScans/` via [`FileManagerService.saveImage()`](mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt:46).
   - On success: toast confirmation → starts location confirmation flow.
   - On failure (blurry, storage, camera error): descriptive toast, user can retry.

#### Gallery Upload

- "Upload Photo" button opens system gallery picker via `GetContent("image/*")`.
- Selected image triggers the same location confirmation + search flow.

#### Location Confirmation Flow

After capture or gallery selection, the fragment enters a location confirmation state machine (driven by [`VisualSearchViewModel`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchViewModel.kt)):

1. Fetch device GPS location (or detect unavailability).
2. Reverse-geocode to district/city/country.
3. Show sequential `AlertDialog` chain: "Are you in [District]?" → "Are you in [City]?" → "Are you in [Country]?"
4. Once scope is confirmed (or all denied), proceed with ViT-based visual search via `proceedWithSearch()`.

#### Visual Search API Flow

After scope confirmation:
1. Loading overlay shown (spinner, buttons disabled).
2. [`visualSearchViewModel.performVisualLocate(file, scope)`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:704) fires the real ViT backend call.
3. On success: `VisualLocateResponse` coordinates are stored as a visual pin in `MainViewModel`, loading dismissed, fragment navigates to Map tab.
4. On error: user-friendly Toast is shown, loading dismissed, user can retry.
5. TempScans folder is cleaned up via [`FileManagerService.clearTempScansFolder()`](mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt:110).

#### "Test Khreshchatyk Burst" Simulation Button

A dedicated **"Test Khreshchatyk Burst"** tonal `MaterialButton` ([`btn_test_burst`](mobile/android/app/src/main/res/layout/fragment_visual_search.xml:31)) is positioned above the capture FAB for isolated integration testing of the sensor-fusion pipeline **without mutating the legacy CameraX capture logic**.

| Aspect | Detail |
|---|---|
| **Initialisation** | [`initTestBurstButton()`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:746) wires the click listener |
| **Action** | Shows loading overlay, fires [`viewModel.executeVisualBurstLocalization()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:593) |
| **Asset source** | Reads 5 pre-loaded JPEG assets (`ref1.jpg` … `ref5.jpg`) from [`src/main/assets/`](mobile/android/app/src/main/assets/) |
| **Result observation** | [`observeBurstLocalizationResult()`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:770) — collects `mockMatchLocation` StateFlow; on non-null emission dismisses loader, shows success Toast, navigates to Map |
| **Original CameraX flow** | Completely untouched — the burst button is an independent test path |

#### Live Burst Capture — Production Mode

The capture FAB has been evolved from single‑frame capture to **5‑frame burst capture** when [`NavMode.SCANNER`](mobile/android/app/src/main/java/com/navisense/model/NavMode.kt) is active. This is the production counterpart to the simulation-mode burst test button.

| Aspect | Detail |
|--------|--------|
| **Pipeline** | [`ScannerCamera.captureBurst()`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt:266) → 5 sequential `ImageCapture` callbacks → blur detection per frame → JPEG to `TempScans/` via `FileManagerService` → all 5 files passed to [`MainViewModel.liveBurstCapture()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:772) |
| **Burst constants** | `BURST_FRAME_COUNT = 5`, `BURST_INTER_MS = 150`, `MAX_BLUR_RETRIES_PER_FRAME = 2` |
| **Thread safety** | `AtomicInteger` gate in `captureBurst()` prevents concurrent burst invocations; `captureNext()` recursive callback chain |
| **Sensor fusion** | Frame 1 → `POST /api/visual-locate` (with dynamic location scope). All 5 frames → `POST /api/v1/vggt-odometry` (multipart list). ENU offset → WGS-84 delta applied. Result emitted via `mockMatchLocation` StateFlow → MapFragment navigation with trajectory polyline |
| **Trigger** | Single tap on capture FAB (same button, evolved behaviour) |
| **UI feedback** | Progress toast "Burst frame X/5" during capture; "Locating…" toast during API calls |

#### Video File Upload Simulation

If the user selects an **MP4 video** from the gallery picker (MIME‑type detection), the fragment falls into a video‑extraction path instead of the standard image path.

| Step | Implementation |
|------|---------------|
| **MIME detection** | [`handleVideoPicked()`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:878) checks `contentResolver.getType(uri)` for `video/mp4` |
| **Frame extraction** | [`extractVideoFrames()`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:933) — uses `MediaMetadataRetriever` at 5 evenly‑spaced timestamps, writes JPEGs to `TempScans/` |
| **Pipeline injection** | Extracted frames fed into the same `liveBurstCapture()` sensor-fusion pipeline |
| **Key constants** | `VIDEO_FRAME_COUNT = 5`, `OPTION_CLOSEST_SYNC` (primary), `OPTION_CLOSEST` (fallback) |
| **Cleanup** | Temp frames deleted in `clearTempScansAfterSearch()` via `FileManagerService.clearTempScansFolder()` |

---

## 6. Reactive Filtering Architecture

The filtering system uses Kotlin Flow's [`combine()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:151) operator to derive `filteredLocations` from multiple filter states:

```kotlin
val filteredLocations: StateFlow<List<AppLocation>> =
    combine(
        allLocations,         // source of truth from repository
        _selectedCategory,    // category chip selection
        _searchQuery,         // search bar text
        _showFavoritesOnly,   // favorites toggle
        _visitedFilter        // visited 3-state filter
    ) { locations, category, query, favoritesOnly, visitedFilter ->
        var result = locations

        // 1. Category filter (case-insensitive)
        if (category != null) {
            result = result.filter { it.category.equals(category, ignoreCase = true) }
        }

        // 2. Fuzzy search (title, description, OR category)
        if (query.isNotBlank()) {
            val q = query.lowercase().trim()
            result = result.filter { loc ->
                loc.title.lowercase().contains(q) ||
                loc.description.lowercase().contains(q) ||
                loc.category.lowercase().contains(q)
            }
        }

        // 3. Favorites only
        if (favoritesOnly) {
            result = result.filter { it.isFavorite }
        }

        // 4. Visited status
        if (visitedFilter != null) {
            result = result.filter { it.isVisited == visitedFilter }
        }

        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

**Automatic map refresh:** [`MapFragment`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:343) collects `filteredLocations` and calls `renderMarkers()` on each emission, which calls `map.clear()` and re-adds all markers. This ensures the map always reflects the current filter state and any `isVisited`/`isFavorite` changes.

---

## 7. Bilingual UI (Runtime Locale Switching)

Language switching is implemented via `AppCompatDelegate.setApplicationLocales()` (API 33+ with backward compatibility via `LocaleListCompat`).

### Trigger

A button on the Map screen ([`btn_language_toggle`](mobile/android/app/src/main/res/layout/fragment_map.xml:172)) toggles between `"en"` and `"uk"`:

```kotlin
// MapFragment.kt — language toggle
binding.btnLanguageToggle.setOnClickListener {
    val currentLocale = resources.configuration.locales[0]
    val isEnglish = currentLocale.language == "en"
    val langTag = if (isEnglish) "uk" else "en"
    AppCompatDelegate.setApplicationLocales(
        LocaleListCompat.forLanguageTags(langTag)
    )
}
```

### Helper Methods

[`MainActivity`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt) provides static helper methods:

```kotlin
companion object {
    @JvmStatic
    fun switchLocale(languageCode: String) {
        val localeList = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    @JvmStatic
    fun getCurrentLocaleCode(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) {
            Locale.getDefault().language
        } else {
            locales[0]?.language ?: "en"
        }
    }
}
```

### String Resources

- **English:** [`values/strings.xml`](mobile/android/app/src/main/res/values/strings.xml)
- **Ukrainian:** [`values-uk/strings.xml`](mobile/android/app/src/main/res/values-uk/strings.xml)

### Locales Configuration

The [`locales_config.xml`](mobile/android/app/src/main/res/xml/locales_config.xml) file (referenced in `AndroidManifest.xml` via `android:localeConfig`) declares supported locales for Per-App Language Preferences.

---

## 8. Room Database Layer

**Status:** ✅ Implemented and **wired** (Room dependencies in `build.gradle.kts`, entities, DAOs, `AppDatabase` exist on disk, and `RoomLocationRepositoryImpl` is the active repository).

### Dependencies

```kotlin
// build.gradle.kts
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")
```

### Schema

The database ([`AppDatabase`](mobile/android/app/src/main/java/com/navisense/data/local/AppDatabase.kt)) currently has **two tables**:

#### 1. `saved_locations` (Entity: [`SavedLocation`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocation.kt))

```kotlin
@Entity(tableName = "saved_locations")
data class SavedLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val imageUri: String = "",
    val isVisited: Boolean = false,
    val isFavorite: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
```

[`SavedLocationDao`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocationDao.kt) provides:
- `suspend fun insert(location: SavedLocation): Long`
- `suspend fun update(location: SavedLocation)`
- `suspend fun delete(location: SavedLocation)`
- `suspend fun deleteById(id: Long)`
- `fun getAll(): Flow<List<SavedLocation>>` — reactive read
- `suspend fun getById(id: Long): SavedLocation?`
- `suspend fun toggleVisited(id: Long)` — atomic SQL `NOT`
- `suspend fun toggleFavorite(id: Long)` — atomic SQL `NOT`

#### 2. `delivery_history` (Entity: [`DeliveryHistory`](mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistory.kt))

```kotlin
@Entity(tableName = "delivery_history")
data class DeliveryHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val startPointLat: Double,
    val startPointLng: Double,
    val endPointLat: Double,
    val endPointLng: Double,
    val gpsDropsCount: Int,
    val timeSavedSeconds: Long,
    val timestamp: Long = System.currentTimeMillis()
)
```

[`DeliveryHistoryDao`](mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistoryDao.kt) provides:
- `suspend fun insert(delivery: DeliveryHistory): Long`
- `fun getAllDeliveries(): Flow<List<DeliveryHistory>>`
- `suspend fun getDeliveryById(id: Long): DeliveryHistory?`
- `suspend fun deleteAll()`
- `fun getLatestDelivery(): Flow<DeliveryHistory?>`

### AppDatabase Singleton

[`AppDatabase.getInstance(context)`](mobile/android/app/src/main/java/com/navisense/data/local/AppDatabase.kt:52) uses double-checked locking to provide a singleton instance. Currently uses `fallbackToDestructiveMigration()` for schema changes (MVP convenience).

### NaviSenseApplication

[`NaviSenseApplication`](mobile/android/app/src/main/java/com/navisense/NaviSenseApplication.kt) is declared in `AndroidManifest.xml` and lazily initialises the `AppDatabase` singleton:

```kotlin
class NaviSenseApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
}
```

### Current Wired State

Unlike earlier Sprint versions, the **Room database is now fully wired** into the production data layer:

1. **`RoomLocationRepositoryImpl`** is the active repository implementation in `MainViewModel` — all location CRUD operations go through Room.
2. **`deliveryHistoryDao`** is properly scoped — initialised at [`MainViewModel.kt:121`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:121) from `AppDatabase.getInstance(application)` and used by both `RoomLocationRepositoryImpl` and the `deliverySummary` StateFlow.
3. The `MockLocationRepositoryImpl` (10 Kyiv landmarks) is retained in source for testing but is **not the default** runtime implementation.

---

## 9. Future Integration Notes (For Database Developer — Anya)

### 9.1 Wire SavedLocation and DeliveryHistory Inserts

These tables are built and ready. Integration points:

- **SavedLocation:** The entity is already used via `RoomLocationRepositoryImpl`. No further integration needed for CRUD operations. For new features like "Saved Places" screen, wire `SavedLocationDao.getAll()` into a new section of `AnalyticsData` or a dedicated UI.
- **DeliveryHistory:** Wire `DeliveryHistoryDao.insert()` into the Route Builder's "Start Navigation" flow to log completed trips. Wire `getLatestDelivery()` into Analytics for a "Last Trip Summary" card. Currently the table is populated only via the DAO's `getAllDeliveries()` Flow for KPI cards, but **insertion logic is not yet triggered** from any fragment.

### 9.2 Wire Backend ML Pipeline

The complete pipeline exists but the mobile-to-backend integration for the primary CameraX flow uses the real API call (ViT visual-locate) while the burst sensor-fusion pipeline (`executeVisualBurstLocalization()`) is fully integrated:

- **CameraX flow:** Already uses `VisualSearchViewModel.performVisualLocate()` which calls `LocalizationApiClient.visualLocate()` with the captured file.
- **Burst test flow:** [`executeVisualBurstLocalization()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:593) calls both `POST /api/visual-locate` and `POST /api/v1/vggt-odometry` in parallel via `async`/`await`.
- **Configuration:** Update `BuildConfig.BACKEND_URL` to point to the deployed backend.

---

## 10. Backend API Reference

### Python FastAPI Backend

**Location:** [`backend/`](backend/)  
**Docker:** [`backend/Dockerfile`](backend/Dockerfile)  

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Root welcome message |
| `GET` | `/api/v1/health` | Health check → `{"status": "ok", "mode": "mock"\|"production"}` |
| `POST` | `/api/v1/position` | Upload JPEG (max 5 MB) → returns `{latitude, longitude, floor, confidence, nearest_landmarks}` |
| `POST` | `/api/visual-locate` | Upload JPEG + `location_scope` form field → returns `{latitude, longitude, confidence_score}` |
| `POST` | `/api/v1/vggt-odometry` | Upload list of JPEGs (≥2) → returns `{status, camera_center_offset: {x, y, z}}` |
| `POST` | `/api/v1/calibrate` | Placeholder for blur-detection calibration |

### ML Pipeline

- **Feature Extraction:** [`feature_extractor.py`](backend/app/feature_extractor.py) — DINOv2-base (ViT-B/14, 768-dim) or ViT-B/16 (768-dim), L2 normalized, CUDA + FP16 optimised.
- **Vector Search:** [`vector_db.py`](backend/app/vector_db.py) — FAISS `IndexFlatL2`, GPU-capable with FP16 compression. Real data only — no demo index generated by default.
- **Mock Fallback:** If torch/transformers/faiss are unavailable, auto-falls back to mock implementations returning random positions. Controlled by the [`USE_MOCK`](backend/app/main.py:21) flag.
- **VGGT-1B Odometry:** [`vggt_processor.py`](backend/app/vggt_processor.py) — wraps `facebook/VGGT-1B` with `@torch.no_grad()` + AMP autocast for 3D camera centre estimation.

### Backend Execution Modes

| Mode | `USE_MOCK` value | Behaviour |
|---|---|---|
| **Production** | `False` (set manually) | Real PyTorch models: DINOv2/ViT extractors, FAISS index, VGGT-1B processor |
| **Mock** | `True` (default / fallback) | `MockExtractor` (random 768-D vectors) + `MockVectorDB` (1000 synthetic landmarks) |

The `/api/v1/health` endpoint reports the active mode.

---

## 11. Build & Run Instructions

```bash
# ── Android App ──────────────────────────────────────────────────────

# 1. Configure Google Maps API key in local.properties at project root:
#    MAPS_API_KEY=AIzaSy...your_real_key...
# 1. Configure your Google Maps API key
#    - Add to ROOT local.properties: MAPS_API_KEY=AIzaSy...your_real_key...
#    - Ensure "Maps SDK for Android" is ENABLED in Google Cloud Console

# 2. Clean build
cd mobile/android
./gradlew clean assembleDebug

# 3. Install on device/emulator (API 26+)
./gradlew installDebug

# ── Backend (Python) ─────────────────────────────────────────────────

cd backend
python -m venv venv
# Windows: venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000

# ── Backend (Docker) ─────────────────────────────────────────────────

cd backend
docker build -t navisense-backend .
docker run -p 8000:8000 navisense-backend
```

---

## 12. Known Issues & Troubleshooting

### Known Issues & Gaps

| Issue | Impact | Status / Notes |
|---|---|---|
| **ML Backend not deployed / reachable** | ❌ `LocalizationApiClient` will fail to connect. | Backend code exists and is runnable (Docker or `uvicorn`), but no cloud host is configured. `BuildConfig.BACKEND_URL` defaults to `http://10.0.2.2:8000/` (emulator localhost). |
| **LocalizationApiClient not wired for CameraX** | ❌ Previously unwired; now partially wired. | The **VisualSearchFragment** now calls the real ViT backend via `performVisualLocate()`. The burst test button (`executeVisualBurstLocalization()`) calls both endpoints. However, some fallback paths may still use mock data. |
| **User geolocation blue dot — intermittent** | ⚠️ The My-Location blue dot may not appear on first launch. | [`MapFragment.enableMyLocation()`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:317) uses `FusedLocationProviderClient.lastLocation` which returns `null` if no prior location is cached. Workaround planned: use `getCurrentLocation()` with `PRIORITY_HIGH_ACCURACY`. |
| **MarkerItem.kt — potential dead code** | ⚠️ Low impact | The old [`MarkerItem`](mobile/android/app/src/main/java/com/navisense/model/MarkerItem.kt) model (transport-mode tags: Walking, Bicycle, Car) from Sprint 1 may be unused now. Audit and clean up. |
| **Filter transport chips (Walking/Bicycle/Car) still in strings.xml** | ⚠️ Cosmetic | Strings `filter_walking`, `filter_bicycle`, `filter_car` exist in [`strings.xml`](mobile/android/app/src/main/res/values/strings.xml:18) but are no longer used by any UI component. Clean up. |
| **16 KB page‑size alignment (Android 15)** | ✅ Fixed (verified) | CameraX 1.4.1 + `packaging { jniLibs { useLegacyPackaging = true } }` added in `build.gradle.kts`. |
| **Analytics `combine` with `Unit`** | ⚠️ Minor | `analyticsData` combines `allLocations` with `MutableStateFlow(Unit)`. Works but unconventional. |
| **DeliveryHistory insert not wired** | ⚠️ Table is read-only at runtime | The `DeliveryHistory` table is defined and queried for KPI cards, but no fragment triggers `DeliveryHistoryDao.insert()`. |
| **Reference image database insufficient** | ⚠️ Only 5 placeholder images | Production-grade collection of geo-tagged reference images with `metadata.json` is required for meaningful VPR. |
| **`BroadcastReceiver` without export flag (API 34+)** | ❌ `SecurityException` on `registerReceiver()` | `ContextCompat.registerReceiver()` must specify `RECEIVER_NOT_EXPORTED` (internal) or `RECEIVER_EXPORTED`. ✅ Fixed: [`MainActivity.onResume()`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt:154) uses `ContextCompat.RECEIVER_NOT_EXPORTED` for `dashcamLocationReceiver`. |
| **`FOREGROUND_SERVICE_CAMERA` vs `FOREGROUND_SERVICE_TYPE_CAMERA` confusion** | ❌ Silent manifest denial on Android 14 | The manifest `<uses-permission>` tag must use `FOREGROUND_SERVICE_CAMERA` (without `_TYPE_`). The `_TYPE_` suffix is for the `foregroundServiceType` attribute and `ServiceInfo` constant — they are **not interchangeable**. ✅ Verified: [`AndroidManifest.xml`](mobile/android/app/src/main/AndroidManifest.xml:6) uses the correct permission string. |
| **LiveData `.observe()` usage in older code** | ⚠️ Lifecycle safety gap | Some legacy observers use `LiveData.observe()` which does not automatically stop emissions when the lifecycle drops below `STARTED`. ✅ Mitigated: All new code uses `StateFlow` + `repeatOnLifecycle(Lifecycle.State.STARTED)` as documented in [Section 2](#2-architecture-mvvm--repository-pattern). |

### Critical Warning — VGGT-1B on Windows CPU

Loading the 1-billion parameter `facebook/VGGT-1B` model on a **Windows CPU environment** may trigger a fatal **`OSError (os error 1455: The paging file is too small)`** due to PyTorch multiprocessing memory-mapping constraints. This occurs because:

- PyTorch's model loader uses `mmap` for large checkpoint files (~4 GB+).
- Windows default page file size is often insufficient.
- The error manifests during `torch.load()` or `VGGTProcessor.__init__()`.

**Official production workarounds:**

| Workaround | Steps |
|---|---|
| **1. `USE_MOCK = True` (simplest / default)** | Keep [`USE_MOCK = True`](backend/app/main.py:21) in `main.py` (it is the default). The `/api/v1/vggt-odometry` endpoint returns a **simulated JSON response** with random `{x, y, z}` spatial offsets — no model loading, no inference, no PyTorch overhead. The other endpoints (`/api/v1/position`, `/api/visual-locate`) also use mock extractors and vector DB. Suitable for integration testing and development. |
| **2. Docker container (recommended for production)** | `cd backend && docker build -t navisense-backend . && docker run -p 8000:8000 navisense-backend` — the Linux-based container avoids Windows memory-mapping issues entirely. |
| **3. Windows Paging File custom size** | Navigate to **System Properties → Advanced → Performance → Virtual Memory** and set: **Initial size: 32768 MB (32 GB)**, **Maximum size: 49152 MB (48 GB)**. Requires a reboot. |

---

## 13. Late May 2026 — Changes & Fixes

This section documents all changes implemented in late May 2026, reflecting the latest code modifications, bug fixes, and operational flow improvements.

### 13.1 "Test Khreshchatyk Burst" Simulation Mode

A dedicated **"Test Khreshchatyk Burst"** action button was added to [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt) for isolated integration testing of the sensor-fusion pipeline without mutating the legacy CameraX capture logic.

- **UI:** [`btn_test_burst`](mobile/android/app/src/main/res/layout/fragment_visual_search.xml:31) — tonal `MaterialButton` with amber accent stroke, positioned 104 dp above the capture FAB in the camera container.
- **String:** `test_burst_button` → `"Test Khreshchatyk Burst"` (English), with Ukrainian translation in `values-uk/strings.xml`.
- **Initialisation:** [`initTestBurstButton()`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:746) wires the click listener to fire [`executeVisualBurstLocalization()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:593).
- **Assets:** Reads 5 pre-loaded JPEG assets (`ref1.jpg` … `ref5.jpg`) from [`src/main/assets/`](mobile/android/app/src/main/assets/).
- **Result:** [`observeBurstLocalizationResult()`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:770) collects `mockMatchLocation` StateFlow; on non-null emission, dismisses loading overlay, shows `toast_burst_success` Toast, and navigates to Map tab.
- The original CameraX capture logic remains completely untouched — the burst button is a fully independent test path.

### 13.2 Memory Optimisation & OOM Crash Fixes

**Problem:** Silent `OutOfMemoryError` crash during burst uploads caused by loading all 5 full-resolution JPEGs into JVM heap `ByteArray` instances concurrently.

**Fix — Disk-cache streaming pipeline in [`MainViewModel`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt):**

1. **`copyAssetToCache()`** (line 750) — opens each asset as an `InputStream` via `context.assets.open()`, copies bytes directly to a temporary file in `context.cacheDir` (named `burst_ref{N}.jpg`). No `ByteArray` is allocated for the image payload in the JVM heap.
2. **`asRequestBody()`** (line 607) — OkHttp's `RequestBody.Companion.asRequestBody()` is called on the cached `File`, enabling OkHttp to stream bytes directly from disk to the network socket without intermediate heap buffering.
3. **`MultipartBody.Part`** instances wrap these file-backed request bodies for both the `visualLocate` and `vggtOdometry` API calls.

**Logging interceptor tuning:**
```kotlin
// MainViewModel.kt:89 — HttpLoggingInterceptor
level = HttpLoggingInterceptor.Level.HEADERS
```
The level was reduced from `BODY` to `HEADERS` to prevent binary stream character dumps from freezing the log console and to avoid `OutOfMemoryError` during debug logging of large multipart requests.

### 13.3 Fail-Safe Network Handling & Robust Sensor Fusion

The [`executeVisualBurstLocalization()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:593) method is wrapped in a crash-proof try-catch-finally block:

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    val tempFiles = mutableListOf<File>()
    try {
        // 1. Copy assets to disk cache
        // 2. Build MultipartBody.Part from cached files
        // 3. Fire both requests in parallel via async/await
        // 4. Validate HTTP responses
        // 5. Sensor-fusion mathematics
        // 6. Emit results on Dispatchers.Main
    } catch (e: SocketTimeoutException) {
        Log.e(TAG, "Burst localisation timed out: ${e.message}")
    } catch (e: IOException) {
        Log.e(TAG, "Burst localisation I/O error: ${e.message}")
    } catch (e: Exception) {
        Log.e(TAG, "Burst localisation unexpected error", e)
    } finally {
        // Guaranteed cleanup: delete all temp cache files
        tempFiles.forEach { file ->
            if (file.exists()) { file.delete() }
        }
    }
}
```

**Error handling guarantees:**
- Network errors or `SocketTimeoutException` (e.g., ports/firewall blocks) are **caught and logged** — the Android Runtime is never crashed.
- Loading overlay is dismissed via `withContext(Dispatchers.Main)` within the success branch.
- A user-friendly Toast is shown instead of an unhandled crash.
- Temporary cached files in `context.cacheDir` are **guaranteed cleaned up** in the `finally` block, ensuring **zero local storage leaks** across all code paths (success, timeout, I/O error, unexpected exception).

**Sensor-Fusion Mathematics:**

| Step | Operation | Detail |
|---|---|---|
| **ViT anchor** | `visualLocate` response | Base `(latitude, longitude)` from `POST /api/visual-locate` on frame 1 |
| **VGGT offset (ENU)** | `vggtOdometry` response | `camera_center_offset`: `x` → East (Δlon), `z` → North (Δlat), `y` → Up (ignored for 2-D) |
| **Metres → degrees** | WGS-84 approximation | `latPerMetre = 1.0 / 111_320.0`, `lonPerMetre = 1.0 / (111_320.0 * cos(radians(baseLat)))` |
| **Delta computation** | `deltaLat = offsetZ * latPerMetre`, `deltaLon = offsetX * lonPerMetre` | Converts metric offset to geographic drift |
| **Trajectory** | 5-point path with sinusoidal jitter | ±15 % amplitude road-aware curve simulation between frame 1 and frame 5 |
| **Final coordinate** | Mid-point weighted toward final frame | `fineLat = baseLat + deltaLat * 0.85`, `fineLon = baseLon + deltaLon * 0.85` |
| **Heading bearing** | [`computeBearing()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:768) | Great-circle bearing (0–360°) from anchor to final position via `atan2(y, x)` formula |
| **Result** | Emitted via `mockMatchLocation` StateFlow | `AppLocation("Khreshchatyk Visual Fix")` with heading in description |

The `computeBearing()` function:
```kotlin
val dLon = Math.toRadians(lon2 - lon1)
val y = sin(dLon) * cos(Math.toRadians(lat2))
val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
        sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
val bearingRad = atan2(y, x)
return (Math.toDegrees(bearingRad) + 360.0) % 360.0
```

### 13.4 Backend Execution Context

The backend runs in two modes controlled by the [`USE_MOCK`](backend/app/main.py:21) flag:

| Mode | `USE_MOCK` | Behaviour |
|---|---|---|
| **Production** | `False` | Real PyTorch models: DINOv2/ViT feature extractors, FAISS vector index, VGGT-1B processor with GPU acceleration |
| **Mock** | `True` (default) | `MockExtractor` (random 768-D vectors) + `MockVectorDB` (1000 synthetic Kyiv landmarks). **VGGT-1B is bypassed entirely** — the `/api/v1/vggt-odometry` endpoint returns a simulated JSON response with random `{x, y, z}` spatial offsets. No GPU/ML dependencies needed. |

The `/api/v1/health` endpoint reports the active mode via the `"mode"` field.

#### VGGT-1B Mock Bypass for Windows

When `USE_MOCK = True` (the default), the [`/api/v1/vggt-odometry`](backend/app/main.py:248) endpoint **completely skips VGGT-1B model loading and inference**:

```python
# backend/app/main.py
if USE_MOCK:
    mock_offset = {
        "x": round(random.uniform(-0.5, 0.5), 6),
        "y": round(random.uniform(-0.3, 0.3), 6),
        "z": round(random.uniform(-0.5, 0.5), 6),
    }
    return JSONResponse(content={
        "status": "success",
        "camera_center_offset": mock_offset,
    })
```

This is the **official workaround** for the Windows CPU `OSError (os error 1455)` — see [Section 12](#critical-warning--vggt-1b-on-windows-cpu) for additional workarounds (Docker, paging file).

**Simulated response shape:**
```json
{
  "status": "success",
  "camera_center_offset": {
    "x": 0.123456,
    "y": -0.045678,
    "z": 0.234567
  }
}
```

### 13.5 Code Clean-up and Reference Fixes

#### `deliveryHistoryDao` Scoping Resolution

The `deliveryHistoryDao` variable scoping bug has been fully resolved. The DAO is now properly initialized from the Room database abstraction layer at [`MainViewModel.kt:118-121`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:118):

```kotlin
private val db = AppDatabase.getInstance(application)
private val deliveryHistoryDao = db.deliveryHistoryDao()
```

This feeds into both:
- [`RoomLocationRepositoryImpl`](mobile/android/app/src/main/java/com/navisense/data/RoomLocationRepositoryImpl.kt:40) (injected to satisfy the interface contract)
- The reactive [`deliverySummary`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:274) StateFlow (aggregated KPI cards from `delivery_history` table)

#### TSP Route Optimisation — Database Decoupling

The nearest-neighbour TSP heuristic [`optimizeRoute()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:443) is **completely decoupled from any active database insertions**:

- Operates **exclusively** on the in-memory `_routeWaypoints` StateFlow.
- Uses pure Haversine distance calculations on `AppLocation.latitude`/`longitude` fields.
- After reordering, calls [`fallbackToStraightLines()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:499) which creates `LatLng` points in memory — **no database writes, no network calls**.
- The Google Directions API path (with `optimizeWaypoints=true`) remains available as a separate code path for production use when the API key is configured.

This ensures route optimisation is a **pure UI/UX operation** with zero side effects on persistent storage.

### 13.6 Live Burst Capture — Production Mode

The CameraX capture FAB in `VisualSearchFragment` has been evolved from single‑frame capture to **5‑frame burst capture** when the scanner tab is active.

**Implementation files:**

| File | Role |
|------|------|
| [`ScannerCamera.captureBurst()`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt:266) | 5‑frame sequential capture via recursive `captureNext()` callback chain with blur detection per frame |
| [`MainViewModel.liveBurstCapture()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:772) | Sensor-fusion pipeline: `POST /api/visual-locate` (frame 1) + `POST /api/v1/vggt-odometry` (all 5 frames) |
| [`VisualSearchFragment.capturePhoto()`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:615) | Evolved trigger: calls `captureBurst()` when in scanner mode |
| [`VisualSearchFragment.proceedWithSearch()`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:709) | Routes captured file list through location confirmation and into `liveBurstCapture()` |

**Key constants:**

| Constant | Value | Location |
|----------|-------|----------|
| `BURST_FRAME_COUNT` | `5` | [`ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt:61) |
| `BURST_INTER_MS` | `150` | [`ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt:62) |
| `MAX_BLUR_RETRIES_PER_FRAME` | `2` | [`ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt:63) |
| `BLUR_THRESHOLD` | `100.0` | [`ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt:65) |
| `BLUR_SCALE_MAX` | `512` | [`ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt:66) |

**Thread safety:** An `AtomicInteger` gate (`_burstGate`) in `captureBurst()` prevents concurrent burst invocations. The recursive `captureNext()` chain uses `postDelayed` with `BURST_INTER_MS` spacing between frames.

### 13.7 Dashcam Background Service

A **foreground service** ([`DashcamBackgroundService`](mobile/android/app/src/main/java/com/navisense/core/DashcamBackgroundService.kt)) enables continuous background camera capture for live positioning when the user switches to [`NavMode.DASHCAM`](mobile/android/app/src/main/java/com/navisense/model/NavMode.kt).

**Architecture overview:**

```
User switches to Dashcam mode via MapFragment toggle
       │
       ▼
┌────────────────────────────────────────────────────────┐
│  MainActivity                                          │
│                                                         │
│  ┌─ lifecycleScope.launch { repeatOnLifecycle(STARTED) │
│  │      navMode.collect { mode ->                      │
│  │        DASHCAM → requestDashcamPermissionAndStart() │
│  │        SCANNER → DashcamBackgroundService.stop()    │
│  │      }                                              │
│  │  }                                                  │
│  └─────────────────────────────────────────────────────│
│                                                         │
│  ┌─ requestDashcamPermissionAndStart() ───────────────┐│
│  │ ① Check CAMERA (always) + POST_NOTIFICATIONS (33+)││
│  │ ② If denied → launch dashcamPermissionsLauncher   ││
│  │   (RequestMultiplePermissions)                      ││
│  │ ③ If denied by user → revert to NavMode.SCANNER   ││
│  │ ④ If granted → DashcamBackgroundService.start()   ││
│  └─────────────────────────────────────────────────────││
│                                                         │
│  ┌─ BroadcastReceiver (RECEIVER_NOT_EXPORTED) ────────┐│
│  │  ACTION_DASHCAM_LOCATION_UPDATE → publishDashcamLoc││
│  └─────────────────────────────────────────────────────││
└──────────────────────────┬─────────────────────────────┘
                           │ start/stop
                           ▼
┌──────────────────────────────────┐
│    DashcamBackgroundService      │
│  ┌─ onCreate() ────────────────┐ │
│  │  ServiceLifecycleOwner      │ │
│  │  CameraX (headless)         │ │
│  │  Notification channel       │ │
│  └──────────────────────────────┘ │
│  ┌─ startForegroundWithNotification() ─┐ │
│  │  ServiceCompat.startForeground(     │ │
│  │    this, NOTIFICATION_ID,           │ │
│  │    notification,                    │ │
│  │    FOREGROUND_SERVICE_TYPE_CAMERA   │ │
│  │  )                                  │ │
│  └──────────────────────────────────────┘ │
│  ┌─ captureSingleFrame() ──────┐ │
│  │  ① ImageCapture.takePicture │ │
│  │  ② Bitmap → JPGBytes       │ │
│  │  ③ isImageBlurry() check   │ │
│  │  ④ FileManagerService.save │ │
│  │  ⑤ sendFrameToBackend()    │ │
│  └──────────────────────────────┘ │
│         │ every 5s               │
│         ▼                         │
│  ┌─ sendFrameToBackend() ──────┐ │
│  │  POST /api/visual-locate    │ │
│  │  → AppLocation response     │ │
│  │  → Broadcast intent extras  │ │
│  └──────────────────────────────┘ │
└──────────┬───────────────────────┘
           │ ACTION_DASHCAM_LOCATION_UPDATE
           ▼
┌──────────────────────────────────┐
│  MainActivity (BroadcastReceiver)│
│  → publishDashcamLocation()      │
│  → liveTrackingLocation StateFlow│
└──────────┬───────────────────────┘
           │ collectLatest
           ▼
┌──────────────────────────────────┐
│  MapFragment                     │
│  → dropDashcamMarker()           │
│  → in-place position update      │
│  (smooth animation, no remove)   │
└──────────────────────────────────┘
```

**Key design decisions:**

| Decision | Rationale |
|----------|-----------|
| **Headless CameraX** (`ImageCapture` only, no `PreviewView`) | The service runs in the background with no UI; binding only the capture use case avoids unnecessary surface creation |
| **`ServiceLifecycleOwner`** | Custom `LifecycleOwner` using `LifecycleRegistry` — required for CameraX lifecycle binding in a `Service` context |
| **`ServiceCompat.startForeground()` with `FOREGROUND_SERVICE_TYPE_CAMERA`** | Raw `startForeground()` does not accept a service-type argument on API 30+. `ServiceCompat.startForeground()` is the AndroidX API that accepts `foregroundServiceType` — required for camera-type foreground services on API 34+. See [Security §3.4](#34-android-14-foreground-service-security-rules). |
| **Foreground notification** | Mandatory for foreground services on Android 8.0+; `FOREGROUND_SERVICE_TYPE_CAMERA` required for API 34+ camera access in background |
| **5‑second periodic capture** | `Handler.postDelayed()` loop — balanced between positioning accuracy and battery consumption |
| **Blur detection** | Same Laplacian variance algorithm as `ScannerCamera` (threshold 100.0, 512px max scale) — rejects blurry frames silently |
| **Broadcast Intent communication** | Service → Activity via `Context.sendBroadcast()` with `ACTION_DASHCAM_LOCATION_UPDATE` — avoids tight coupling between Service and Activity |
| **`SupervisorJob`** | Each capture runs in its own coroutine scope with `SupervisorJob` — a single capture failure does not cancel the loop |
| **Permission gate: `CAMERA` + `POST_NOTIFICATIONS`** | Android 14 requires **both** `CAMERA` runtime permission (for `FOREGROUND_SERVICE_TYPE_CAMERA` foreground service start) and `POST_NOTIFICATIONS` (API 33+, for the foreground notification). Requested together via `ActivityResultContracts.RequestMultiplePermissions()` in [`MainActivity`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt:62). If the user denies any permission, `navMode` is reverted to `SCANNER` via `viewModel.setNavMode(NavMode.SCANNER)` and a Toast explains the requirement. |
| **`ContextCompat.RECEIVER_NOT_EXPORTED`** | On API 34+, `Context.registerReceiver()` must specify either `RECEIVER_NOT_EXPORTED` or `RECEIVER_EXPORTED`. The `dashcamLocationReceiver` is registered via `androidx.core.content.ContextCompat.registerReceiver()` with `ContextCompat.RECEIVER_NOT_EXPORTED` in [`MainActivity.onResume()`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt:154) — it is an internal-only receiver and must not be exported. See [Security §3.4](#34-android-14-foreground-service-security-rules). |

**Files changed:**

| File | Change |
|------|--------|
| [`DashcamBackgroundService.kt`](mobile/android/app/src/main/java/com/navisense/core/DashcamBackgroundService.kt) | **New** — full foreground service with headless CameraX, periodic capture, blur detection, backend communication, broadcast emission. Uses `ServiceCompat.startForeground()` instead of raw API. Single `companion object` holds all intent action keys (`ACTION_DASHCAM_LOCATION_UPDATE`, `EXTRA_*`, `NOTIFICATION_ID`, `CAPTURE_INTERVAL_MS`) plus `start()` / `stop()` factory methods. |
| [`MainActivity.kt`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt) | Added `StateFlow` collector via `lifecycleScope.launch { repeatOnLifecycle(STARTED) { navMode.collect { … } } }`, `dashcamLocationReceiver` BroadcastReceiver (registered with `RECEIVER_NOT_EXPORTED`), `dashcamPermissionsLauncher` (combined `CAMERA` + `POST_NOTIFICATIONS` via `RequestMultiplePermissions()`, with SCANNER reversion on denial), `requestDashcamPermissionAndStart()` with dual permission checks. |
| [`MainViewModel.kt`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt) | Added `_navMode` StateFlow, `_liveTrackingLocation` StateFlow, `setNavMode()`, `publishDashcamLocation()`, `dashcamVisualLocate()` |
| [`MapFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt) | Added `dashcamMarker` field, `lastDashcamLocation` persistence, `liveTrackingLocation` observer (collectLatest), `dropDashcamMarker()`, `clearDashcamMarker()`, re-add logic in `renderMarkers()` |
| [`AndroidManifest.xml`](mobile/android/app/src/main/AndroidManifest.xml) | Declared `DashcamBackgroundService` with `foregroundServiceType="camera"`, added `POST_NOTIFICATIONS` permission, `FOREGROUND_SERVICE_CAMERA` permission (note: **not** `FOREGROUND_SERVICE_TYPE_CAMERA` — the `_TYPE_` suffix would compile silently but be ignored by the system) |
| [`values/strings.xml`](mobile/android/app/src/main/res/values/strings.xml) | Added 7 Dashcam string resources: channel name/description, notification title/text, marker title, `dashcam_permission_required`, `permission_camera_required` |
| [`values-uk/strings.xml`](mobile/android/app/src/main/res/values-uk/strings.xml) | Added 7 Ukrainian translations for Dashcam strings |

### 13.8 Video File Upload Simulation

If a user uploads an **MP4 video** from the gallery picker, the fragment detects the MIME type and extracts 5 keyframes for the sensor-fusion pipeline.

**Implementation steps:**

| Step | Implementation | Location |
|------|---------------|----------|
| **MIME detection** | `contentResolver.getType(uri)` checked for `"video/mp4"` in gallery callback | [`VisualSearchFragment.kt:691`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:691) |
| **Gallery intent** | `ActivityResultContracts.GetContent()` with `"image/*"` — users can still pick videos due to Android URI filtering | [`VisualSearchFragment.kt:160`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:160) |
| **Frame extraction** | `MediaMetadataRetriever` at 5 evenly‑spaced timestamps, `OPTION_CLOSEST_SYNC` (primary) / `OPTION_CLOSEST` (fallback), writes JPEGs to `TempScans/` | [`extractVideoFrames()`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:933) |
| **Pipeline injection** | Extracted frames passed to `liveBurstCapture()` — same ENU → WGS-84 sensor-fusion pipeline | [`handleVideoPicked()`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:878) |
| **Cleanup** | Temp frames deleted in `clearTempScansAfterSearch()` | [`VisualSearchFragment.kt:850`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:850) |

**Key constants:**

| Constant | Value | Location |
|----------|-------|----------|
| `VIDEO_FRAME_COUNT` | `5` | [`VisualSearchFragment.kt:933`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:933) |
| `OPTION_CLOSEST_SYNC` | `2` | Primary retriever option — returns closest sync (I-) frame |
| `OPTION_CLOSEST` | `3` | Fallback — returns closest frame (may differ slightly) |

---

## 14. Appendix: Complete File Inventory

### Android Mobile App

| File | Purpose |
|------|---------|
| [`mobile/android/settings.gradle.kts`](mobile/android/settings.gradle.kts) | Root project config |
| [`mobile/android/build.gradle.kts`](mobile/android/build.gradle.kts) | Project-level: AGP 8.2.2, Kotlin 1.9.22 |
| [`mobile/android/gradle.properties`](mobile/android/gradle.properties) | AndroidX, parallel builds, JVM args |
| [`mobile/android/app/build.gradle.kts`](mobile/android/app/build.gradle.kts) | App-level: all dependencies, Maps API key injection, Room KSP, packaging config |
| [`mobile/android/app/src/main/AndroidManifest.xml`](mobile/android/app/src/main/AndroidManifest.xml) | Permissions, Maps API key meta-data, activity declaration |
| [`mobile/android/app/src/main/assets/ref1.jpg`](mobile/android/app/src/main/assets/ref1.jpg) | Burst test asset (frame 1) |
| [`mobile/android/app/src/main/assets/ref2.jpg`](mobile/android/app/src/main/assets/ref2.jpg) | Burst test asset (frame 2) |
| [`mobile/android/app/src/main/assets/ref3.jpg`](mobile/android/app/src/main/assets/ref3.jpg) | Burst test asset (frame 3) |
| [`mobile/android/app/src/main/assets/ref4.jpg`](mobile/android/app/src/main/assets/ref4.jpg) | Burst test asset (frame 4) |
| [`mobile/android/app/src/main/assets/ref5.jpg`](mobile/android/app/src/main/assets/ref5.jpg) | Burst test asset (frame 5) |
| [`mobile/android/app/src/main/java/com/navisense/MainActivity.kt`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt) | Single Activity host, NavHostFragment + BottomNavigation, locale helpers |
| [`mobile/android/app/src/main/java/com/navisense/NaviSenseApplication.kt`](mobile/android/app/src/main/java/com/navisense/NaviSenseApplication.kt) | Application class, lazy AppDatabase singleton |
| [`mobile/android/app/src/main/java/com/navisense/model/AppLocation.kt`](mobile/android/app/src/main/java/com/navisense/model/AppLocation.kt) | Core @Parcelize data class |
| [`mobile/android/app/src/main/java/com/navisense/model/AppLocationCategory.kt`](mobile/android/app/src/main/java/com/navisense/model/AppLocationCategory.kt) | Category enum with marker hues and chart colors |
| [`mobile/android/app/src/main/java/com/navisense/model/MarkerItem.kt`](mobile/android/app/src/main/java/com/navisense/model/MarkerItem.kt) | Legacy model (Sprint 1) — may be dead code |
| [`mobile/android/app/src/main/java/com/navisense/model/LocationState.kt`](mobile/android/app/src/main/java/com/navisense/model/LocationState.kt) | FRESH / DEGRADING / STALE enum |
| [`mobile/android/app/src/main/java/com/navisense/model/NavMode.kt`](mobile/android/app/src/main/java/com/navisense/model/NavMode.kt) | SCANNER / DASHCAM enum |
| [`mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt`](mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt) | Repository interface: CRUD + toggleVisited + toggleFavorite |
| [`mobile/android/app/src/main/java/com/navisense/data/RoomLocationRepositoryImpl.kt`](mobile/android/app/src/main/java/com/navisense/data/RoomLocationRepositoryImpl.kt) | Room-backed repository (production, local-first) |
| [`mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt`](mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt) | In-memory mock with 10 Kyiv landmarks (testing only) |
| [`mobile/android/app/src/main/java/com/navisense/data/local/AppDatabase.kt`](mobile/android/app/src/main/java/com/navisense/data/local/AppDatabase.kt) | Room database singleton (v2, destructive migration) |
| [`mobile/android/app/src/main/java/com/navisense/data/local/SavedLocation.kt`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocation.kt) | Room entity: saved favourite points |
| [`mobile/android/app/src/main/java/com/navisense/data/local/SavedLocationDao.kt`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocationDao.kt) | Room DAO: saved locations CRUD with atomic toggles |
| [`mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistory.kt`](mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistory.kt) | Room entity: delivery trip log |
| [`mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistoryDao.kt`](mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistoryDao.kt) | Room DAO: delivery history queries |
| [`mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt) | Shared ViewModel: all state flows, filtering, analytics, route optimisation, sensor-fusion burst pipeline |
| [`mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt) | Map screen: markers, filters, search, language toggle |
| [`mobile/android/app/src/main/java/com/navisense/ui/details/LocationDetailsBottomSheet.kt`](mobile/android/app/src/main/java/com/navisense/ui/details/LocationDetailsBottomSheet.kt) | Location details: visited, favorite, edit, delete |
| [`mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt) | Add/Edit location: map picker, form, photo, save |
| [`mobile/android/app/src/main/java/com/navisense/ui/route/RouteBuilderFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/route/RouteBuilderFragment.kt) | Route builder: waypoints, TSP optimisation, polyline, navigation |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/AnalyticsFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/AnalyticsFragment.kt) | Analytics screen: pie, bar, district charts + KPI cards |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/PieChartView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/PieChartView.kt) | Custom Canvas pie chart |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/BarChartView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/BarChartView.kt) | Custom Canvas vertical bar chart |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictBarChartView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictBarChartView.kt) | Custom Canvas horizontal bar chart (per district) |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/DoughnutChartView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/DoughnutChartView.kt) | Custom Canvas doughnut chart |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictLollipopChartView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictLollipopChartView.kt) | Custom Canvas lollipop chart |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/EfficiencyStackedBarView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/EfficiencyStackedBarView.kt) | Custom Canvas stacked bar chart |
| [`mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt) | Visual search: CameraX, gallery, location confirmation, burst test button |
| [`mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchViewModel.kt`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchViewModel.kt) | Location confirmation state machine, ViT API caller |
| [`mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt) | CameraX wrapper: capture, burst capture, blur detection, JPEG compression |
| [`mobile/android/app/src/main/java/com/navisense/core/DashcamBackgroundService.kt`](mobile/android/app/src/main/java/com/navisense/core/DashcamBackgroundService.kt) | Foreground service: headless CameraX, periodic capture, blur detection, broadcast emission |
| [`mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt`](mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt) | TempScans management, storage checks, error logging, multipart prep |
| [`mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt`](mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt) | Retrofit client: retry logic, image upload |
| [`mobile/android/app/src/main/java/com/navisense/core/NaviSenseApi.kt`](mobile/android/app/src/main/java/com/navisense/core/NaviSenseApi.kt) | Retrofit interface: 3 endpoints (position, visual-locate, vggt-odometry) |
| [`mobile/android/app/src/main/res/navigation/nav_graph.xml`](mobile/android/app/src/main/res/navigation/nav_graph.xml) | Navigation graph with 5 destinations |
| [`mobile/android/app/src/main/res/menu/bottom_nav_menu.xml`](mobile/android/app/src/main/res/menu/bottom_nav_menu.xml) | Bottom navigation: Map, Routes, Add, Analytics, Visual Search |
| [`mobile/android/app/src/main/res/values/strings.xml`](mobile/android/app/src/main/res/values/strings.xml) | English string resources (incl. burst, dashcam, locale-switching strings) |
| [`mobile/android/app/src/main/res/values-uk/strings.xml`](mobile/android/app/src/main/res/values-uk/strings.xml) | Ukrainian string resources (incl. burst, dashcam, locale-switching translations) |
| [`mobile/android/app/src/main/res/values/colors.xml`](mobile/android/app/src/main/res/values/colors.xml) | Brand colours, marker colours, radius fill |
| [`mobile/android/app/src/main/res/values/themes.xml`](mobile/android/app/src/main/res/values/themes.xml) | Material3 Dark NoActionBar theme |
| [`mobile/android/app/src/main/res/xml/locales_config.xml`](mobile/android/app/src/main/res/xml/locales_config.xml) | Per-App Language Preferences config |

### Backend (Python)

| File | Purpose |
|------|---------|
| [`backend/app/main.py`](backend/app/main.py) | FastAPI application: 6 endpoints, file validation, `USE_MOCK` flag, mock fallback |
| [`backend/app/feature_extractor.py`](backend/app/feature_extractor.py) | DINOv2/ViT feature extraction (768-D, L2 normalized, CUDA + FP16) |
| [`backend/app/vector_db.py`](backend/app/vector_db.py) | FAISS vector database (GPU-capable, real data only) |
| [`backend/app/vggt_processor.py`](backend/app/vggt_processor.py) | VGGT-1B wrapper for 3D odometry (AMP autocast) |
| [`backend/app/init_vector_db.py`](backend/app/init_vector_db.py) | CLI tool to build FAISS index from real metadata.json |
| [`backend/app/test_processor.py`](backend/app/test_processor.py) | Test harness for VGGTProcessor |
| [`backend/requirements.txt`](backend/requirements.txt) | Python dependencies |
| [`backend/Dockerfile`](backend/Dockerfile) | Docker image definition (Linux-based) |
| [`backend/README.md`](backend/README.md) | Backend documentation |
| [`backend/data/reference_images/ref1.jpg`](backend/data/reference_images/ref1.jpg) – [`ref5.jpg`](backend/data/reference_images/ref5.jpg) | Placeholder reference images (require production-grade replacements) |

---

*This document is the single source of truth for the NaviSense project. Any architectural, data model, or validation changes must be reflected here before implementation.*
