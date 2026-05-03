# NaviSense MVP — Single Source of Truth

## 1. Project Goal
NaviSense is a visual positioning mobile application designed for couriers operating in GPS‑denied urban environments (e.g., indoor warehouses, dense urban canyons, underground facilities). The MVP provides real‑time location estimation using a single camera frame, without relying on GPS signals or continuous video streaming.

**Core Value Proposition:**
- Deliver sub‑meter positional accuracy in environments where GPS is unavailable or unreliable.
- Enable couriers to navigate complex indoor spaces (shopping malls, office towers, underground parking) with visual cues.
- Minimize latency and battery consumption by using single‑frame captures instead of continuous video.

---

## 2. Tech Stack (Current & Planned)

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

### Backend
- **Runtime:** Python 3.10+
- **Web Framework:** FastAPI (ASGI, automatic OpenAPI docs at `/docs`)
- **ML/DL Stack:**
  - PyTorch 2.11.0 (with GPU support if available)
  - Hugging Face Transformers 4.35.0 (DINOv2‑base model for feature extraction)
    - Model variant: `facebook/dinov2‑base` (ViT‑B/14)
    - Input resolution: 224×224 pixels, normalized with ImageNet mean/std
    - Output feature dimension: 768‑dimensional vector, L2-normalized
    - Inference device: GPU if available, otherwise CPU with FP16 precision
  - FAISS (cpu 1.13.2) for vector search
    - Index type: `IndexFlatL2` (exact L2 distance) for up to 100k landmarks; `IndexIVFFlat` for larger datasets
    - Dimension: 768 (matches DINOv2 output)
    - Metric: L2 (Euclidean) distance
    - Index built offline from landmark feature vectors; stored as binary file loaded at startup
  - **Mock fallback mode** — if torch/transformers/faiss are unavailable, the backend auto-falls back to mock implementations returning random positions
- **Utilities:**
  - Pillow/PIL for image preprocessing
  - NumPy, SciPy for numerical operations
  - Uvicorn as ASGI server
  - python-multipart for file uploads
  - pydantic for data validation
  - python-dotenv for environment configuration
  - httpx for async HTTP testing
  - pytest + pytest-asyncio for testing
- **API Endpoints:**
  - `GET /` — root welcome message
  - `GET /api/v1/health` — returns `{"status": "ok"}` if backend is ready.
  - `POST /api/v1/position` — upload a JPEG image (max 5 MB); returns JSON with `latitude`, `longitude`, `floor`, `confidence`, `nearest_landmarks`.
  - `POST /api/v1/calibrate` — (placeholder) upload a calibration image to adjust blur‑detection threshold; returns `{"message": "Calibration endpoint (not implemented)"}`.

### Infrastructure & DevOps
- **Version Control:** Git with GitHub Gitflow branching model
- **CI/CD:** GitHub Actions (build, test, deploy) — *(planned)*
- **Containerization:** Docker (see [`backend/Dockerfile`](backend/Dockerfile))
- **Database:** PostgreSQL 14+ (for metadata, user sessions, landmark catalog) — *(planned)*
- **Cloud Provider:** AWS / GCP (TBD)

---

## 3. Architecture — Current State

The app has evolved from a single-screen MVP into a multi-tab Location Management application with a clean fragment-based architecture.

