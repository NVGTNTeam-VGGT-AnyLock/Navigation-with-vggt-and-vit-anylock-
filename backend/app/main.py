from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse
import uvicorn
import logging
from typing import Optional, List
import numpy as np
from PIL import Image
import io
import sys

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Try to import ML dependencies; fall back to mock if unavailable
USE_MOCK = False
try:
    from app.feature_extractor import get_extractor
    from app.vector_db import get_vector_db
    logger.info("ML dependencies loaded successfully")
except ImportError as e:
    logger.warning(f"ML dependencies not available: {e}. Using mock implementations.")
    USE_MOCK = True

if USE_MOCK:
    # Mock feature extractor
    class MockExtractor:
        def extract_features_from_bytes(self, image_bytes: bytes) -> np.ndarray:
            logger.info("Mock extractor: generating random feature vector")
            # Return random unit vector of dimension 768
            vec = np.random.randn(768).astype(np.float32)
            vec /= np.linalg.norm(vec)
            return vec
    
    # Mock vector database
    class MockVectorDB:
        def __init__(self):
            self.landmark_ids = [f"landmark_{i:04d}" for i in range(1000)]
            # Random positions around Kyiv
            self.positions = {}
            center_lat, center_lon = 50.4501, 30.5234
            for lid in self.landmark_ids:
                lat = center_lat + np.random.uniform(-0.001, 0.001)
                lon = center_lon + np.random.uniform(-0.001, 0.001)
                floor = np.random.randint(0, 5)
                self.positions[lid] = (lat, lon, floor)
        
        def search(self, query_vector: np.ndarray, k: int = 5):
            distances = np.random.rand(k).astype(np.float32) * 0.5
            indices = np.random.choice(len(self.landmark_ids), k, replace=False)
            landmark_ids = [self.landmark_ids[i] for i in indices]
            return distances, indices, landmark_ids
        
        def get_landmark_position(self, landmark_id: str):
            return self.positions.get(landmark_id)
    
    # Create mock instances
    _extractor = MockExtractor()
    _vector_db = MockVectorDB()
    
    def get_extractor():
        return _extractor
    
    def get_vector_db():
        return _vector_db

app = FastAPI(
    title="NaviSense Backend API",
    description="Visual positioning backend using DINOv2 and FAISS",
    version="0.1.0"
)

# Initialize singleton components (lazy loading)
_extractor_real = None
_vector_db_real = None

def get_components():
    """Lazy load and return extractor and vector DB."""
    global _extractor_real, _vector_db_real
    if _extractor_real is None:
        _extractor_real = get_extractor()
    if _vector_db_real is None:
        _vector_db_real = get_vector_db()
    return _extractor_real, _vector_db_real

@app.get("/")
async def root():
    return {"message": "NaviSense Backend API"}

@app.get("/api/v1/health")
async def health():
    """Health check endpoint."""
    return {"status": "ok"}

@app.post("/api/v1/position")
async def position_estimate(file: UploadFile = File(...)):
    """
    Accepts a JPEG image, extracts features with DINOv2,
    performs FAISS vector search, returns estimated position.
    """
    # Validate file type
    if file.content_type not in ["image/jpeg", "image/jpg"]:
        raise HTTPException(status_code=400, detail="Only JPEG images are allowed")
    
    # Read image bytes
    try:
        contents = await file.read()
        if len(contents) == 0:
            raise HTTPException(status_code=400, detail="Empty file")
        
        # Limit file size (e.g., 5 MB)
        max_size = 5 * 1024 * 1024
        if len(contents) > max_size:
            raise HTTPException(status_code=400, detail="File too large (max 5MB)")
        
        logger.info(f"Processing image: {file.filename}, size: {len(contents)} bytes")
        
        # Get components
        extractor, vector_db = get_components()
        
        # Extract feature vector
        feature_vector = extractor.extract_features_from_bytes(contents)
        logger.debug(f"Feature vector shape: {feature_vector.shape}")
        
        # Search nearest landmarks (top 5)
        distances, indices, landmark_ids = vector_db.search(feature_vector, k=5)
        
        if len(landmark_ids) == 0:
            raise HTTPException(status_code=404, detail="No landmarks found in database")
        
        # Retrieve positions of the nearest landmarks
        positions = []
        confidences = []
        for lid, dist in zip(landmark_ids, distances):
            pos = vector_db.get_landmark_position(lid)
            if pos:
                lat, lon, floor = pos
                # Convert distance to confidence score (higher distance = lower confidence)
                # Simple heuristic: confidence = 1 / (1 + distance)
                confidence = 1.0 / (1.0 + dist)
                positions.append((lat, lon, floor))
                confidences.append(confidence)
        
        if not positions:
            raise HTTPException(status_code=500, detail="Landmark metadata missing")
        
        # Weighted average of positions based on confidence
        total_confidence = sum(confidences)
        weighted_lat = sum(p[0] * c for p, c in zip(positions, confidences)) / total_confidence
        weighted_lon = sum(p[1] * c for p, c in zip(positions, confidences)) / total_confidence
        # Floor: round weighted average (could also pick floor of nearest landmark)
        weighted_floor = round(sum(p[2] * c for p, c in zip(positions, confidences)) / total_confidence)
        
        # Overall confidence as average of top‑K confidences
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
        
        logger.info(f"Position estimated: lat={response['latitude']}, lon={response['longitude']}, floor={response['floor']}")
        return JSONResponse(content=response)
    
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Unexpected error during position estimation")
        raise HTTPException(status_code=500, detail=f"Internal server error: {str(e)}")

@app.post("/api/v1/calibrate")
async def calibrate(file: UploadFile = File(...)):
    """
    Optional calibration endpoint for adjusting blur-detection threshold.
    """
    # TODO: Implement calibration logic
    return {"message": "Calibration endpoint (not implemented)"}

if __name__ == "__main__":
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)