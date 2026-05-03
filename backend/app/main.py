from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import JSONResponse
import uvicorn
import logging
from typing import Optional, List
import numpy as np
from PIL import Image
import io
import sys
import os
from pathlib import Path

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ── Import / mock ML dependencies ────────────────────────────────────
USE_MOCK = False
try:
    from app.feature_extractor import get_extractor, get_vit_extractor
    from app.vector_db import get_vector_db, get_vit_vector_db
    logger.info("ML dependencies loaded successfully")
except ImportError as e:
    logger.warning(f"ML dependencies not available: {e}. Using mock implementations.")
    USE_MOCK = True

# ---------------------------------------------------------------------------
# Mock classes (used when torch / transformers / faiss are not installed)
# ---------------------------------------------------------------------------
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
        # Simulate scope filtering
        if scope_filter:
            # Filter mock IDs that match the scope
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

# Create mock instances
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

# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------
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

    # 3. Retrieve the best match (top-1)
    best_id = landmark_ids[0]
    best_distance = float(distances[0])
    pos = vector_db.get_landmark_position(best_id)

    if pos is None:
        raise HTTPException(status_code=500, detail="Landmark metadata missing")

    lat, lon, floor = pos  # floor is 0 for outdoor reference images

    # 4. Compute confidence score
    #    For L2 distance on normalized vectors: 0 = perfect match, 2 = max
    #    confidence = 1 / (1 + distance) gives 1.0 at dist=0, ~0.33 at dist=2
    confidence_score = round(1.0 / (1.0 + best_distance), 4)

    response = {
        "latitude": float(lat),
        "longitude": float(lon),
        "confidence_score": confidence_score,
    }

    logger.info(
        f"Visual locate result: lat={lat:.4f}, lon={lon:.4f}, "
        f"confidence={confidence_score}, match={best_id}"
    )
    return JSONResponse(content=response)


# ── Calibration placeholder (existing) ───────────────────────────────

@app.post("/api/v1/calibrate")
async def calibrate(file: UploadFile = File(...)):
    """Optional calibration endpoint (not implemented)."""
    return {"message": "Calibration endpoint (not implemented)"}


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