import asyncio
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import JSONResponse
import uvicorn
import logging
from typing import Optional, List, Annotated
from pydantic import WithJsonSchema
import numpy as np
from PIL import Image
import io
import sys
import os
from pathlib import Path

import torch
import torchvision.transforms as T

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ── Import / mock ML dependencies ────────────────────────────────────
USE_MOCK = False

# VGGT-1B has NO dependency on FAISS – import separately so a FAISS
# DLL load error does not poison the VGGT singleton.
_VGGT_AVAILABLE = False
try:
    from app.vggt_processor import VGGTProcessor
    _VGGT_AVAILABLE = True
except ImportError as e:
    logger.warning(f"VGGT-1B not available: {e}")

# Feature extractor & vector DB depend on FAISS (DLL may fail on Windows).
try:
    from app.feature_extractor import get_extractor, get_vit_extractor
    from app.vector_db import get_vector_db, get_vit_vector_db
    logger.info("ML dependencies loaded successfully")
except ImportError as e:
    logger.warning(f"FAISS/ML dependencies not available: {e}. Using mock implementations.")
    USE_MOCK = True

# ---------------------------------------------------------------------------
# Mock classes (used ONLY when torch / transformers / faiss are not installed)
# ---------------------------------------------------------------------------
if USE_MOCK:

    class MockExtractor:
        def extract_features_from_bytes(self, image_bytes: bytes) -> np.ndarray:
            logger.info("Mock extractor: generating random feature vector")
            vec = np.random.randn(768).astype(np.float32)
            vec /= np.linalg.norm(vec)
            return vec

    class MockVectorDB:
        def __init__(self):
            self.landmark_ids = [f"landmark_{i:04d}" for i in range(1000)]
            self.positions = {}
            self.scopes: dict = {}
            center_lat, center_lon = 50.4501, 30.5234
            districts = ["Nyvky District", "Pechersk District", "Podil District", "Obolon District"]
            for lid in self.landmark_ids:
                lat = center_lat + np.random.uniform(-0.001, 0.001)
                lon = center_lon + np.random.uniform(-0.001, 0.001)
                floor = np.random.randint(0, 5)
                self.positions[lid] = (lat, lon, floor)
                self.scopes[lid] = np.random.choice(districts)
        
        def search(self, query_vector: np.ndarray, k: int = 5, scope_filter: Optional[str] = None):
            if scope_filter:
                matching = [lid for lid in self.landmark_ids if scope_filter.lower() in self.scopes.get(lid, "").lower()]
                if not matching:
                    matching = self.landmark_ids[:k]
                selected = np.random.choice(matching, min(k, len(matching)), replace=False)
            else:
                selected = np.random.choice(self.landmark_ids, k, replace=False)
            distances = np.random.rand(len(selected)).astype(np.float32) * 0.5
            indices = np.array([self.landmark_ids.index(s) for s in selected])
            return distances, indices, list(selected)
        
        def get_landmark_position(self, landmark_id: str):
            return self.positions.get(landmark_id)
        
        def get_landmark_scope(self, landmark_id: str) -> str:
            return self.scopes.get(landmark_id, "")

    # Create mock instances & shadowing wrappers (only used when imports failed)
    _mock_extractor = MockExtractor()
    _mock_vector_db = MockVectorDB()

    def get_extractor():
        return _mock_extractor

    def get_vit_extractor():
        return _mock_extractor

    def get_vector_db():
        return _mock_vector_db

    def get_vit_vector_db():
        return _mock_vector_db

# ── Mock VGGT processor (used when VGGT-1B itself fails to load) ─────────
if not _VGGT_AVAILABLE:

    class MockVGGTProcessor:
        """Fallback mock that returns sane odometry values when VGGT-1B is unavailable."""

        @torch.no_grad()
        def get_relative_position(self, images_tensor: torch.Tensor) -> list[float]:
            n = images_tensor.shape[1]  # number of frames
            # Return a plausible zero-ish offset
            return [0.0, 0.0, float(n) * 0.1]

        @torch.no_grad()
        def get_full_odometry(self, images_tensor: torch.Tensor) -> dict:
            n = images_tensor.shape[1]
            trajectory = []
            for i in range(n):
                trajectory.append({"dx": 0.0, "dy": 0.0, "dz": float(i) * 0.1})
            return {
                "trajectory": trajectory,
                "heading_vector": {"x": 1.0, "y": 0.0},
            }

    class VGGTProcessor(MockVGGTProcessor):  # type: ignore
        """Fallback alias so get_vggt_processor() always returns a valid type."""
        pass

# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------
# ── Workaround: force UploadFile to render as binary in OpenAPI schema ──
# Pydantic v2 no longer emits "format: binary" for UploadFile, which causes
# Swagger UI to show a text input instead of a file-upload button when used
# inside List[UploadFile].  The annotated type below explicitly sets the
# JSON schema so Swagger UI renders the correct multi-file picker.
FixedUploadFile = Annotated[UploadFile, WithJsonSchema({"type": "string", "format": "binary"})]

app = FastAPI(
    title="NaviSense Backend API",
    description="Visual positioning backend using ViT / DINOv2 and FAISS",
    version="0.2.0"
)

# Singleton holders (real implementations)
_extractor_real = None
_vector_db_real = None
_vit_extractor_real = None
_vit_vector_db_real = None
_vggt_processor_real = None

def get_components():
    """Lazy-load and return DINOv2 extractor + vector DB (for /api/v1/position)."""
    global _extractor_real, _vector_db_real
    if _extractor_real is None:
        _extractor_real = get_extractor()
    if _vector_db_real is None:
        _vector_db_real = get_vector_db()
    return _extractor_real, _vector_db_real

def get_vit_components():
    """Lazy-load and return ViT extractor + vector DB (for /api/visual-locate)."""
    global _vit_extractor_real, _vit_vector_db_real
    if _vit_extractor_real is None:
        _vit_extractor_real = get_vit_extractor()
    if _vit_vector_db_real is None:
        _vit_vector_db_real = get_vit_vector_db()
    return _vit_extractor_real, _vit_vector_db_real


def get_vggt_processor() -> VGGTProcessor:
    """Lazy-load and return the VGGT-1B processor singleton."""
    global _vggt_processor_real
    if _vggt_processor_real is None:
        _vggt_processor_real = VGGTProcessor()
    return _vggt_processor_real


# =====================================================================
#  Endpoints
# =====================================================================

@app.get("/")
async def root():
    return {"message": "NaviSense Backend API"}


@app.get("/api/v1/health")
async def health():
    """Health check endpoint."""
    mode = "mock" if USE_MOCK else "production"
    return {"status": "ok", "mode": mode}


# ── DINOv2-based positioning (existing) ──────────────────────────────

@app.post("/api/v1/position")
async def position_estimate(file: UploadFile = File(...)):
    """
    Accepts a JPEG image, extracts features with **DINOv2**,
    performs FAISS vector search, returns estimated position.
    """
    contents, filename = await _read_upload(file)
    extractor, vector_db = get_components()
    return _run_position_pipeline(contents, filename, extractor, vector_db)


# ── ViT-based visual place recognition (new) ─────────────────────────

@app.post("/api/visual-locate")
async def visual_locate(
    file: UploadFile = File(...),
    location_scope: str = Form(""),
):
    """
    Visual Place Recognition using **ViT** + FAISS.
    
    Accepts an image (JPEG/PNG) and an optional ``location_scope`` string
    (e.g. ``"Kyiv"``, ``"Nyvky District"``) to narrow the search to a
    predefined geographic area.
    
    Returns:
        ``{"latitude": …, "longitude": …, "confidence_score": …}``
    """
    contents, filename = await _read_upload(file)
    extractor, vector_db = get_vit_components()

    logger.info(
        f"Visual-locate request: file={filename}, "
        f"size={len(contents)} bytes, "
        f"scope='{location_scope}'"
    )

    # 1. Extract feature vector
    feature_vector = extractor.extract_features_from_bytes(contents)
    logger.debug(f"ViT feature vector shape: {feature_vector.shape}")

    # 2. Search FAISS (with optional scope filter)
    distances, indices, landmark_ids = vector_db.search(
        feature_vector, k=5, scope_filter=location_scope if location_scope else None
    )

    if len(landmark_ids) == 0:
        raise HTTPException(status_code=404, detail="No matching landmarks found")

    # 3. Weighted average (center of mass) across all top-k matches
    positions = []
    confidences = []
    for lid, dist in zip(landmark_ids, distances):
        pos = vector_db.get_landmark_position(lid)
        if pos:
            lat, lon, floor = pos
            # For L2 distance on normalized vectors: 0 = perfect match, 2 = max
            # confidence = 1 / (1 + distance) gives 1.0 at dist=0, ~0.33 at dist=2
            confidence = 1.0 / (1.0 + float(dist))
            positions.append((lat, lon, floor))
            confidences.append(confidence)

    if not positions:
        raise HTTPException(status_code=500, detail="Landmark metadata missing")

    total_confidence = sum(confidences)
    weighted_lat = sum(p[0] * c for p, c in zip(positions, confidences)) / total_confidence
    weighted_lon = sum(p[1] * c for p, c in zip(positions, confidences)) / total_confidence

    # 4. Confidence score: maximum confidence among the top-k matches
    confidence_score = round(max(confidences), 4)

    response = {
        "latitude": float(weighted_lat),
        "longitude": float(weighted_lon),
        "confidence_score": confidence_score,
    }

    logger.info(
        f"Visual locate result: lat={weighted_lat:.4f}, lon={weighted_lon:.4f}, "
        f"confidence={confidence_score}, top_match={landmark_ids[0]}"
    )
    return JSONResponse(content=response)


