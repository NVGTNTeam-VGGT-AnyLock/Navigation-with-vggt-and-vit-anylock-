# NaviSense MVP — Single Source of Truth

## 1. Project Goal
NaviSense is a visual positioning mobile application designed for couriers operating in GPS‑denied urban environments (e.g., indoor warehouses, dense urban canyons, underground facilities). The MVP provides real‑time location estimation using a single camera frame, without relying on GPS signals or continuous video streaming.

**Core Value Proposition:**
- Deliver sub‑meter positional accuracy in environments where GPS is unavailable or unreliable.
- Enable couriers to navigate complex indoor spaces (shopping malls, office towers, underground parking) with visual cues.
- Minimize latency and battery consumption by using single‑frame captures instead of continuous video.

---

## 2. Tech Stack (Planned Full Stack)

### Mobile Frontend
- **Platform:** Native Android (minimum SDK 26, target SDK 34)
- **Language:** Kotlin (no cross‑platform frameworks)
- **Key Libraries:**
  - **CameraX 1.4.1** for single‑frame image capture
    - Resolution selector targeting 1080×1920 (portrait)
    - Capture mode: `MINIMIZE_LATENCY`
    - Built‑in `ImageProxy.toBitmap()` (CameraX 1.4+)
  - **Google Maps SDK (play-services-maps:18.2.0)** for map display
  - **Retrofit2 + OkHttp4** for REST communication
    - Base URL configurable via `BuildConfig.BACKEND_URL`
    - Timeout: 10 seconds connect, 30 seconds read
    - Multipart file upload with JPEG compression quality 85%
  - **Room** for local caching *(planned — not yet implemented)*
  - **OpenCV‑Android** for lightweight blur‑detection *(not used; custom Kotlin Laplacian variance used instead)*

### Backend
- **Runtime:** Python 3.10+
- **Web Framework:** FastAPI (ASGI, automatic OpenAPI docs)
- **ML/DL Stack:**
  - PyTorch 2.0+ (with GPU support if available)
  - Hugging Face Transformers (DINOv2‑base model for feature extraction)
    - Model variant: `facebook/dinov2‑base` (ViT‑B/14)
    - Input resolution: 224×224 pixels, normalized with ImageNet mean/std
    - Output feature dimension: 768‑dimensional vector
    - Inference device: GPU if available, otherwise CPU with FP16 precision
  - FAISS (Facebook AI Similarity Search) for vector search
    - Index type: `IndexFlatL2` (exact L2 distance) for up to 100k landmarks; `IndexIVFFlat` for larger datasets
    - Dimension: 768 (matches DINOv2 output)
    - Metric: L2 (Euclidean) distance
    - Index built offline from landmark feature vectors; stored as binary file loaded at startup
- **Utilities:**
  - Pillow/PIL for image preprocessing
  - NumPy, SciPy for numerical operations
  - Uvicorn as ASGI server
  - Redis (optional) for caching feature vectors
- **API Endpoints:**
  - `POST /api/v1/position` – upload a JPEG image; returns JSON with `latitude`, `longitude`, `floor`, `confidence`.
  - `GET /api/v1/health` – returns `{"status": "ok"}` if backend is ready.
  - `POST /api/v1/calibrate` – (optional) upload a calibration image to adjust blur‑detection threshold.

### Infrastructure & DevOps
- **Version Control:** Git with GitHub Gitflow branching model
- **CI/CD:** GitHub Actions (build, test, deploy) — *(planned)*
- **Containerization:** Docker, Docker‑Compose for local development
- **Database:** PostgreSQL 14+ (for metadata, user sessions, landmark catalog) — *(planned — backend not yet deployed)*
- **Cloud Provider:** AWS / GCP (TBD)

---

## 3. Architecture Flow (Full Vision)

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
    K --> L[Compute Position Estimate];
    L --> M[Return Position to Mobile];
    M --> N[Delete Temporary Image];
    N --> O[Update UI with Position];
