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

- **Single Activity** ([`MainActivity.kt`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt)) hosts a [`NavHostFragment`](mobile/android/app/src/main/res/layout/activity_main.xml:12) with 5 bottom-navigation destinations.
- **Shared ViewModel** ([`MainViewModel`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt)) is scoped to the Activity, meaning all fragments share the same instance and react to the same state flows.
- **Repository Pattern** — [`LocationRepository`](mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt) is an interface. The current implementation [`MockLocationRepositoryImpl`](mobile/android/app/src/main/java/com/navisense/data/MockLocationRepositoryImpl.kt) uses an in-memory `MutableStateFlow<List<AppLocation>>`. A Room-backed implementation can be swapped in without changing any ViewModel or UI code (see [Section 9](#9-future-integration-notes-for-database-developer--anya)).
- **Navigation Graph** — defined in [`nav_graph.xml`](mobile/android/app/src/main/res/navigation/nav_graph.xml) with 5 fragment destinations.

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

        // 1. Category filter
        if (category != null) {
            result = result.filter { it.category == category }
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

## 12. Known Issues & Gaps

| Issue | Impact | Status |
|-------|--------|--------|
| **MockLocationRepositoryImpl not connected to Room** | Location data is in-memory only; lost on app restart. Room infrastructure is built but `MainViewModel` still uses the mock. | ⚠️ Pending |
| **Backend not deployed** | `LocalizationApiClient` cannot connect. Backend code is runnable locally but no cloud host configured. | ❌ |
| **My-Location blue dot intermittent** | `FusedLocationProviderClient.lastLocation` returns `null` on first launch if no cached location. Should use `getCurrentLocation(PRIORITY_HIGH_ACCURACY)`. | ⚠️ |
| **Analytics `combine` with `Unit`** | `analyticsData` combines `allLocations` with `MutableStateFlow(Unit)`. Works but unconventional. | ⚠️ Minor |
| **16 KB page-size alignment** | CameraX 1.4.1 + `useLegacyPackaging = true` in `build.gradle.kts`. Verified fix for Android 15. | ✅ Fixed |
| **Visual Search is mock** | No actual ML inference on device; mock drops random marker. Backend pipeline ready but unwired. | ⚠️ Sprint 3 |

---

## 13. Appendix: Complete File Inventory

### Android Mobile App

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

*This document is the single source of truth for the NaviSense project. Any architectural, data model, or validation changes must be reflected here before implementation.*