# ── Fusion endpoint: ViT + VGGT in parallel ─────────────────────────

@app.post("/api/v1/navigate-fusion")
async def navigate_fusion(files: List[FixedUploadFile] = File(...)):
    """
    **Fused visual navigation** — runs ViT absolute positioning and VGGT-1B
    visual odometry **sequentially** on a single set of 4 images.

    The endpoint accepts **exactly 4 images** as ``multipart/form-data``
    under the field name ``files``.

    **Why 4 images?**
    VGGT-1B benefits from multi-frame context for stable pose estimation;
    ViT only needs the first frame for visual place recognition.  Both models
    are executed **sequentially** (ViT first, then VGGT) with
    ``torch.cuda.empty_cache()`` in between to prevent CUDA Out-Of-Memory
    errors on consumer GPUs.

    Returns:
        ``{
          "current_location": {"lat": float, "lng": float},
          "trajectory": [{"dx": float, "dy": float, "dz": float}, ...],
          "heading_vector": {"x": float, "y": float}
        }``

        - ``current_location`` — WGS‑84 coordinates from ViT + FAISS.
        - ``trajectory`` — per-frame camera-centre displacement relative to
          the first frame (length = number of input images).
        - ``heading_vector`` — normalised 2D forward direction on the ground
          plane (``x`` = lateral, ``y`` = forward/depth).
    """
    # ── 1. Validation ──────────────────────────────────────────────────
    if not files or len(files) < 2:
        raise HTTPException(
            status_code=400,
            detail="At least 2 images are required (4 recommended for best accuracy).",
        )

    logger.info(f"Navigate-fusion request: {len(files)} files received")

    # ── 2. Read all images into memory ONCE ────────────────────────────
    try:
        images_bytes: list[bytes] = []
        for f in files:
            contents = await f.read()
            if len(contents) == 0:
                raise HTTPException(
                    status_code=400,
                    detail=f"Empty file: {f.filename or 'unknown'}",
                )
            images_bytes.append(contents)
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to read uploaded files: {e}", exc_info=True)
        raise HTTPException(status_code=400, detail=f"File read error: {str(e)}")

    # ── 3. Run ViT and VGGT pipelines SEQUENTIALLY ────────────────────
    #     ViT first → empty_cache → VGGT → empty_cache to prevent OOM
    try:
        # Step 3a: ViT (absolute positioning)
        vit_result = await _run_vit_pipeline(images_bytes[0])

        # Step 3b: Release VRAM used by ViT
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            logger.debug("CUDA cache cleared after ViT inference")

        # Step 3c: VGGT (visual odometry)
        vggt_result = await _run_vggt_pipeline(images_bytes)

        # Step 3d: Release VRAM used by VGGT
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            logger.debug("CUDA cache cleared after VGGT inference")

        # ── 4. Combine results ─────────────────────────────────────────
        response = {
            "current_location": {
                "lat": vit_result["latitude"],
                "lng": vit_result["longitude"],
            },
            "trajectory": vggt_result["trajectory"],
            "heading_vector": vggt_result["heading_vector"],
        }

        logger.info(
            f"Navigate-fusion complete: "
            f"lat={vit_result['latitude']:.4f}, "
            f"lon={vit_result['longitude']:.4f}, "
            f"trajectory_len={len(vggt_result['trajectory'])}, "
            f"heading=({vggt_result['heading_vector']['x']:.3f}, "
            f"{vggt_result['heading_vector']['y']:.3f})"
        )

        return JSONResponse(content=response)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Navigate-fusion failed: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"Navigation fusion failed: {str(e)}",
        )


async def _run_vit_pipeline(first_image_bytes: bytes) -> dict:
    """
    Run the ViT visual place recognition pipeline on a single image.

    This is a **blocking** (CPU/GPU-bound) call wrapped in
    ``asyncio.to_thread`` so it does not block the FastAPI event loop.
    """
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(
        None, _vit_pipeline_sync, first_image_bytes
    )


