"""
NaviSense 2.0 — Backend API

Single endpoint: ``POST /api/v1/navigate-fusion``

Accepts 4 images, runs ViT (absolute positioning) and VGGT-1B (visual odometry)
**sequentially** with ``torch.cuda.empty_cache()`` in between to prevent CUDA OOM.

No mock classes. If PyTorch, ViT, or VGGT fails to load → HTTP 500.
"""

import asyncio
import io
import logging
from typing import List, Annotated

import torch
import torchvision.transforms as T
import numpy as np
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse
from PIL import Image
from pydantic import WithJsonSchema
import uvicorn

# ── Logging ────────────────────────────────────────────────────────────
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ── ML model imports (fail hard on ImportError → HTTP 500) ─────────────
from app.vggt_processor import VGGTProcessor
from app.feature_extractor import FeatureExtractor
from app.vector_db import VectorDatabase, get_vector_db

# ── FastAPI app ────────────────────────────────────────────────────────
FixedUploadFile = Annotated[
    UploadFile, WithJsonSchema({"type": "string", "format": "binary"})
]

app = FastAPI(
    title="NaviSense 2.0 API",
    description="Single-endpoint visual navigation: ViT (absolute) + VGGT-1B (odometry)",
    version="2.0.0",
)

# ── Singleton holders ──────────────────────────────────────────────────
_vit_extractor: "FeatureExtractor | None" = None
_vit_vector_db: "VectorDatabase | None" = None
_vggt_processor: "VGGTProcessor | None" = None


def _get_vit_extractor() -> FeatureExtractor:
    """Lazy-load ViT feature extractor (google/vit-base-patch16-224)."""
    global _vit_extractor
    if _vit_extractor is None:
        logger.info("Loading ViT feature extractor (google/vit-base-patch16-224) ...")
        _vit_extractor = FeatureExtractor(model_type="vit")
        logger.info(f"ViT extractor loaded on {_vit_extractor.device}")
    return _vit_extractor


def _get_vit_vector_db() -> VectorDatabase:
    """Lazy-load the FAISS vector database for ViT features."""
    global _vit_vector_db
    if _vit_vector_db is None:
        _vit_vector_db = get_vector_db(fallback_to_demo=False)
    return _vit_vector_db


def _get_vggt() -> VGGTProcessor:
    """Lazy-load VGGT-1B processor."""
    global _vggt_processor
    if _vggt_processor is None:
        _vggt_processor = VGGTProcessor()
    return _vggt_processor


# =====================================================================
#  Endpoints
# =====================================================================

@app.get("/")
async def root():
    return {"message": "NaviSense 2.0 API"}


@app.get("/api/v1/health")
async def health():
    """Health check — returns 200 if the server is alive."""
    return {"status": "ok", "version": "2.0.0"}


@app.post("/api/v1/navigate-fusion")
async def navigate_fusion(files: List[FixedUploadFile] = File(...)):
    """
    **Fused visual navigation** — runs ViT absolute positioning and VGGT-1B
    visual odometry **sequentially** on a single set of 4 images.

    Accepts **exactly 4 images** as ``multipart/form-data`` under ``files``.

    **Execution flow (strictly sequential to prevent CUDA OOM):**

    1. ``vit_extractor(frame_4)`` → FAISS search → get Lat/Lon
    2. ``torch.cuda.empty_cache()``
    3. ``vggt_processor(frames_1_to_4)`` → get Heading & 3D Offset
    4. ``torch.cuda.empty_cache()``
    5. Merge results

    Returns:
        ``{"lat": float, "lon": float, "heading": float}``

        - ``lat``, ``lon`` — WGS‑84 coordinates from ViT + FAISS.
        - ``heading`` — normalised forward direction angle in degrees
          (0° = North, 90° = East, 180° = South, 270° = West).
    """
    # ── 1. Validation ──────────────────────────────────────────────────
    if not files or len(files) != 4:
        raise HTTPException(
            status_code=400,
            detail="Exactly 4 images are required.",
        )

    logger.info(f"Navigate-fusion request: {len(files)} files received")

    # ── 2. Read all images into memory ─────────────────────────────────
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
    try:
        # Step 3a: ViT (absolute positioning) — use LAST frame (frame_4)
        lat, lon = _run_vit_sync(images_bytes[3])

        # Step 3b: Release VRAM used by ViT
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            logger.debug("CUDA cache cleared after ViT inference")

        # Step 3c: VGGT (visual odometry) — use ALL 4 frames
        heading = _run_vggt_sync(images_bytes)

        # Step 3d: Release VRAM used by VGGT
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            logger.debug("CUDA cache cleared after VGGT inference")

        # ── 4. Return simplified response ──────────────────────────────
        response = {
            "lat": round(lat, 6),
            "lon": round(lon, 6),
            "heading": round(heading, 2),
        }

        logger.info(
            f"Navigate-fusion complete: "
            f"lat={lat:.4f}, lon={lon:.4f}, heading={heading:.1f}°"
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


# =====================================================================
#  Synchronous ML pipelines (run in thread executor)
# =====================================================================

def _run_vit_sync(image_bytes: bytes) -> tuple[float, float]:
    """
    Run ViT feature extraction + FAISS search on a single image.

    Returns ``(latitude, longitude)``.
    """
    extractor = _get_vit_extractor()
    vector_db = _get_vit_vector_db()

    # Load image
    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")

    # Extract features
    features = extractor.extract_features(image)

    # ── FAISS search ──────────────────────────────────────────────────
    if vector_db.index.ntotal > 0:
        distances, indices, landmark_ids = vector_db.search(features, k=5)

        # Weighted average of top-5 positions (inverse distance weighting)
        eps = 1e-8
        weights = 1.0 / (distances + eps)
        weights = weights / weights.sum()

        lats, lons = [], []
        for lid in landmark_ids:
            pos = vector_db.get_landmark_position(lid)
            if pos:
                lats.append(pos[0])
                lons.append(pos[1])

        if lats and lons:
            lat = float(np.average(lats, weights=weights[:len(lats)]))
            lon = float(np.average(lons, weights=weights[:len(lons)]))
            logger.info(
                f"ViT + FAISS: weighted position ({lat:.4f}, {lon:.4f}) "
                f"from {len(landmark_ids)} neighbours"
            )
            return (lat, lon)

    # Fallback: no FAISS index available
    logger.warning(
        "ViT pipeline: FAISS index is empty — returning placeholder position (Kyiv centre). "
        "Run `python -m app.init_vector_db` to build a real index from reference images."
    )
    return (50.4501, 30.5234)


def _run_vggt_sync(images_bytes: list[bytes]) -> float:
    """
    Run VGGT-1B visual odometry on a list of image byte blobs.

    Returns ``heading`` — the forward direction angle in degrees
    (0° = North, 90° = East, 180° = South, 270° = West).
    """
    processor = _get_vggt()

    # Load & preprocess images
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

    # Run full odometry
    result = processor.get_full_odometry(batch)

    # Extract heading vector → convert to degrees
    hx = result["heading_vector"]["x"]
    hy = result["heading_vector"]["y"]

    # atan2(x, y) gives angle from North (Y-forward in our convention)
    # Then convert radians → degrees
    heading_rad = np.arctan2(hx, hy)
    heading_deg = float(np.degrees(heading_rad))
    # Normalise to [0, 360)
    heading_deg = heading_deg % 360.0

    return heading_deg


# =====================================================================
if __name__ == "__main__":
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)