```

**Detailed Steps (Full Vision):**

1. **Frame Capture:** Mobile camera captures a single RGB frame (resolution 1080×1920) via CameraX.
2. **Edge Validation:** A lightweight blur‑detection algorithm (Laplacian variance) runs on‑device. If the frame is too blurry, it is discarded immediately; no file I/O or network call occurs.
3. **Storage Check:** Before writing to disk, the app verifies that at least 50 MB of free space is available on the device's internal storage. If not, the operation is aborted and an error is logged.
4. **Temporary Storage:** The validated frame is saved as a JPEG file in `[App Internal Storage]/TempScans/` with a UUID filename.
5. **Network Transmission:** The file is uploaded via a multipart POST request to `/api/v1/position` (FastAPI endpoint).
6. **Backend Processing:**
   - Image is resized and normalized to match DINOv2 input requirements (224×224).
   - DINOv2‑base extracts a 768‑dimensional feature vector.
   - The vector is searched against a pre‑built FAISS index of landmark feature vectors (landmarks are geo‑referenced images of the environment).
   - The top‑K nearest neighbours are retrieved, and a weighted average of their coordinates yields the estimated position.
7. **Response:** The estimated (latitude, longitude, floor) is returned as JSON.
8. **Cleanup:** Upon successful receipt of the response, the mobile app deletes the temporary JPEG file. If the transmission fails, the file is kept for retry (up to 3 attempts) before being deleted and logged as an error.
9. **UI Update:** The mobile UI displays the position on a floor plan (map tile) and optionally provides turn‑by‑turn navigation instructions.

> **Sprint 1 note:** Steps 1–2 (CameraX + blur detection) are implemented in [`ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt) but not yet wired into the UI. Steps 3–9 are planned for Sprint 2.

---

## 4. Database Schema (Planned)

```sql
-- PostgreSQL tables (planned — not yet deployed)

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
- **Algorithm:** Compute the Laplacian variance of the grayscale version of the image. If the variance is below a calibrated threshold (determined per‑device during calibration), the frame is considered too blurry and is discarded.
- **Performance:** The blur‑detection routine must complete in < 100 ms on a mid‑range Android device.

### 5.4 Error Handling & Logging
- **Error Logs:** All runtime errors (I/O, network, validation failures) must be appended to `error_logs.txt` in the app's internal storage, with a timestamp, error code, and brief description.
- **Retry Logic:** Network requests that timeout or return 5xx status codes are retried up to 3 times with exponential backoff.
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

## 7. Current Implementation Status (Sprint 1)

### 7.1 Sprint 1 Scope
Sprint 1 delivered a functional Android UI shell with Google Maps integration, runtime permission handling, mock marker placement, and bilingual (EN/UK) resources. The ML backend pipeline (CameraX capture, backend upload, DINOv2 feature extraction, FAISS search) is **not yet wired into the UI** and remains planned for Sprint 2.

### 7.2 What Works (Verified)

| Feature | Status | Details |
|---|---|---|
| **Runtime Permissions** | ✅ Working | `ActivityResultContracts.RequestMultiplePermissions()` requests **CAMERA** + **ACCESS_FINE_LOCATION** + **ACCESS_COARSE_LOCATION** immediately on launch. All four grant/deny combinations handled with user‑friendly toasts. |
| **Google Maps Display** | ✅ Working | `SupportMapFragment` renders map tiles (requires valid API key with "Maps SDK for Android" enabled in Google Cloud Console). Default camera centres on Kyiv (50.4501, 30.5234) at zoom 15. |
| **Map UI Controls** | ✅ Working | Zoom controls (`+`/`–` buttons) enabled. Map toolbar (Google branding, open‑in‑Maps) disabled for MVP simplicity. |
| **Tap‑to‑Add Marker** | ✅ Working | Tapping the map creates a new `MarkerItem` at the tapped coordinates. Marker tag auto‑assigned based on currently selected filter chip (defaults to "All" → no tag). Toast confirms coordinates. |
| **Marker Info Window** | ✅ Working | Tapping a marker opens its info window showing title + snippet. |
| **Info‑Window Tap to Delete** | ✅ Working | Tapping the info window removes the marker. Toast confirms deletion. |
| **Search Bar** | ✅ Working | `TextWatcher` on the search `EditText` filters displayed markers in real‑time by title, snippet, and tag (case‑insensitive). |
| **Bilingual UI** | ✅ Working | Full English (`values/`) and Ukrainian (`values‑uk/`) string resources for all UI labels, toasts, and permission messages. |
| **Marker Tag Colors** | ✅ Working | Walking = Green (`HUE_GREEN`), Bicycle = Azure (`HUE_AZURE`), Car = Orange (`HUE_ORANGE`), default = Red (`HUE_RED`). |
| **CameraX ScannerCamera** | ✅ Implemented (not wired) | [`ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt) is fully built: `ResolutionSelector`, Laplacian variance blur detection, `captureSharpImage()` callback. Ready to integrate in Sprint 2. |
| **FileManagerService** | ✅ Implemented (not wired) | [`FileManagerService.kt`](mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt) provides TempScans folder, 50 MB free‑space check, UUID file naming, error logging. Ready for Sprint 2. |
| **LocalizationApiClient** | ✅ Implemented (not wired) | [`LocalizationApiClient.kt`](mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt) provides Retrofit client, multipart image upload, response parsing. Ready for Sprint 2. |