```mermaid
graph TD
    subgraph "Mobile App (Navigation Component)"
        A[MainActivity] --> B[NavHostFragment]
        B --> C[MapFragment]
        B --> D[RouteBuilderFragment]
        B --> E[AddLocationFragment]
        B --> F[AnalyticsFragment]
        B --> G[VisualSearchFragment]
    end

    subgraph "Shared ViewModel"
        H[MainViewModel] --> I[LocationRepository]
        I --> J[MockLocationRepositoryImpl]
        H --> K[analyticsData]
        H --> L[routeWaypoints & routePolylinePoints]
        H --> M[filteredLocations]
        H --> N[searchQuery / showFavoritesOnly / visitedFilter]
    end

    subgraph "Room Database (MVP)"
        O[AppDatabase] --> P[DeliveryHistoryDao]
        O --> Q[SavedLocationDao]
    end

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

### Tab Overview

| # | Tab | Fragment | Description |
|---|-----|----------|-------------|
| 1 | Map (Home) | [`MapFragment`](mobile/android/app/src/main/java/com/navisense/ui/map/MapFragment.kt) | Full‑screen Google Map with **search bar** (fuzzy across title/description/category), **category filter chips** (All, Monument, Grocery, etc.) in a horizontal scroll, **advanced filter buttons** (Visited 3-state toggle, Favorites toggle), radius filter (Off → 1/2/5/10 km circle overlay), **language toggle** (EN/UK via `AppCompatDelegate.setApplicationLocales()`), My‑Location FAB with runtime permission flow, location markers coloured by category (visited → HUE_VIOLET), mock match marker drop |
| 2 | Routes | [`RouteBuilderFragment`](mobile/android/app/src/main/java/com/navisense/ui/route/RouteBuilderFragment.kt) | Split view: map (top) + selectable waypoint list (bottom). Polyline connects selected waypoints with road‑aware mock interpolation. **Optimize Route button** reorders middle waypoints using nearest-neighbour TSP heuristic. "Start Navigation" launches Google Maps external app (or web fallback) to final destination. |
| 3 | Add (+) | [`AddLocationFragment`](mobile/android/app/src/main/java/com/navisense/ui/add/AddLocationFragment.kt) | Map picker + form (title, description, category dropdown with "No Category" option, photo attachment from gallery/camera). **Edit mode** supported via `newInstance()` with pre-filled fields, preserves `isVisited`/`isFavorite` flags. |
| 4 | Analytics | [`AnalyticsFragment`](mobile/android/app/src/main/java/com/navisense/ui/analytics/AnalyticsFragment.kt) | Custom Canvas-drawn [`PieChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/PieChartView.kt) (category distribution) + [`BarChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/BarChartView.kt) (Visited, Not Visited, Favorites, Others) + [`DistrictBarChartView`](mobile/android/app/src/main/java/com/navisense/ui/analytics/DistrictBarChartView.kt) (locations per Kyiv district) + total location count card. |
| 5 | Visual Search | [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt) | **CameraX live preview** with single-frame capture, blur validation via `ScannerCamera`, temporary file storage via `FileManagerService`, and mock visual positioning. Camera permission requested at runtime. Also supports gallery upload. After 2‑second mock inference → navigates to Map and drops a yellow mock-match marker. **TempScans folder wiped after search completes.** |

### Full Vision Flow (Camera → Backend → Position)

```mermaid
graph TD
    A[Mobile: Single Frame Capture] --> B{Edge Validation<br>Blur Detection};
    B -- Fail --> C[Discard Frame];
    B -- Pass --> D[Check Free Space >50MB];
    D -- Insufficient --> E[Log Error & Abort];
    D -- Sufficient --> F[Save to TempScans Folder];
    F --> G[Send Frame to Backend];
    G --> H[Backend: Preprocess Image];
    H --> I[Extract Features with DINOv2];
    I --> J[FAISS Vector Search];
    J --> K[Retrieve Top‑K Landmarks];
    K --> L[Compute Weighted Position];
    L --> M[Return Position to Mobile];
    M --> N[Delete Temporary Image];
    N --> O[Update UI with Position];
```

> **Current state:** Steps 1–2 (CameraX + blur detection) are implemented and **wired into [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt)**. Steps 3–9 are fully implemented in the backend but the mobile → backend integration (`LocalizationApiClient`) is still **not wired** — the fragment uses a mock 2-second delay instead.

---

## 4. Database Schema (Planned — PostgreSQL)

```sql
-- Registered couriers
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    hashed_password VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP WITH TIME ZONE
);

-- Active sessions (JWT tokens optional)
CREATE TABLE sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    device_id VARCHAR(255),
    started_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE,
    is_active BOOLEAN DEFAULT TRUE
);

-- Known landmarks (pre‑indexed visual features)
CREATE TABLE landmarks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    building_id UUID NOT NULL,
    floor INTEGER NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    altitude DOUBLE PRECISION,
    feature_vector BYTEA,
    image_path VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(building_id, floor, latitude, longitude)
);

-- Historical scans (for analytics and retraining)
CREATE TABLE scans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    session_id UUID REFERENCES sessions(id) ON DELETE SET NULL,
    landmark_id UUID REFERENCES landmarks(id) ON DELETE SET NULL,
    image_path VARCHAR(512) NOT NULL,
    estimated_latitude DOUBLE PRECISION,
    estimated_longitude DOUBLE PRECISION,
    estimated_floor INTEGER,
    confidence_score FLOAT,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_from_device BOOLEAN DEFAULT TRUE
);