def _vit_pipeline_sync(image_bytes: bytes) -> dict:
    """Synchronous ViT + FAISS pipeline (runs in a thread executor)."""
    extractor, vector_db = get_vit_components()

    # 1. Extract feature vector
    feature_vector = extractor.extract_features_from_bytes(image_bytes)

    # 2. Search FAISS
    distances, indices, landmark_ids = vector_db.search(
        feature_vector, k=5, scope_filter=None
    )

    if len(landmark_ids) == 0:
        raise HTTPException(status_code=404, detail="No matching landmarks found")

    # 3. Weighted average across top-k matches
    positions = []
    confidences = []
    for lid, dist in zip(landmark_ids, distances):
        pos = vector_db.get_landmark_position(lid)
        if pos:
            lat, lon, floor = pos
            confidence = 1.0 / (1.0 + float(dist))
            positions.append((lat, lon, floor))
            confidences.append(confidence)

    if not positions:
        raise HTTPException(status_code=500, detail="Landmark metadata missing")

    total_confidence = sum(confidences)
    weighted_lat = sum(p[0] * c for p, c in zip(positions, confidences)) / total_confidence
    weighted_lon = sum(p[1] * c for p, c in zip(positions, confidences)) / total_confidence

    return {
        "latitude": float(weighted_lat),
        "longitude": float(weighted_lon),
        "confidence": round(max(confidences), 4),
    }


async def _run_vggt_pipeline(images_bytes: list[bytes]) -> dict:
    """
    Run the VGGT-1B visual odometry pipeline on a list of image byte blobs.

    Called **sequentially** after the ViT pipeline has finished and
    ``torch.cuda.empty_cache()`` has been called.  Wrapped in
    ``asyncio.to_thread`` (via ``run_in_executor``) so the blocking GPU
    inference does not stall the event loop.
    """
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(
        None, _vggt_pipeline_sync, images_bytes
    )


def _vggt_pipeline_sync(images_bytes: list[bytes]) -> dict:
    """Synchronous VGGT pipeline (runs in a thread executor)."""
    from PIL import Image
    import torchvision.transforms as T

    # 1. Load & preprocess images
    images_pil: list[Image.Image] = []
    for img_bytes in images_bytes:
        img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
        img = img.resize((518, 518), Image.BILINEAR)
        images_pil.append(img)

    to_tensor = T.ToTensor()
    tensors = [to_tensor(img) for img in images_pil]
    batch = torch.stack(tensors, dim=0).unsqueeze(0)  # (1, N, 3, 518, 518)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    batch = batch.to(device)
    if device.type == "cuda":
        batch = batch.half()

    # 2. Run full odometry
    processor = get_vggt_processor()
    result = processor.get_full_odometry(batch)  # returns {"trajectory": [...], "heading_vector": {...}}

    return result


# ── Calibration placeholder (existing) ───────────────────────────────

@app.post("/api/v1/calibrate")
async def calibrate(file: UploadFile = File(...)):
    """Optional calibration endpoint (not implemented)."""
    return {"message": "Calibration endpoint (not implemented)"}


# ── VGGT-1B visual odometry (3D relative position) ────────────────────

