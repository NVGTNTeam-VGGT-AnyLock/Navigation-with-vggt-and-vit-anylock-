# NaviSense MVP - Single Source of Truth

## 1. Project Goal
NaviSense is a visual positioning mobile application designed for couriers operating in GPS‑denied urban environments (e.g., indoor warehouses, dense urban canyons, underground facilities). The MVP provides real‑time location estimation using a single camera frame, without relying on GPS signals or continuous video streaming.

**Core Value Proposition:**
- Deliver sub‑meter positional accuracy in environments where GPS is unavailable or unreliable.
- Enable couriers to navigate complex indoor spaces (shopping malls, office towers, underground parking) with visual cues.
- Minimize latency and battery consumption by using single‑frame captures instead of continuous video.

## 2. Tech Stack

### Mobile Frontend
- **Platform:** Native Android (minimum SDK 26)
- **Language:** Kotlin (no cross‑platform frameworks)
- **Key Libraries:**
  - CameraX for image capture
    - Target resolution: 1080×1920 (portrait)
    - Frame capture triggered manually (single still image)
    - ImageAnalysis pipeline for real‑time blur detection (optional)
  - Retrofit2 for REST communication
    - Base URL configurable via `BuildConfig.BACKEND_URL`
    - Timeout: 10 seconds connect, 30 seconds read
    - Multipart file upload with JPEG compression quality 85%
  - Room for local caching (if needed)
  - OpenCV‑Android for lightweight blur‑detection

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
- **CI/CD:** GitHub Actions (build, test, deploy)
- **Containerization:** Docker, Docker‑Compose for local development
- **Database:** PostgreSQL 14+ (for metadata, user sessions, landmark catalog)
- **Cloud Provider:** AWS / GCP (TBD)

## 3. Architecture Flow

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

**Detailed Steps:**

1. **Frame Capture:** Mobile camera captures a single RGB frame (resolution 1080×1920) via CameraX.
2. **Edge Validation:** A lightweight blur‑detection algorithm (Laplacian variance) runs on‑device. If the frame is too blurry, it is discarded immediately; no file I/O or network call occurs.
3. **Storage Check:** Before writing to disk, the app verifies that at least 50 MB of free space is available on the device’s internal storage. If not, the operation is aborted and an error is logged.
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

## 4. Database Schema

```sql
-- PostgreSQL tables

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
    feature_vector BYTEA, -- FAISS‑compatible vector (optional, can be stored separately)
    image_path VARCHAR(512), -- path to reference image
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

## 5. Strict Validation Rules

### 5.1 Low Latency Requirement
- **Single‑frame only:** The mobile app must never stream video to the backend. Each positioning request corresponds to exactly one captured image.
- **End‑to‑end latency target:** < 2 seconds (from capture to position display), excluding network round‑trip.
- **On‑device preprocessing:** Resize and compression must be performed before transmission; the uploaded JPEG shall not exceed 500 KB.

### 5.2 File I/O & Storage Policies
1. **TempScans Folder:** All temporary images must be saved exclusively to `[App Internal Storage]/TempScans/`. No other directory may be used for this purpose.
2. **Free‑space Check:** Before any file write, the app must verify that at least **50 MB** of free space is available on the device’s internal storage. If the check fails, the operation is aborted and an error is appended to `error_logs.txt`.
3. **Immediate Deletion:** After successful backend transmission (HTTP 200 response), the temporary image file must be deleted **before** updating the UI. If transmission fails, the file may be kept for retry (max 3 attempts) but must be deleted after the final failure.
4. **No Persistence:** No captured image may remain on the device for longer than 5 minutes, regardless of success or failure.

### 5.3 Edge Validation (Blur Detection)
- Every captured frame must undergo a blur‑detection test before hitting the file system or network.
- **Algorithm:** Compute the Laplacian variance of the grayscale version of the image. If the variance is below a calibrated threshold (determined per‑device during calibration), the frame is considered too blurry and is discarded.
- **Performance:** The blur‑detection routine must complete in < 100 ms on a mid‑range Android device.

### 5.4 Error Handling & Logging
- **Error Logs:** All runtime errors (I/O, network, validation failures) must be appended to `error_logs.txt` in the app’s internal storage, with a timestamp, error code, and brief description.
- **Retry Logic:** Network requests that timeout or return 5xx status codes are retried up to 3 times with exponential backoff.
- **User Feedback:** Non‑technical errors (e.g., “image too blurry”, “insufficient storage”) are displayed as user‑friendly toasts; technical errors are logged silently.

### 5.5 Security & Privacy
- **Image Transmission:** All images are transmitted over HTTPS with certificate pinning.
- **No Personal Data:** The app must not capture or transmit any personally identifiable information (PII) embedded in the image. If face‑like regions are detected (optional), the image is discarded.
- **Local Storage:** The TempScans folder is located in the app’s internal storage, which is sandboxed and inaccessible to other apps.

## 6. Development Workflow (Gitflow)

- **Main Branches:**
  - `main` – production‑ready code, tagged releases.
  - `develop` – integration branch for completed features.
- **Feature Branches:** `feature/*` branched from `develop`. Must pass code review and CI before merging.
- **Release Branches:** `release/*` for final testing, version bumping, and documentation updates.
- **Hotfix Branches:** `hotfix/*` branched from `main` for urgent production fixes.

**CI/CD Pipeline:**
1. On push to `feature/*` – run unit tests (Android: Gradle, Backend: pytest).
2. On merge to `develop` – build Docker images, run integration tests.
3. On merge to `main` – deploy to staging environment (auto) and optionally to production (manual approval).

---

*This document is the single source of truth for the NaviSense MVP. Any architectural or validation changes must be reflected here before implementation.*