### 7.3 Known Issues / Not Yet Working

| Issue | Impact | Root Cause / Notes |
|---|---|---|
| **Filter Chips (ChipGroup) are non‑functional** | ❌ Markers do **not** filter when a transport‑mode chip (Walking, Bicycle, Car) is selected. The chips render and are selectable, but the marker list remains unchanged. | The `ViewModel.selectedTag` StateFlow or its combination with `_markers` in `filteredMarkers` may have a logic gap. The `setFilter(tag)` call updates the state, but the derived `filteredMarkers` flow may not be correctly combining the tag with the marker list. **Priority fix for Sprint 2.** |
| **User geolocation blue dot not showing** | ❌ The My‑Location layer (`map.isMyLocationEnabled = true`) and `FusedLocationProviderClient` for camera animation are implemented, but the blue dot does not appear after granting location permission. | Likely causes: (a) `FusedLocationProviderClient.lastLocation` returns `null` on first launch (no cached location), (b) the `isMapReady` guard fires before `getMapAsync` completes in some sequences, or (c) the location permission callback races with map initialisation. Also depends on a valid Google Maps API key for full tile rendering. |
| **CameraX not wired into UI** | N/A (Sprint 2) | `ScannerCamera` is fully implemented but not instantiated in `MainActivity`. The permission launcher has `// Sprint 2: initCameraX()` placeholder comments. |
| **ML Backend not deployed** | N/A (Sprint 2) | Backend code exists in [`backend/`](backend/) but is not running on a server. `LocalizationApiClient` will fail to connect. |
| **16 KB page‑size alignment (Android 15)** | ⚠️ Fixed (verify) | CameraX 1.4.1 + `packaging { jniLibs { useLegacyPackaging = true } }` added. Run `./gradlew clean assembleDebug` to purge cached 4 KB‑aligned libraries. |

### 7.4 Files Summary

