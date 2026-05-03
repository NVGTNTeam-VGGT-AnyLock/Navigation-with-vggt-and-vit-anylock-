# NaviSense — Single Source of Truth

> **Maintainer:** Android Team  
> **Audience:** All developers (frontend, backend, database)  
> **Last Updated:** 2026-05-01  
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
12. [Known Issues & Gaps](#12-known-issues--gaps)
13. [Appendix: Complete File Inventory](#13-appendix-complete-file-inventory)

---

## 1. Project Overview & Tech Stack

NaviSense is a native Android Location Management application with visual positioning capabilities. The app allows couriers to save, organise, and navigate to points of interest, with route optimisation and analytics.
### Mobile Frontend
- **Platform:** Native Android (minimum SDK 26, target SDK 34)
- **Language:** Kotlin (no cross‑platform frameworks)
- **Architecture:** Single‑Activity with Navigation Component + BottomNavigationView (5 tabs)
- **Key Libraries:**
  - **Navigation Component 2.7.7** — fragment-based navigation with `NavHostFragment` and Bottom Navigation
  - **CameraX 1.4.1** for single‑frame image capture
    - Resolution selector targeting 1080×1920 (portrait)
    - Capture mode: `MINIMIZE_LATENCY`
    - Built‑in `ImageProxy.toBitmap()` (CameraX 1.4+)
  - **Google Maps SDK (play-services-maps:18.2.0)** for map display
  - **Maps-Utils-KTx (5.0.0)** for enhanced map utilities
  - **Play Services Location (21.1.0)** for FusedLocationProviderClient
  - **Retrofit2 + OkHttp4** for REST communication
    - Base URL configurable via `BuildConfig.BACKEND_URL`
    - Timeout: 15 seconds connect, 30 seconds read/write
    - Multipart file upload with JPEG compression quality 85%
    - Logging interceptor (HTTP body logging in debug)
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
└──────────────────────────┬──────────────────────────────┘
                           │ calls suspend functions
                           ▼
┌─────────────────────────────────────────────────────────┐
│              Repository Layer (Interface)                │
│  LocationRepository (interface)                          │
│    ▲                                                    │
│    │ implements                                         │
│    │                                                    │
│  MockLocationRepositoryImpl  (RoomLocationRepo — TBD)   │
│  (in-memory StateFlow)        (SQLite via Room)         │
└─────────────────────────────────────────────────────────┘
```

### Key Architectural Decisions
    subgraph "Shared ViewModel"
        H[MainViewModel] --> I[LocationRepository]
        I --> J[MockLocationRepositoryImpl]
        H --> K[analyticsData]
        H --> L[routeWaypoints & routePolylinePoints]
        H --> M[filteredLocations]
        H --> N[searchQuery / showFavoritesOnly / visitedFilter]
    end

- **Single Activity** ([`MainActivity.kt`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt)) hosts a [`NavHostFragment`](mobile/android/app/src/main/res/layout/activity_main.xml:12) with 5 bottom-navigation destinations.
- **Shared ViewModel** ([`MainViewModel`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt)) is scoped to the Activity, meaning all fragments share the same instance and react to the same state flows.
- **Repository Pattern** — [`LocationRepository`](mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt) is an interface. The current implementation [`MockLocationRepositoryImpl`](mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt) uses an in-memory `MutableStateFlow<List<AppLocation>>`. A Room-backed implementation can be swapped in without changing any ViewModel or UI code (see [Section 9](#9-future-integration-notes-for-database-developer--anya)).
- **Navigation Graph** — defined in [`nav_graph.xml`](mobile/android/app/src/main/res/navigation/nav_graph.xml) with 5 fragment destinations.
    subgraph "Room Database (MVP)"
        O[AppDatabase] --> P[DeliveryHistoryDao]
        O --> Q[SavedLocationDao]
    end

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
    subgraph "Wired Core Services (Sprint 3)"
        R[ScannerCamera] --> S[FileManagerService]
        S --> T[TempScans/ Folder]
        R --> U[Laplacian Blur Detection]
        V[LocalizationApiClient] --> W[Retrofit -> NaviSenseApi]
        W --> X[POST /api/v1/position]
    end

    C --> H
    D --> H
    E --> H
    F --> H
    G --> H
    G --> R   "CameraX now wired!"
```

### 3.2 Backend URL

Configured via `BuildConfig.BACKEND_URL` in [`build.gradle.kts`](mobile/android/app/build.gradle.kts:52). Defaults to `http://10.0.2.2:8000/` (Android emulator loopback to host machine).
| # | Tab | Fragment | Description |
|---|-----|----------|-------------|
| 1 | Map (Home) | [`MapFragment`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt) | Full‑screen Google Map with **search bar** (fuzzy across title/description/category), **category filter chips** (All, Monument, Grocery, etc.) in a horizontal scroll, **advanced filter buttons** (Visited 3-state toggle, Favorites toggle), radius filter (Off → 1/2/5/10 km circle overlay), **language toggle** (EN/UK via `AppCompatDelegate.setApplicationLocales()`), My‑Location FAB with runtime permission flow, location markers coloured by category (visited → HUE_VIOLET), mock match marker drop |
| 2 | Routes | [`RouteBuilderFragment`](mobile/android/app/src/main/java/com/navisense/ui/route/RouteBuilderFragment.kt) | Split view: map (top) + selectable waypoint list (bottom). Polyline connects selected waypoints with road‑aware mock interpolation. **Optimize Route button** reorders middle waypoints using nearest-neighbour TSP heuristic. "Start Navigation" launches Google Maps external app (or web fallback) to final destination. |
| 3 | Add (+) | [`AddLocationFragment`](mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt) | Map picker + form (title, description, category dropdown with "No Category" option, photo attachment from gallery/camera). **Edit mode** supported via `newInstance()` with pre-filled fields, preserves `isVisited`/`isFavorite` flags. |
| 4 | Analytics | [`AnalyticsFragment`](mobile/android/app/src/main/java/com/navisense/ui/analytics/AnalyticsFragment.kt) | Custom Canvas-drawn [`PieChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/PieChartView.kt) (category distribution) + [`BarChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/BarChartView.kt) (Visited, Not Visited, Favorites, Others) + [`DistrictBarChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictBarChartView.kt) (locations per Kyiv district) + total location count card. |
| 5 | Visual Search | [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt) | **CameraX live preview** with single-frame capture, blur validation via `ScannerCamera`, temporary file storage via `FileManagerService`, and mock visual positioning. Camera permission requested at runtime. Also supports gallery upload. After 2‑second mock inference → navigates to Map and drops a yellow mock-match marker. **TempScans folder wiped after search completes.** |

### 3.3 Camera & Storage Permissions

- CAMERA permission is **not required** at install time (`required="false"` in manifest). Requested at runtime in [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:60).
- Location permissions (FINE + COARSE) requested at runtime in [`MapFragment`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:61).
- TempScans folder lives in **app-internal storage** (sandboxed, inaccessible to other apps).

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
> **Current state:** Steps 1–2 (CameraX + blur detection) are implemented and **wired into [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt)**. Steps 3–9 are fully implemented in the backend but the mobile → backend integration (`LocalizationApiClient`) is still **not wired** — the fragment uses a mock 2-second delay instead.

| Field | Type | Notes |
|-------|------|-------|
| `id` | `Int` | Auto-incremented by `MockLocationRepositoryImpl` |
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

### 4.3 MockLocationRepositoryImpl — Seed Data

[`MockLocationRepositoryImpl`](mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt) initialises with **10 Kyiv landmarks** as seed data, covering all 5 categories. The in-memory `MutableStateFlow` enables reactive UI updates without a database.

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
| `routePolylinePoints` | `StateFlow<List<Pair<Double, Double>>>` | Mock "road-aware" polyline points |
| `mockMatchLocation` | `StateFlow<AppLocation?>` | Visual Search mock match result |
| `analyticsData` | `StateFlow<AnalyticsData>` | Computed analytics (category counts, visited, favorites, district counts) |

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

The route optimisation logic is implemented in [`MainViewModel.optimizeRoute()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:264):

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

[`MainViewModel.recalculateRoute()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:298) generates interpolated points between consecutive waypoints with slight perpendicular jitter (sin/cos offset) to simulate road-aware routing. The polyline is drawn in blue (`#1565C0`) at 6px width.

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

[`MainViewModel.detectDistrict()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:150) maps coordinates to one of 8 Kyiv districts using simple bounding-box logic:

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

---

### F. Visual Search

**Fragment:** [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt)  
**Layout:** [`fragment_visual_search.xml`](mobile/android/app/src/main/res/layout/fragment_visual_search.xml)

#### CameraX Integration (Wired — Sprint 1 + 2)

The CameraX pipeline is now **fully wired** in this fragment:

1. **Permission Check** — On fragment creation, checks for CAMERA permission. If not granted, launches runtime permission request.
2. **Camera Initialization** — Instantiates [`ScannerCamera`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt) with a live `PreviewView`, `FileManagerService`, and the fragment's lifecycle owner.
3. **Live Preview** — Camera preview appears immediately after initialization.
4. **Capture Flow** — User taps the capture FAB → `ScannerCamera.captureSharpImage()`:
   - CameraX captures a single frame at 1080×1920 resolution with `CAPTURE_MODE_MINIMIZE_LATENCY`.
   - Laplacian variance blur detection (threshold 100.0) — rejects blurry images.
   - Sharp images are saved to `TempScans/` via [`FileManagerService.saveImage()`](mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt:46).
   - On success: toast confirmation → starts mock search.
   - On failure (blurry, storage, camera error): descriptive toast, user can retry.

#### Gallery Upload

- "Upload Photo" button opens system gallery picker via `GetContent("image/*")`.
- Selected image triggers the same mock search flow (no TempScans save).

#### Mock Search Flow

After capture or gallery selection:

1. Loading overlay shown (spinner, buttons disabled).
2. 2-second delay (mocks network inference time).
3. Random mock match `AppLocation` created near Kyiv centre with "Match Found" title.
4. [`viewModel.setMockMatchResult()`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt:296) stores the mock result.
5. Fragment navigates to Map tab via `findNavController().navigate(R.id.mapFragment)`.
6. [`MapFragment`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:350) observes `mockMatchLocation` and drops a **yellow marker** at the match position with a "Match Found" toast.
7. TempScans folder is cleaned up via [`FileManagerService.clearTempScansFolder()`](mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt:110).

**Note:** The actual ML inference pipeline (DINOv2 feature extraction + FAISS vector search) is fully implemented on the backend but not yet wired from the mobile app. The mock replaces the `POST /api/v1/position` call.

---

## 6. Reactive Filtering Architecture

The filtering system uses Kotlin Flow's [`combine()`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:62) operator to derive `filteredLocations` from multiple filter states:

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
The project has evolved through three major phases:

        // 1. Category filter
        if (category != null) {
            result = result.filter { it.category == category }
        }
- **Sprint 1 (Complete):** Delivered a single-screen UI shell with Google Maps integration, runtime permission handling, mock marker placement, CameraX capture module, FileManagerService, LocalizationApiClient, and bilingual (EN/UK) resources. Much of this remained unwired.

        // 2. Fuzzy search (title, description, OR category)
        if (query.isNotBlank()) {
            val q = query.lowercase().trim()
            result = result.filter { loc ->
                loc.title.lowercase().contains(q) ||
                loc.description.lowercase().contains(q) ||
                loc.category.lowercase().contains(q)
            }
        }
- **Sprint 2 (Complete):** Refactored the monolithic `MainActivity` into a **5-tab Navigation Component** architecture. Implemented `MapFragment`, `AddLocationFragment`, `RouteBuilderFragment`, `AnalyticsFragment`, `VisualSearchFragment`, shared `MainViewModel` with `LocationRepository` pattern, `AppLocation`/`AppLocationCategory` models, custom chart views, and mock visual search flow. Backend received full ML pipeline (DINOv2 + FAISS) with mock fallback mode and Docker support.

        // 3. Favorites only
        if (favoritesOnly) {
            result = result.filter { it.isFavorite }
        }
- **Sprint 3 (In Progress):** Major UI/UX overhaul (Alina) and Room database expansion (BD).
  - **CameraX wired** into `VisualSearchFragment` with live preview, blur detection, and TempScans management.
  - **Enhanced Map UI:** Search bar, advanced filters (Visited 3-state, Favorites toggle), language toggle (EN/UK).
  - **Edit mode** for AddLocationFragment with pre-filled fields and isVisited/isFavorite preservation.
  - **Favorites system:** `isFavorite` field on `AppLocation`, heart icon toggle in LocationDetailsBottomSheet, favorites filter on map.
  - **Route optimization:** TSP nearest-neighbour heuristic with road-aware mock polyline.
  - **District analytics:** `DistrictBarChartView` and `detectDistrict()` in MainViewModel.
  - **Room expansion:** Added `SavedLocation` entity + `SavedLocationDao` (version 2 of AppDatabase).
  - **Dark theme:** Material3 Dark NoActionBar with BottomSheet dark overlay.
  - **Runtime locale switching:** `AppCompatDelegate.setApplicationLocales()` with `locale_config.xml`.

### 7.2 What Works (Verified)

        // 4. Visited status
        if (visitedFilter != null) {
            result = result.filter { it.isVisited == visitedFilter }
        }

        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```
| Feature | Status | Details |
|---|---|---|
| **Google Maps Display** | ✅ Working | `SupportMapFragment` renders map tiles in `MapFragment`. Default camera centres on Kyiv (50.4501, 30.5234) at zoom 13. |
| **Map UI Controls** | ✅ Working | Zoom controls (`+`/`–` buttons) enabled. Map toolbar disabled for MVP simplicity. |
| **CameraX ScannerCamera** | ✅ **Wired into VisualSearchFragment** | [`ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt): `ResolutionSelector`, Laplacian variance blur detection (threshold 100.0), `captureSharpImage()` callback, `ImageTooBlurryException`. **Now instantiated in [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt)** with live PreviewView. |
| **FileManagerService** | ✅ **Wired into VisualSearchFragment** | [`FileManagerService.kt`](mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt): TempScans folder, 50 MB free‑space check, UUID file naming, error logging, `prepareImagePart()` for Retrofit multipart upload. **Now used by [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt)** for saving captured images and cleaning up TempScans after search. |
| **LocalizationApiClient** | ✅ Implemented (not wired) | [`LocalizationApiClient.kt`](mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt): Retrofit client, OkHttp timeouts (15s/30s/30s), retry logic (3 attempts with exponential backoff), file cleanup after success/failure. **Not called from any fragment** — VisualSearch still uses mock 2-second delay. |
| **NaviSenseApi** | ✅ Implemented (not wired) | [`NaviSenseApi.kt`](mobile/android/app/src/main/java/com/navisense/core/NaviSenseApi.kt): Retrofit interface with `uploadImage()` multipart endpoint. `PositionResponse` and `Landmark` data classes. |
| **Bilingual UI** | ✅ Working | Full English (`values/`) and Ukrainian (`values‑uk/`) string resources for all UI labels and messages. **Runtime switching** via language toggle button on Map. |

**Automatic map refresh:** [`MapFragment`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:343) collects `filteredLocations` and calls `renderMarkers()` on each emission, which calls `map.clear()` and re-adds all markers. This ensures the map always reflects the current filter state and any `isVisited`/`isFavorite` changes.
#### Sprint 2 Features (Complete)

---
| Feature | Status | Details |
|---|---|---|
| **Navigation Component + Bottom Nav** | ✅ Fully Implemented | 5-tab navigation: Map, Routes, Add, Analytics, Visual Search. [`NavHostFragment`](mobile/android/app/src/main/res/layout/activity_main.xml:12) with [`nav_graph.xml`](mobile/android/app/src/main/res/navigation/nav_graph.xml) and [`bottom_nav_menu.xml`](mobile/android/app/src/main/res/menu/bottom_nav_menu.xml). |
| **Add Location Screen** | ✅ Fully Implemented | [`AddLocationFragment`](mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt): Map picker with tap-to-select coordinates, title/description inputs, category dropdown (incl. "No Category"), photo attachment (gallery or camera), save via ViewModel. |
| **Route Builder** | ✅ Fully Implemented | [`RouteBuilderFragment`](mobile/android/app/src/main/java/com/navisense/ui/route/RouteBuilderFragment.kt): Split map + waypoint list. Select locations as waypoints, polyline drawn on map, "Start Navigation" opens Google Maps external nav. **Optimize Route** reorders middle waypoints via TSP heuristic. |
| **Analytics Screen** | ✅ Fully Implemented | [`AnalyticsFragment`](mobile/android/app/src/main/java/com/navisense/ui/analytics/AnalyticsFragment.kt): Custom Canvas-drawn [`PieChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/PieChartView.kt) (category distribution) and [`BarChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/BarChartView.kt) (Visited, Not Visited, Favorites, Others). Total location count card. |
| **AppLocation Model** | ✅ Fully Implemented | [`AppLocation`](mobile/android/app/src/main/java/com/navisense/model/AppLocation.kt): `@Parcelize` data class with `id`, `title`, `description`, `latitude`, `longitude`, `category`, `imageUri`, `isVisited`, **`isFavorite`**. |
| **AppLocationCategory Enum** | ✅ Fully Implemented | [`AppLocationCategory`](mobile/android/app/src/main/java/com/navisense/model/AppLocationCategory.kt): `MONUMENT`, `GROCERY`, `GAS_STATION`, `RESTAURANT`, `PHARMACY`, **`NO_CATEGORY`**. Companion `names`, `fromKey()`, **`markerHue()`**, **`chartColor()`**. |
| **LocationRepository Pattern** | ✅ Fully Implemented | [`LocationRepository`](mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt) interface + [`MockLocationRepositoryImpl`](mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt) with 10 Kyiv landmarks as seed data. Includes **`toggleFavorite()`**. |
| **Backend — Full ML Pipeline** | ✅ Fully Implemented | [`feature_extractor.py`](backend/app/feature_extractor.py): DINOv2-base model loading, 768-dim feature extraction, L2 normalization. [`vector_db.py`](backend/app/vector_db.py): FAISS IndexFlatL2, add/search/save/load, demo index with 1000 random Kyiv landmarks. |
| **Backend — FastAPI Server** | ✅ Fully Implemented | [`main.py`](backend/app/main.py): 4 endpoints (`/`, `/health`, `/position`, `/calibrate`), file validation (JPEG only, max 5 MB), weighted position averaging, mock fallback mode, error handling. |
| **Backend — Docker Support** | ✅ Fully Implemented | [`Dockerfile`](backend/Dockerfile): Python 3.10-slim, system deps for torch/faiss, pip installs requirements, exposes port 8000. |
| **Backend — Mock Fallback** | ✅ Fully Implemented | When torch/transformers/faiss are unavailable, `main.py` auto-creates `MockExtractor` (random 768-dim vectors) and `MockVectorDB` (1000 random Kyiv landmarks). |

## 7. Bilingual UI (Runtime Locale Switching)
#### Sprint 3 Features (New / Enhanced)

| Feature | Status | Details |
|---|---|---|
| **Visual Search — CameraX Wired** | ✅ **WIRED** | [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt) now instantiates [`ScannerCamera`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt) with live `PreviewView`. Capture → blur check → save → mock search → navigate to Map → cleanup TempScans. Error toasts for blurry/storage/camera/file errors. |
| **Map — Search Bar** | ✅ Fully Implemented | Search `EditText` in map overlay card. Filters locations by title/description/category via `ViewModel.setSearchQuery()`. |
| **Map — Advanced Filters** | ✅ Fully Implemented | **Visited** button (3-state: Off → Visited → Not Visited). **Favorites** toggle button. Both update `filteredLocations` reactively via ViewModel. |
| **Map — Language Toggle** | ✅ Fully Implemented | Button switches app locale between EN and UK at runtime using `AppCompatDelegate.setApplicationLocales()`. Uses `locale_config.xml` for Android 13+ per-app language preferences. |
| **Map — Location Permission** | ✅ Fully Implemented | Runtime location permission via `ActivityResultContracts.RequestMultiplePermissions()` with proper grant/deny handling. |
| **LocationDetailsBottomSheet — Favorites** | ✅ Fully Implemented | Heart icon (filled/outline) toggles `isFavorite` via `ViewModel.toggleFavorite()`. |
| **LocationDetailsBottomSheet — Edit** | ✅ Fully Implemented | Edit button opens `AddLocationFragment` in edit mode with all fields pre-filled. Uses `parentFragmentManager.beginTransaction().replace()` with backstack. |
| **LocationDetailsBottomSheet — Delete** | ✅ Fully Implemented | Delete button removes from repository and dismisses sheet. |
| **AddLocationFragment — Edit Mode** | ✅ Fully Implemented | Full edit mode via `AddLocationFragment.newInstance()` with `locationId`, `title`, `description`, `latitude`, `longitude`, `category`, `imageUri`, `isVisited`, `isFavorite`. Coordinates locked in edit mode. |
| **RouteBuilder — Optimization** | ✅ Fully Implemented | "Optimize" button reorders middle waypoints using nearest-neighbour TSP heuristic. Road-aware mock polyline with sinusoidal jitter. External nav web fallback URL. |
| **Analytics — District Chart** | ✅ Fully Implemented | [`DistrictBarChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictBarChartView.kt): horizontal bar chart with 8 Kyiv districts, coloured bars, count labels. Data from `MainViewModel.detectDistrict()`. |
| **Analytics — Favorites Bar** | ✅ Fully Implemented | [`BarChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/BarChartView.kt) now shows 4 bars: Visited (green), Not Visited (red), Favorites (pink), Others (gray). |
| **Room Database — SavedLocation Entity** | ✅ Fully Implemented | [`SavedLocation`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocation.kt) entity with `id`, `name`, `description`, `category`, `latitude`, `longitude`, `timestamp`. [`SavedLocationDao`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocationDao.kt) with full CRUD + Flow-based reactive read. [`AppDatabase`](mobile/android/app/src/main/java/com/navisense/data/local/AppDatabase.kt) updated to **version 2** with both entities. |
| **NaviSenseApplication** | ✅ Fully Implemented | [`NaviSenseApplication`](mobile/android/app/src/main/java/com/navisense/NaviSenseApplication.kt): custom `Application` subclass lazily initializing `AppDatabase` singleton. Declared in `AndroidManifest.xml`. |
| **Dark Theme** | ✅ Fully Implemented | Material3 Dark NoActionBar theme (`Theme.NaviSense`). `Theme.NaviSense.BottomSheet` for dark bottom sheet. `surface_dark` (#121212) backgrounds. |
| **Runtime Locale Switching** | ✅ Fully Implemented | [`MainActivity`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt) has `switchLocale()` and `getCurrentLocaleCode()` static helpers. `android:localeConfig="@xml/locales_config"` in manifest. |
| **Maps API Key via BuildConfig** | ✅ Fully Implemented | API key injected via `manifestPlaceholders` from `local.properties` (`MAPS_API_KEY` variable) instead of hardcoded value. Dummy fallback for CI/CD. |

### 7.3 Known Issues & Gaps

Language switching is implemented via `AppCompatDelegate.setApplicationLocales()` (API 33+ with backward compatibility via `LocaleListCompat`).
| Issue | Impact | Status / Notes |
|---|---|---|
| **ML Backend not deployed / reachable** | ❌ `LocalizationApiClient` will fail to connect. | Backend code exists and is runnable (Docker or `uvicorn`), but no cloud host is configured. `BuildConfig.BACKEND_URL` defaults to `http://10.0.2.2:8000/` (emulator localhost). VisualSearch still uses mock 2-second delay instead of real backend call. |
| **LocalizationApiClient not wired** | ❌ `ScannerCamera` captures → saves → but does NOT call backend. | The capture pipeline is fully wired up to the save step. The final upload → position → cleanup flow via `LocalizationApiClient.localizeImage()` is not connected. **Priority for Sprint 3 completion.** |
| **User geolocation blue dot — intermittent** | ⚠️ The My-Location blue dot may not appear on first launch. | [`MapFragment.enableMyLocation()`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt:317) uses `FusedLocationProviderClient.lastLocation` which returns `null` if no prior location is cached. Workaround planned: use `getCurrentLocation()` with `PRIORITY_HIGH_ACCURACY`. |
| **Category filter treats MONUMENT as "All"** | ⚠️ Minor UX bug (now fixed) | **FIXED in Sprint 3.** [`MainViewModel.filteredLocations`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:73) now correctly filters: `if (category != null) { result = result.filter { it.category == category } }`. No more MONUMENT-as-All exception. |
| **Analytics computed on allLocations changes** | ⚠️ Minor | [`analyticsData`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:128) combines `allLocations` with a dummy `MutableStateFlow(Unit)`. The Flow works but the `combine` with `Unit` is unconventional. |
| **Room-backed LocationRepository not implemented** | ⚠️ Data lost on app restart | [`MockLocationRepositoryImpl`](mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt) is in-memory only. All added/edited locations are lost when the process dies. Need `RoomLocationRepositoryImpl` implementing [`LocationRepository`](mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt) backed by Room DAO. |
| **MarkerItem.kt — potential dead code** | ⚠️ Low impact | The old [`MarkerItem`](mobile/android/app/src/main/java/com/navisense/model/MarkerItem.kt) model (transport-mode tags: Walking, Bicycle, Car) from Sprint 1 may be unused now. Audit and clean up. |
| **Filter transport chips (Walking/Bicycle/Car) still in strings.xml** | ⚠️ Cosmetic | Strings `filter_walking`, `filter_bicycle`, `filter_car` exist in [`strings.xml`](mobile/android/app/src/main/res/values/strings.xml:18) but are no longer used by any UI component. Clean up. |
| **16 KB page‑size alignment (Android 15)** | ✅ Fixed (verified) | CameraX 1.4.1 + `packaging { jniLibs { useLegacyPackaging = true } }` added in `build.gradle.kts`. |

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
| File | Purpose |
|---|---|
| [`mobile/android/settings.gradle.kts`](mobile/android/settings.gradle.kts) | Root project name "NaviSense", includes `:app` module |
| [`mobile/android/build.gradle.kts`](mobile/android/build.gradle.kts) | Project‑level: AGP 8.2.2, Kotlin 1.9.22 |
| [`mobile/android/gradle.properties`](mobile/android/gradle.properties) | AndroidX, parallel builds, JVM args |
| [`mobile/android/gradle/wrapper/gradle-wrapper.properties`](mobile/android/gradle/wrapper/gradle-wrapper.properties) | Gradle 8.5 distribution |
| [`mobile/android/app/build.gradle.kts`](mobile/android/app/build.gradle.kts) | App‑level: CameraX 1.4.1, Maps SDK 18.2.0, Retrofit 2.9, OkHttp 4.12, Navigation 2.7.7, Coil 2.5.0, Maps Utils KTx 5.0.0, Location 21.1.0, Room 2.6.1 + KSP, packaging block for 16 KB alignment |
| [`mobile/android/app/src/main/AndroidManifest.xml`](mobile/android/app/src/main/AndroidManifest.xml) | Permissions (INTERNET, CAMERA, FINE/COARSE LOCATION, ACCESS_NETWORK_STATE, WRITE_EXTERNAL_STORAGE maxSdkVersion=28), Maps API key via manifest placeholder `${MAPS_API_KEY}`, `NaviSenseApplication`, `localeConfig`, single-activity launcher |
| [`mobile/android/app/src/main/java/com/navisense/MainActivity.kt`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt) | Single Activity hosting `NavHostFragment` + `BottomNavigationView`. Static `switchLocale()` and `getCurrentLocaleCode()` helpers. |
| [`mobile/android/app/src/main/java/com/navisense/NaviSenseApplication.kt`](mobile/android/app/src/main/java/com/navisense/NaviSenseApplication.kt) | Custom `Application` subclass with lazy `AppDatabase` singleton. |
| [`mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt) | Shared `AndroidViewModel`: `allLocations`, `selectedCategory`, `searchQuery`, `showFavoritesOnly`, `visitedFilter`, `filteredLocations` (5-flow combine), `selectedRadiusKm`, `routeWaypoints`, `routePolylinePoints`, `mockMatchLocation`, `analyticsData` (incl. `favoriteCount`, `notFavoriteCount`, `districtCounts`). CRUD, search, filter toggle, route toggle/optimize, mock match, `detectDistrict()`. |
| [`mobile/android/app/src/main/java/com/navisense/model/AppLocation.kt`](mobile/android/app/src/main/java/com/navisense/model/AppLocation.kt) | `@Parcelize` data class: `id`, `title`, `description`, `latitude`, `longitude`, `category`, `imageUri`, `isVisited`, `isFavorite` |
| [`mobile/android/app/src/main/java/com/navisense/model/AppLocationCategory.kt`](mobile/android/app/src/main/java/com/navisense/model/AppLocationCategory.kt) | Enum: `MONUMENT`, `GROCERY`, `GAS_STATION`, `RESTAURANT`, `PHARMACY`, `NO_CATEGORY`. Companion: `names`, `fromKey()`, `markerHue()`, `chartColor()`. |
| [`mobile/android/app/src/main/java/com/navisense/model/MarkerItem.kt`](mobile/android/app/src/main/java/com/navisense/model/MarkerItem.kt) | `@Parcelize` data class: legacy model for Sprint 1 markers (transport-mode tags: Walking, Bicycle, Car). **Likely dead code.** |
| [`mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt`](mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt) | Repository interface: `getAllLocations()`, `getLocationById()`, `insertLocation()`, `updateLocation()`, `deleteLocation()`, `toggleVisited()`, `toggleFavorite()` |
| [`mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt`](mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt) | In-memory mock with 10 Kyiv landmarks as seed data. Includes `toggleFavorite()`. |
| [`mobile/android/app/src/main/java/com/navisense/data/local/AppDatabase.kt`](mobile/android/app/src/main/java/com/navisense/data/local/AppDatabase.kt) | Room database (version 2): `DeliveryHistory` + `SavedLocation` entities. Singleton via `getInstance()`. Destructive migration fallback. |
| [`mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistory.kt`](mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistory.kt) | Room entity: immutable log of completed delivery trips. |
| [`mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistoryDao.kt`](mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistoryDao.kt) | Room DAO: insert, getRecent, getByDateRange, deleteOlderThan (all Flow/suspend). |
| [`mobile/android/app/src/main/java/com/navisense/data/local/SavedLocation.kt`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocation.kt) | Room entity: user-saved favourite points with name, description, category, lat/lng, timestamp. |
| [`mobile/android/app/src/main/java/com/navisense/data/local/SavedLocationDao.kt`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocationDao.kt) | Room DAO: insert, update, delete, deleteById, getAll (Flow), getById. |
| [`mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt) | Map tab: Google Maps, search bar, category chips (programmatic), advanced filters (Visited 3-state, Favorites toggle), radius filter, language toggle, My-Location FAB with permission flow, marker rendering by category colour (visited→HUE_VIOLET), LocationDetailsBottomSheet on marker click, mock match marker drop |
| [`mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt) | Add/Edit Location tab: map picker with tap-to-select, title/description inputs, category dropdown (incl. "No Category"), photo attachment (gallery/camera), edit mode with full field prefill, save/update |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/AnalyticsFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/AnalyticsFragment.kt) | Analytics tab: pie chart + bar chart (4 bars) + district bar chart + total count |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/PieChartView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/PieChartView.kt) | Custom Canvas-drawn pie chart with legend |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/BarChartView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/BarChartView.kt) | Custom Canvas-drawn bar chart (Visited, Not Visited, Favorites, Others) |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictBarChartView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictBarChartView.kt) | Custom Canvas-drawn horizontal bar chart showing location counts per Kyiv district |
| [`mobile/android/app/src/main/java/com/navisense/ui/route/RouteBuilderFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/route/RouteBuilderFragment.kt) | Route Builder tab: map + waypoint list, Optimize Route (TSP heuristic), road-aware mock polyline, Google Maps external navigation with web fallback |
| [`mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt) | Visual Search tab: CameraX live preview + capture FAB, ScannerCamera integration, blur validation, TempScans save/cleanup, gallery upload, 2-second mock inference → Map marker drop |
| [`mobile/android/app/src/main/java/com/navisense/ui/details/LocationDetailsBottomSheet.kt`](mobile/android/app/src/main/java/com/navisense/ui/details/LocationDetailsBottomSheet.kt) | Bottom sheet: Coil image loading, title, category, coordinates, description, favorite heart toggle, visited toggle, edit button, delete button |
| [`mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt) | CameraX wrapper: ResolutionSelector, ImageCapture with MINIMIZE_LATENCY, Laplacian variance blur detection, `captureSharpImage()` callback, bind/unbind lifecycle, `shutdown()` |
| [`mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt`](mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt) | TempScans folder management, 50 MB free‑space check, UUID file naming, error logging, `clearTempScansFolder()` |
| [`mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt`](mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt) | Retrofit singleton: OkHttp client, retry logic (3 attempts, exponential backoff), `localizeImage()` suspend function |
| [`mobile/android/app/src/main/java/com/navisense/core/NaviSenseApi.kt`](mobile/android/app/src/main/java/com/navisense/core/NaviSenseApi.kt) | Retrofit interface: `uploadImage()` multipart, `PositionResponse`, `Landmark` data classes |
| [`mobile/android/app/src/main/res/layout/activity_main.xml`](mobile/android/app/src/main/res/layout/activity_main.xml) | NavHostFragment + BottomNavigationView |
| [`mobile/android/app/src/main/res/layout/fragment_map.xml`](mobile/android/app/src/main/res/layout/fragment_map.xml) | CoordinatorLayout: SupportMapFragment, search bar card, filter card (ChipGroup + advanced filters row + radius), language toggle button, My-Location FAB |
| [`mobile/android/app/src/main/res/layout/fragment_add_location.xml`](mobile/android/app/src/main/res/layout/fragment_add_location.xml) | Add/Edit Location form: map picker, text inputs, dropdown, photo button, save/update |
| [`mobile/android/app/src/main/res/layout/fragment_analytics.xml`](mobile/android/app/src/main/res/layout/fragment_analytics.xml) | ScrollView: PieChartView, BarChartView, DistrictBarChartView, total count card |
| [`mobile/android/app/src/main/res/layout/fragment_route_builder.xml`](mobile/android/app/src/main/res/layout/fragment_route_builder.xml) | Route Builder: map (top) + RecyclerView waypoint list + action buttons (Clear, Optimize, Start Navigation) |
| [`mobile/android/app/src/main/res/layout/fragment_visual_search.xml`](mobile/android/app/src/main/res/layout/fragment_visual_search.xml) | Visual Search: CameraX PreviewView + capture FAB + camera placeholder + upload button + full-screen loading overlay |
| [`mobile/android/app/src/main/res/layout/bottom_sheet_location_details.xml`](mobile/android/app/src/main/res/layout/bottom_sheet_location_details.xml) | Location details: ConstraintLayout, Coil image with placeholder card, title, coordinates, category, favorite heart icon, description, visited/edit/delete button row |
| [`mobile/android/app/src/main/res/navigation/nav_graph.xml`](mobile/android/app/src/main/res/navigation/nav_graph.xml) | Navigation graph: 5 fragment destinations (map, add, route, analytics, visual search) |
| [`mobile/android/app/src/main/res/menu/bottom_nav_menu.xml`](mobile/android/app/src/main/res/menu/bottom_nav_menu.xml) | Bottom nav: Map, Routes, Add, Analytics, Visual Search |
| [`mobile/android/app/src/main/res/values/strings.xml`](mobile/android/app/src/main/res/values/strings.xml) | English strings (EN) — all UI labels, toasts, filter labels, analytics titles |
| [`mobile/android/app/src/main/res/values-uk/strings.xml`](mobile/android/app/src/main/res/values-uk/strings.xml) | Ukrainian strings (UK) — full translation |
| [`mobile/android/app/src/main/res/values/colors.xml`](mobile/android/app/src/main/res/values/colors.xml) | Brand colours, marker colours, radius fill colour, chart colours (visited, not_visited, favorites, others) |
| [`mobile/android/app/src/main/res/values/themes.xml`](mobile/android/app/src/main/res/values/themes.xml) | Material3 Dark NoActionBar theme (`Theme.NaviSense`) + `Theme.NaviSense.BottomSheet` for bottom sheet dark overlay |
| [`mobile/android/app/src/main/res/xml/locales_config.xml`](mobile/android/app/src/main/res/xml/locales_config.xml) | Supported locales for per-app language preferences: `en`, `uk` |
| [`mobile/android/app/src/main/res/drawable/ic_search.xml`](mobile/android/app/src/main/res/drawable/ic_search.xml) | Search icon (used in map search bar) |
| [`mobile/android/app/src/main/res/drawable/ic_camera_capture.xml`](mobile/android/app/src/main/res/drawable/ic_camera_capture.xml) | Camera capture FAB icon |
| [`mobile/android/app/src/main/res/drawable/ic_delete.xml`](mobile/android/app/src/main/res/drawable/ic_delete.xml) | Delete action icon |
| [`mobile/android/app/src/main/res/drawable/ic_edit.xml`](mobile/android/app/src/main/res/drawable/ic_edit.xml) | Edit action icon |
| [`mobile/android/app/src/main/res/drawable/ic_heart_filled.xml`](mobile/android/app/src/main/res/drawable/ic_heart_filled.xml) | Favorite (filled heart) icon |
| [`mobile/android/app/src/main/res/drawable/ic_heart_outline.xml`](mobile/android/app/src/main/res/drawable/ic_heart_outline.xml) | Favorite (outline heart) icon |
| [`mobile/android/app/src/main/res/drawable/ic_visited.xml`](mobile/android/app/src/main/res/drawable/ic_visited.xml) | Visited toggle icon |
| [`mobile/android/app/proguard-rules.pro`](mobile/android/app/proguard-rules.pro) | Keep rules for Retrofit, Gson, Maps |

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

**Status:** ✅ Implemented (Room dependencies in `build.gradle.kts`, entities, DAOs, and `AppDatabase` exist on disk).

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

### Important Note

While Room infrastructure exists, the **primary UI data layer** (`LocationRepository` interface) is still backed by `MockLocationRepositoryImpl`. The Room tables (`saved_locations`, `delivery_history`) are currently **not wired** to the ViewModel — they exist as infrastructure ready for future integration.

---

## 9. Future Integration Notes (For Database Developer — Anya)

### 9.1 Replace MockLocationRepositoryImpl with Room

The `LocationRepository` interface is designed for a clean swap. To replace the mock:

1. **Create a Room entity** that mirrors `AppLocation`:
   ```kotlin
   @Entity(tableName = "locations")
   data class LocationEntity(
       @PrimaryKey(autoGenerate = true) val id: Int = 0,
       val title: String,
       val description: String,
       val latitude: Double,
       val longitude: Double,
       val category: String,
       val imageUri: String,
       val isVisited: Boolean = false,
       val isFavorite: Boolean = false
   )
   ```

2. **Create a DAO** with suspend/Flow functions matching the repository interface.

3. **Create `RoomLocationRepositoryImpl`** implementing `LocationRepository`:
   ```kotlin
   class RoomLocationRepositoryImpl(private val dao: LocationDao) : LocationRepository {
       override fun getAllLocations(): StateFlow<List<AppLocation>> =
           dao.getAllLocations().map { entities -> entities.map { it.toDomain() } }
               .stateIn(...)
       // ... implement remaining methods
   }
   ```

4. **Swap in MainViewModel:**
   ```kotlin
   // Change this one line:
   private val repository: LocationRepository = MockLocationRepositoryImpl()
   // To:
   private val repository: LocationRepository = RoomLocationRepositoryImpl(
       AppDatabase.getInstance(getApplication()).locationDao()
   )
   ```

**No ViewModel code, no UI code, and no Fragment code needs to change.**

### 9.2 Wire SavedLocation and DeliveryHistory

These tables are built and ready. Integration points:

- **SavedLocation:** Wire `SavedLocationDao.getAll()` into a new section of `AnalyticsData` or a dedicated "Saved Places" screen.
- **DeliveryHistory:** Wire `DeliveryHistoryDao.insert()` into the Route Builder's "Start Navigation" flow to log completed trips. Wire `getLatestDelivery()` into Analytics for a "Last Trip Summary" card.

### 9.3 Wire Backend ML Pipeline

The complete pipeline exists but is disconnected:

1. **Mobile side:** Replace `startMockSearch()` in [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt) with a call to [`LocalizationApiClient.localizeImage()`](mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt) passing the captured file.
2. **Backend side:** Already fully implemented (see [Section 10](#10-backend-api-reference)).
3. **Configuration:** Update `BuildConfig.BACKEND_URL` to point to the deployed backend.

---

## 10. Backend API Reference

### Python FastAPI Backend

**Location:** [`backend/`](backend/)  
**Docker:** [`backend/Dockerfile`](backend/Dockerfile)  

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Root welcome message |
| `GET` | `/api/v1/health` | Health check → `{"status": "ok"}` |
| `POST` | `/api/v1/position` | Upload JPEG (max 5 MB) → returns `{latitude, longitude, floor, confidence, nearest_landmarks}` |
| `POST` | `/api/v1/calibrate` | Placeholder for blur-detection calibration |

### ML Pipeline

- **Feature Extraction:** [`feature_extractor.py`](backend/app/feature_extractor.py) — DINOv2-base (ViT-B/14, 768-dim), L2 normalized.
- **Vector Search:** [`vector_db.py`](backend/app/vector_db.py) — FAISS `IndexFlatL2`, 1000 demo Kyiv landmarks.
- **Mock Fallback:** If torch/transformers/faiss are unavailable, auto-falls back to mock implementations returning random positions.

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
### 7.6 Sprint 3 Priority Items (Updated)

## 12. Known Issues & Gaps
1. **Wire LocalizationApiClient into VisualSearchFragment** — Replace the mock 2-second delay in [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt) with a real call to `LocalizationApiClient.localizeImage()`. The capture → blur check → save pipeline is already wired; only the upload → position → cleanup step remains.

| Issue | Impact | Status |
|-------|--------|--------|
| **MockLocationRepositoryImpl not connected to Room** | Location data is in-memory only; lost on app restart. Room infrastructure is built but `MainViewModel` still uses the mock. | ⚠️ Pending |
| **Backend not deployed** | `LocalizationApiClient` cannot connect. Backend code is runnable locally but no cloud host configured. | ❌ |
| **My-Location blue dot intermittent** | `FusedLocationProviderClient.lastLocation` returns `null` on first launch if no cached location. Should use `getCurrentLocation(PRIORITY_HIGH_ACCURACY)`. | ⚠️ |
| **Analytics `combine` with `Unit`** | `analyticsData` combines `allLocations` with `MutableStateFlow(Unit)`. Works but unconventional. | ⚠️ Minor |
| **16 KB page-size alignment** | CameraX 1.4.1 + `useLegacyPackaging = true` in `build.gradle.kts`. Verified fix for Android 15. | ✅ Fixed |
| **Visual Search is mock** | No actual ML inference on device; mock drops random marker. Backend pipeline ready but unwired. | ⚠️ Sprint 3 |

---
3. **Create RoomLocationRepositoryImpl** — Implement [`LocationRepository`](mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt) backed by Room DAO (using `SavedLocation` and/or `DeliveryHistory`). Swap in [`MainViewModel`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:39) to replace `MockLocationRepositoryImpl` for persistent storage.

## 13. Appendix: Complete File Inventory

### Android Mobile App
5. **Remove dead code** — The original `MarkerItem` model and the unused transport-mode strings (`filter_walking`, `filter_bicycle`, `filter_car`) from Sprint 1 should be audited and cleaned up.

| File | Purpose |
|------|---------|
| [`mobile/android/settings.gradle.kts`](mobile/android/settings.gradle.kts) | Root project config |
| [`mobile/android/build.gradle.kts`](mobile/android/build.gradle.kts) | Project-level: AGP 8.2.2, Kotlin 1.9.22 |
| [`mobile/android/gradle.properties`](mobile/android/gradle.properties) | AndroidX, parallel builds, JVM args |
| [`mobile/android/app/build.gradle.kts`](mobile/android/app/build.gradle.kts) | App-level: all dependencies, Maps API key injection, Room KSP, packaging config |
| [`mobile/android/app/src/main/AndroidManifest.xml`](mobile/android/app/src/main/AndroidManifest.xml) | Permissions, Maps API key meta-data, activity declaration |
| [`mobile/android/app/src/main/java/com/navisense/MainActivity.kt`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt) | Single Activity host, NavHostFragment + BottomNavigation, locale helpers |
| [`mobile/android/app/src/main/java/com/navisense/NaviSenseApplication.kt`](mobile/android/app/src/main/java/com/navisense/NaviSenseApplication.kt) | Application class, lazy AppDatabase singleton |
| [`mobile/android/app/src/main/java/com/navisense/model/AppLocation.kt`](mobile/android/app/src/main/java/com/navisense/model/AppLocation.kt) | Core @Parcelize data class |
| [`mobile/android/app/src/main/java/com/navisense/model/AppLocationCategory.kt`](mobile/android/app/src/main/java/com/navisense/model/AppLocationCategory.kt) | Category enum with marker hues and chart colors |
| [`mobile/android/app/src/main/java/com/navisense/model/MarkerItem.kt`](mobile/android/app/src/main/java/com/navisense/model/MarkerItem.kt) | Legacy model (Sprint 1) — may be dead code |
| [`mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt`](mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt) | Repository interface: CRUD + toggleVisited + toggleFavorite |
| [`mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt`](mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt) | In-memory mock with 10 Kyiv landmarks |
| [`mobile/android/app/src/main/java/com/navisense/data/local/AppDatabase.kt`](mobile/android/app/src/main/java/com/navisense/data/local/AppDatabase.kt) | Room database singleton (v2, destructive migration) |
| [`mobile/android/app/src/main/java/com/navisense/data/local/SavedLocation.kt`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocation.kt) | Room entity: saved favourite points |
| [`mobile/android/app/src/main/java/com/navisense/data/local/SavedLocationDao.kt`](mobile/android/app/src/main/java/com/navisense/data/local/SavedLocationDao.kt) | Room DAO: saved locations CRUD |
| [`mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistory.kt`](mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistory.kt) | Room entity: delivery trip log |
| [`mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistoryDao.kt`](mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistoryDao.kt) | Room DAO: delivery history queries |
| [`mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt) | Shared ViewModel: all state flows, filtering, analytics, route optimisation |
| [`mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt) | Map screen: markers, filters, search, language toggle |
| [`mobile/android/app/src/main/java/com/navisense/ui/details/LocationDetailsBottomSheet.kt`](mobile/android/app/src/main/java/com/navisense/ui/details/LocationDetailsBottomSheet.kt) | Location details: visited, favorite, edit, delete |
| [`mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt) | Add/Edit location: map picker, form, photo, save |
| [`mobile/android/app/src/main/java/com/navisense/ui/route/RouteBuilderFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/route/RouteBuilderFragment.kt) | Route builder: waypoints, TSP optimisation, polyline, navigation |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/AnalyticsFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/AnalyticsFragment.kt) | Analytics screen: pie, bar, district charts |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/PieChartView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/PieChartView.kt) | Custom Canvas pie chart |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/BarChartView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/BarChartView.kt) | Custom Canvas vertical bar chart |
| [`mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictBarChartView.kt`](mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictBarChartView.kt) | Custom Canvas horizontal bar chart (per district) |
| [`mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt) | Visual search: CameraX, gallery, mock ML, TempScans cleanup |
| [`mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt) | CameraX wrapper: capture, blur detection, JPEG compression |
| [`mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt`](mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt) | TempScans management, storage checks, error logging, multipart prep |
| [`mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt`](mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt) | Retrofit client: retry logic, image upload |
| [`mobile/android/app/src/main/java/com/navisense/core/NaviSenseApi.kt`](mobile/android/app/src/main/java/com/navisense/core/NaviSenseApi.kt) | Retrofit interface: uploadImage(), PositionResponse, Landmark |
| [`mobile/android/app/src/main/res/navigation/nav_graph.xml`](mobile/android/app/src/main/res/navigation/nav_graph.xml) | Navigation graph with 5 destinations |
| [`mobile/android/app/src/main/res/menu/bottom_nav_menu.xml`](mobile/android/app/src/main/res/menu/bottom_nav_menu.xml) | Bottom navigation: Map, Routes, Add, Analytics, Visual Search |
| [`mobile/android/app/src/main/res/values/strings.xml`](mobile/android/app/src/main/res/values/strings.xml) | English string resources |
| [`mobile/android/app/src/main/res/values-uk/strings.xml`](mobile/android/app/src/main/res/values-uk/strings.xml) | Ukrainian string resources |
| [`mobile/android/app/src/main/res/values/colors.xml`](mobile/android/app/src/main/res/values/colors.xml) | Brand colours, marker colours, radius fill |
| [`mobile/android/app/src/main/res/values/themes.xml`](mobile/android/app/src/main/res/values/themes.xml) | Material3 Light NoActionBar theme |
| [`mobile/android/app/src/main/res/xml/locales_config.xml`](mobile/android/app/src/main/res/xml/locales_config.xml) | Per-App Language Preferences config |
6. **Unit & integration tests** — Add `pytest` tests for backend endpoints and `JUnit`/`Espresso` tests for Android.

### Backend (Python)

| File | Purpose |
|------|---------|
| [`backend/app/main.py`](backend/app/main.py) | FastAPI application: 4 endpoints, file validation, mock fallback |
| [`backend/app/feature_extractor.py`](backend/app/feature_extractor.py) | DINOv2 feature extraction (768-dim, L2 normalized) |
| [`backend/app/vector_db.py`](backend/app/vector_db.py) | FAISS vector database (IndexFlatL2, demo index) |
| [`backend/requirements.txt`](backend/requirements.txt) | Python dependencies |
| [`backend/Dockerfile`](backend/Dockerfile) | Docker image definition |
| [`backend/README.md`](backend/README.md) | Backend documentation |
---

## 8. Files Not Currently on Disk

*(None — all previously missing files have been created in Sprint 3.)*

| Previously Missing File | Status |
|---|---|
| `mobile/android/app/src/main/java/com/navisense/NaviSenseApplication.kt` | ✅ Created |
| `mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistory.kt` | ✅ Created |
| `mobile/android/app/src/main/java/com/navisense/data/local/DeliveryHistoryDao.kt` | ✅ Created |
| `mobile/android/app/src/main/java/com/navisense/data/local/SavedLocation.kt` | ✅ Created |
| `mobile/android/app/src/main/java/com/navisense/data/local/SavedLocationDao.kt` | ✅ Created |
| `mobile/android/app/src/main/java/com/navisense/data/local/AppDatabase.kt` | ✅ Created |
| `mobile/android/app/src/main/res/xml/locales_config.xml` | ✅ Created |
| `mobile/android/app/src/main/res/drawable/ic_camera_capture.xml` | ✅ Created |
| `mobile/android/app/src/main/res/drawable/ic_delete.xml` | ✅ Created |
| `mobile/android/app/src/main/res/drawable/ic_edit.xml` | ✅ Created |
| `mobile/android/app/src/main/res/drawable/ic_heart_filled.xml` | ✅ Created |
| `mobile/android/app/src/main/res/drawable/ic_heart_outline.xml` | ✅ Created |
| `mobile/android/app/src/main/res/drawable/ic_visited.xml` | ✅ Created |
| `mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictBarChartView.kt` | ✅ Created |

---

*This document is the single source of truth for the NaviSense project. Any architectural, data model, or validation changes must be reflected here before implementation.*