-- Buildings & floor plans
CREATE TABLE buildings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    address TEXT,
    geo_boundary POLYGON,
    map_tile_url_template VARCHAR(512)
);
```

---

## 5. Strict Validation Rules (Planned for Full System)

### 5.1 Low Latency Requirement
- **Single‑frame only:** The mobile app must never stream video to the backend. Each positioning request corresponds to exactly one captured image.
- **End‑to‑end latency target:** < 2 seconds (from capture to position display), excluding network round‑trip.
- **On‑device preprocessing:** Resize and compression must be performed before transmission; the uploaded JPEG shall not exceed 500 KB.

### 5.2 File I/O & Storage Policies
1. **TempScans Folder:** All temporary images must be saved exclusively to `[App Internal Storage]/TempScans/`. No other directory may be used for this purpose.
2. **Free‑space Check:** Before any file write, the app must verify that at least **50 MB** of free space is available on the device's internal storage. If the check fails, the operation is aborted and an error is appended to `error_logs.txt`.
3. **Immediate Deletion:** After successful backend transmission (HTTP 200 response), the temporary image file must be deleted **before** updating the UI. If transmission fails, the file may be kept for retry (max 3 attempts) but must be deleted after the final failure.
4. **No Persistence:** No captured image may remain on the device for longer than 5 minutes, regardless of success or failure.

### 5.3 Edge Validation (Blur Detection)
- Every captured frame must undergo a blur‑detection test before hitting the file system or network.
- **Algorithm:** Compute the Laplacian variance of the grayscale version of the image. If the variance is below a calibrated threshold (default: 100.0), the frame is considered too blurry and is discarded.
- **Performance:** The blur‑detection routine must complete in < 100 ms on a mid‑range Android device.

### 5.4 Error Handling & Logging
- **Error Logs:** All runtime errors (I/O, network, validation failures) must be appended to `error_logs.txt` in the app's internal storage, with a timestamp, error code, and brief description.
- **Retry Logic:** Network requests that timeout or return 5xx status codes are retried up to 3 times with exponential backoff (1s → 2s → 4s).
- **User Feedback:** Non‑technical errors (e.g., "image too blurry", "insufficient storage") are displayed as user‑friendly toasts; technical errors are logged silently.

### 5.5 Security & Privacy
- **Image Transmission:** All images are transmitted over HTTPS with certificate pinning *(planned — HTTP used in development)*.
- **No Personal Data:** The app must not capture or transmit any personally identifiable information (PII) embedded in the image. If face‑like regions are detected (optional), the image is discarded.
- **Local Storage:** The TempScans folder is located in the app's internal storage, which is sandboxed and inaccessible to other apps.

---

## 6. Development Workflow (Gitflow)

- **Main Branches:**
  - `main` – production‑ready code, tagged releases.
  - `develop` – integration branch for completed features.
- **Feature Branches:** `feature/*` branched from `develop`. Must pass code review and CI before merging.
- **Release Branches:** `release/*` for final testing, version bumping, and documentation updates.
- **Hotfix Branches:** `hotfix/*` branched from `main` for urgent production fixes.

**CI/CD Pipeline (Planned):**
1. On push to `feature/*` – run unit tests (Android: Gradle, Backend: pytest).
2. On merge to `develop` – build Docker images, run integration tests.
3. On merge to `main` – deploy to staging environment (auto) and optionally to production (manual approval).

---

## 7. Current Implementation Status

### 7.1 Sprint Overview

The project has evolved through three major phases:

- **Sprint 1 (Complete):** Delivered a single-screen UI shell with Google Maps integration, runtime permission handling, mock marker placement, CameraX capture module, FileManagerService, LocalizationApiClient, and bilingual (EN/UK) resources. Much of this remained unwired.

- **Sprint 2 (Complete):** Refactored the monolithic `MainActivity` into a **5-tab Navigation Component** architecture. Implemented `MapFragment`, `AddLocationFragment`, `RouteBuilderFragment`, `AnalyticsFragment`, `VisualSearchFragment`, shared `MainViewModel` with `LocationRepository` pattern, `AppLocation`/`AppLocationCategory` models, custom chart views, and mock visual search flow. Backend received full ML pipeline (DINOv2 + FAISS) with mock fallback mode and Docker support.

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

#### Sprint 1 Features (Carried Forward)

| Feature | Status | Details |
|---|---|---|
| **Google Maps Display** | ✅ Working | `SupportMapFragment` renders map tiles in `MapFragment`. Default camera centres on Kyiv (50.4501, 30.5234) at zoom 13. |
| **Map UI Controls** | ✅ Working | Zoom controls (`+`/`–` buttons) enabled. Map toolbar disabled for MVP simplicity. |
| **CameraX ScannerCamera** | ✅ **Wired into VisualSearchFragment** | [`ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt): `ResolutionSelector`, Laplacian variance blur detection (threshold 100.0), `captureSharpImage()` callback, `ImageTooBlurryException`. **Now instantiated in [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt)** with live PreviewView. |
| **FileManagerService** | ✅ **Wired into VisualSearchFragment** | [`FileManagerService.kt`](mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt): TempScans folder, 50 MB free‑space check, UUID file naming, error logging, `prepareImagePart()` for Retrofit multipart upload. **Now used by [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt)** for saving captured images and cleaning up TempScans after search. |
| **LocalizationApiClient** | ✅ Implemented (not wired) | [`LocalizationApiClient.kt`](mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt): Retrofit client, OkHttp timeouts (15s/30s/30s), retry logic (3 attempts with exponential backoff), file cleanup after success/failure. **Not called from any fragment** — VisualSearch still uses mock 2-second delay. |
| **NaviSenseApi** | ✅ Implemented (not wired) | [`NaviSenseApi.kt`](mobile/android/app/src/main/java/com/navisense/core/NaviSenseApi.kt): Retrofit interface with `uploadImage()` multipart endpoint. `PositionResponse` and `Landmark` data classes. |
| **Bilingual UI** | ✅ Working | Full English (`values/`) and Ukrainian (`values‑uk/`) string resources for all UI labels and messages. **Runtime switching** via language toggle button on Map. |

#### Sprint 2 Features (Complete)

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

### 7.4 Files Summary

#### Mobile (Android)

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

#### Backend (Python)

| File | Purpose |
|---|---|
| [`backend/app/__init__.py`](backend/app/__init__.py) | Package marker (empty) |
| [`backend/app/main.py`](backend/app/main.py) | FastAPI application: 4 endpoints, file validation, component lazy-loading, mock fallback |
| [`backend/app/feature_extractor.py`](backend/app/feature_extractor.py) | DINOv2FeatureExtractor: model loading, 768-dim feature extraction, L2 normalization |
| [`backend/app/vector_db.py`](backend/app/vector_db.py) | FAISS VectorDatabase: index creation (flat_l2 / ivf_flat), add/search/save/load, demo index generation |
| [`backend/requirements.txt`](backend/requirements.txt) | Python dependencies: fastapi, uvicorn, torch, transformers, faiss-cpu, pillow, numpy, etc. |
| [`backend/Dockerfile`](backend/Dockerfile) | Docker image: python:3.10-slim, system deps for torch/faiss, pip installs, exposes 8000 |
| [`backend/README.md`](backend/README.md) | Backend documentation: architecture, installation, API, Docker, troubleshooting |

### 7.5 Build & Run Instructions

```bash
# ── Android App ──────────────────────────────────────────────────────

# 1. Configure your Google Maps API key
#    - Add to ROOT local.properties: MAPS_API_KEY=AIzaSy...your_real_key...
#    - Ensure "Maps SDK for Android" is ENABLED in Google Cloud Console

# 2. Clean build (required after cache changes, e.g. CameraX upgrade)
cd mobile/android
./gradlew clean assembleDebug

# 3. Install on device / emulator (API 26+)
./gradlew installDebug

# ── Backend (Python) ─────────────────────────────────────────────────

# 1. Create and activate virtual environment
cd backend
python -m venv venv
# On Windows: venv\Scripts\activate
# On macOS/Linux: source venv/bin/activate

# 2. Install dependencies
pip install -r requirements.txt

# 3. Run in development mode (with mock fallback if torch/faiss missing)
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000

# 4. API docs available at http://localhost:8000/docs

# ── Backend (Docker) ─────────────────────────────────────────────────

cd backend
docker build -t navisense-backend .
docker run -p 8000:8000 navisense-backend
```

### 7.6 Sprint 3 Priority Items (Updated)

1. **Wire LocalizationApiClient into VisualSearchFragment** — Replace the mock 2-second delay in [`VisualSearchFragment`](mobile/android/app/src/main/java/com/navisense/ui/search/VisualSearchFragment.kt) with a real call to `LocalizationApiClient.localizeImage()`. The capture → blur check → save pipeline is already wired; only the upload → position → cleanup step remains.

2. **Deploy ML backend** — Deploy FastAPI service (Docker) to a cloud host (AWS/GCP), update `BACKEND_URL` via `BuildConfig.BACKEND_URL` or a runtime configuration.

3. **Create RoomLocationRepositoryImpl** — Implement [`LocationRepository`](mobile/android/app/src/main/java/com/navisense/data/LocationRepository.kt) backed by Room DAO (using `SavedLocation` and/or `DeliveryHistory`). Swap in [`MainViewModel`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt:39) to replace `MockLocationRepositoryImpl` for persistent storage.

4. **Fix My-Location blue dot reliability** — Replace `FusedLocationProviderClient.lastLocation` with `getCurrentLocation(PRIORITY_HIGH_ACCURACY, ...)` for more reliable first-launch positioning.

5. **Remove dead code** — The original `MarkerItem` model and the unused transport-mode strings (`filter_walking`, `filter_bicycle`, `filter_car`) from Sprint 1 should be audited and cleaned up.

6. **Unit & integration tests** — Add `pytest` tests for backend endpoints and `JUnit`/`Espresso` tests for Android.

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

*This document is the single source of truth for the NaviSense MVP. Any architectural or validation changes must be reflected here before implementation.*