| File | Purpose |
|---|---|
| [`mobile/android/settings.gradle.kts`](mobile/android/settings.gradle.kts) | Root project name "NaviSense", includes `:app` module |
| [`mobile/android/build.gradle.kts`](mobile/android/build.gradle.kts) | Project‑level: AGP 8.2.2, Kotlin 1.9.22 |
| [`mobile/android/gradle.properties`](mobile/android/gradle.properties) | AndroidX, parallel builds, JVM args |
| [`mobile/android/gradle/wrapper/gradle-wrapper.properties`](mobile/android/gradle/wrapper/gradle-wrapper.properties) | Gradle 8.5 distribution |
| [`mobile/android/app/build.gradle.kts`](mobile/android/app/build.gradle.kts) | App‑level: CameraX 1.4.1, Maps SDK 18.2.0, Retrofit 2.9, OkHttp 4.12, packaging block for 16 KB alignment |
| [`mobile/android/app/src/main/AndroidManifest.xml`](mobile/android/app/src/main/AndroidManifest.xml) | Permissions (INTERNET, CAMERA, FINE/COARSE LOCATION), Maps API key meta‑data, single‑activity launcher |
| [`mobile/android/app/src/main/java/com/navisense/MainActivity.kt`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt) | Single Activity: map init, permission launcher (Camera + Location), search bar, filter chips, marker rendering, `FusedLocationProviderClient` |
| [`mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt) | ViewModel: `_markers` StateFlow, `_selectedTag` StateFlow, `filteredMarkers` derived flow via `combine()`, mock marker seeding |
| [`mobile/android/app/src/main/java/com/navisense/model/MarkerItem.kt`](mobile/android/app/src/main/java/com/navisense/model/MarkerItem.kt) | `@Parcelize` data class: `id`, `title`, `snippet`, `latitude`, `longitude`, `tag`. Companion with `TAGS` list and `TAG_ALL = "All"`. |
| [`mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt`](mobile/android/app/src/main/java/com/navisense/core/ScannerCamera.kt) | CameraX wrapper: `ResolutionSelector`, `ImageCapture` with `MINIMIZE_LATENCY`, Laplacian variance blur detection, `captureSharpImage()` callback |
| [`mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt`](mobile/android/app/src/main/java/com/navisense/core/FileManagerService.kt) | TempScans folder management, 50 MB free‑space check, UUID file naming, error logging to `error_logs.txt` |
| [`mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt`](mobile/android/app/src/main/java/com/navisense/core/LocalizationApiClient.kt) | Retrofit singleton: `buildOkHttpClient()`, `buildRetrofit()`, `localizeImage(file)` suspend function |
| [`mobile/android/app/src/main/java/com/navisense/core/NaviSenseApi.kt`](mobile/android/app/src/main/java/com/navisense/core/NaviSenseApi.kt) | Retrofit interface: `uploadImage()` multipart, `PositionResponse`, `Landmark` data classes |
| [`mobile/android/app/src/main/res/layout/activity_main.xml`](mobile/android/app/src/main/res/layout/activity_main.xml) | CoordinatorLayout: full‑screen `SupportMapFragment`, search bar overlay (top), filter chips overlay (bottom) |
| [`mobile/android/app/src/main/res/values/strings.xml`](mobile/android/app/src/main/res/values/strings.xml) | English strings (EN) |
| [`mobile/android/app/src/main/res/values-uk/strings.xml`](mobile/android/app/src/main/res/values-uk/strings.xml) | Ukrainian strings (UK) |
| [`mobile/android/app/src/main/res/values/colors.xml`](mobile/android/app/src/main/res/values/colors.xml) | Brand colours, marker tag colours |
| [`mobile/android/app/src/main/res/values/themes.xml`](mobile/android/app/src/main/res/values/themes.xml) | Material3 Light NoActionBar theme |
| [`mobile/android/app/proguard-rules.pro`](mobile/android/app/proguard-rules.pro) | Keep rules for Retrofit, Gson, Maps |
| [`backend/`](backend/) | Python FastAPI backend, DINOv2 feature extractor, FAISS vector DB *(not deployed)* |

### 7.5 Build & Run Instructions

```bash
# 1. Configure your Google Maps API key
#    - Open mobile/android/app/src/main/AndroidManifest.xml
#    - Replace the value of com.google.android.geo.API_KEY with your key
#    - Ensure "Maps SDK for Android" is enabled in Google Cloud Console

# 2. Clean build (required after cache changes, e.g. CameraX upgrade)
cd mobile/android
./gradlew clean assembleDebug

# 3. Install on device / emulator (API 26+)
./gradlew installDebug
```

### 7.6 Sprint 2 Priority Items

1. **Fix filter chips** — Debug `MainViewModel.filteredMarkers` to ensure `combine()` reacts to `_selectedTag` changes correctly.
2. **Wire CameraX into UI** — Instantiate `ScannerCamera` in `MainActivity` on the `CAMERA`-granted permission branch.
3. **Deploy ML backend** — Deploy FastAPI service (Docker) to a cloud host, update `BACKEND_URL`.
4. **Fix user geolocation blue dot** — Debug `FusedLocationProviderClient.lastLocation` flow; consider using `getCurrentLocation()` with `Priority.PRIORITY_HIGH_ACCURACY` as fallback.

---

*This document is the single source of truth for the NaviSense MVP. Any architectural or validation changes must be reflected here before implementation.*