@app.post("/api/v1/vggt-odometry")
async def vggt_odometry(files: List[FixedUploadFile] = File(...)):
    """
    Estimate the relative camera-centre offset from a **sequence of images**
    using the VGGT-1B model.

    Accepts 2+ images as ``multipart/form-data`` under the field name ``files``.
    The images are resized to 518×518 (VGGT-1B native resolution), stacked into
    a 5D tensor, and fed to the model.  The returned offset represents the
    camera centre of the **last** frame relative to the sequence's world
    coordinate system.

    Returns:
        ``{"status": "success", "camera_center_offset": {"x": float, "y": float, "z": float}}``
    """
    # ── 1. Validation ─────────────────────────────────────────────────
    if not files or len(files) < 2:
        raise HTTPException(
            status_code=400,
            detail="At least 2 images are required for visual odometry.",
        )

    logger.info(f"VGGT odometry request: {len(files)} files received")

    # ── 2. Load & preprocess images ───────────────────────────────────
    try:
        images_pil: list[Image.Image] = []
        for f in files:
            contents = await f.read()
            if len(contents) == 0:
                raise HTTPException(
                    status_code=400,
                    detail=f"Empty file: {f.filename or 'unknown'}",
                )
            img = Image.open(io.BytesIO(contents)).convert("RGB")
            img = img.resize((518, 518), Image.BILINEAR)
            images_pil.append(img)

        # Build 5D tensor  (1, N, 3, 518, 518)  with values in [0, 1]
        #   ToTensor() converts HWC uint8 → CHW float32 in [0, 1]
        to_tensor = T.ToTensor()
        tensors = [to_tensor(img) for img in images_pil]       # list of (3, 518, 518)
        batch = torch.stack(tensors, dim=0).unsqueeze(0)       # (1, N, 3, 518, 518)

        # GPU / FP16 — consistent with FeatureExtractor optimisation
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        batch = batch.to(device)
        if device.type == "cuda":
            batch = batch.half()

        logger.info(
            f"VGGT input tensor shape: {batch.shape}, "
            f"dtype: {batch.dtype}, device: {batch.device}"
        )

        # ── 3. Run inference ──────────────────────────────────────────
        processor = get_vggt_processor()
        camera_centre = processor.get_relative_position(batch)  # [x, y, z]

        logger.info(f"VGGT odometry result: camera_centre={camera_centre}")

        return JSONResponse(content={
            "status": "success",
            "camera_center_offset": {
                "x": float(camera_centre[0]),
                "y": float(camera_centre[1]),
                "z": float(camera_centre[2]),
            },
        })

    except HTTPException:
        raise  # re-raise validation errors as-is
    except Exception as e:
        logger.error(f"VGGT odometry inference failed: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"VGGT odometry inference failed: {str(e)}",
        )


# =====================================================================
#  Shared helpers
# =====================================================================

async def _read_upload(file: UploadFile) -> tuple:
    """
    Validate and read an uploaded image file.
    
    Returns ``(contents, filename)``.
    Raises ``HTTPException`` on invalid input.
    """
    # Accept JPEG and PNG
    allowed = {"image/jpeg", "image/jpg", "image/png"}
    if file.content_type not in allowed:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported content type '{file.content_type}'. "
                   f"Allowed: {', '.join(allowed)}",
        )
    
    contents = await file.read()
    if len(contents) == 0:
        raise HTTPException(status_code=400, detail="Empty file")
    
    max_size = 5 * 1024 * 1024  # 5 MB
    if len(contents) > max_size:
        raise HTTPException(status_code=400, detail="File too large (max 5 MB)")
    
    return contents, file.filename or "unknown"


def _run_position_pipeline(
    contents: bytes,
    filename: str,
    extractor,
    vector_db,
) -> JSONResponse:
    """
    Shared logic for extracting features, searching FAISS, and computing
    a weighted position estimate.
    """
    logger.info(f"Processing image: {filename}, size: {len(contents)} bytes")
    
    feature_vector = extractor.extract_features_from_bytes(contents)
    logger.debug(f"Feature vector shape: {feature_vector.shape}")
    
    distances, indices, landmark_ids = vector_db.search(feature_vector, k=5)
    
    if len(landmark_ids) == 0:
        raise HTTPException(status_code=404, detail="No landmarks found in database")
    
    positions = []
    confidences = []
    for lid, dist in zip(landmark_ids, distances):
        pos = vector_db.get_landmark_position(lid)
        if pos:
            lat, lon, floor = pos
            confidence = 1.0 / (1.0 + dist)
            positions.append((lat, lon, floor))
            confidences.append(confidence)
    
    if not positions:
        raise HTTPException(status_code=500, detail="Landmark metadata missing")
    
    total_confidence = sum(confidences)
    weighted_lat = sum(p[0] * c for p, c in zip(positions, confidences)) / total_confidence
    weighted_lon = sum(p[1] * c for p, c in zip(positions, confidences)) / total_confidence
    weighted_floor = round(sum(p[2] * c for p, c in zip(positions, confidences)) / total_confidence)
    overall_confidence = sum(confidences) / len(confidences)
    
    response = {
        "latitude": float(weighted_lat),
        "longitude": float(weighted_lon),
        "floor": int(weighted_floor),
        "confidence": float(overall_confidence),
        "nearest_landmarks": [
            {"id": lid, "distance": float(dist), "confidence": float(conf)}
            for lid, dist, conf in zip(landmark_ids, distances, confidences)
        ]
    }
    
    logger.info(
        f"Position estimated: lat={response['latitude']}, "
        f"lon={response['longitude']}, floor={response['floor']}"
    )
    return JSONResponse(content=response)


# =====================================================================
if __name__ == "__main__":
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)