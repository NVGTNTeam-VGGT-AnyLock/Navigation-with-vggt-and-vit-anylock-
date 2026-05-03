#!/usr/bin/env python3
"""
Initialise the FAISS vector database from reference images using a ViT model.

Usage
-----
    python -m app.init_vector_db

This script:
1. Loads a pre-trained Vision Transformer (``google/vit-base-patch16-224``).
2. Reads reference images from ``backend/data/reference_images/``.
3. Extracts 768‑dimensional L2‑normalised embeddings.
4. Reads GPS coordinates and location scopes from a companion JSON metadata
   file (``metadata.json`` inside the reference images folder).
5. Builds a FAISS ``IndexFlatL2`` index and saves it together with the
   metadata to ``backend/vector_index/``.

Adding your own test photos
---------------------------
1. Place your JPEG/PNG images into ``backend/data/reference_images/``.
2. Edit ``backend/data/reference_images/metadata.json`` to include an entry
   for each image with its GPS coordinates and location scope:

   .. code-block:: json

       {
           "my_photo_01.jpg": {
               "latitude": 50.4501,
               "longitude": 30.5234,
               "location_scope": "Kyiv"
           },
           "my_photo_02.jpg": {
               "latitude": 50.4583,
               "longitude": 30.5204,
               "location_scope": "Nyvky District"
           }
       }

3. Re-run ``python -m app.init_vector_db`` to rebuild the index.
"""

import argparse
import json
import logging
import os
import sys
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
from PIL import Image

# Ensure the backend package is importable
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.feature_extractor import FeatureExtractor
from app.vector_db import VectorDatabase

# ── Paths ──────────────────────────────────────────────────────────────
PROJECT_ROOT = Path(__file__).resolve().parent.parent  # backend/
DEFAULT_REF_DIR = PROJECT_ROOT / "data" / "reference_images"
DEFAULT_META_FILE = DEFAULT_REF_DIR / "metadata.json"
DEFAULT_INDEX_DIR = PROJECT_ROOT / "vector_index"
DEFAULT_INDEX_PREFIX = DEFAULT_INDEX_DIR / "faiss_index"

# ── Logging ────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("init_vector_db")


# ======================================================================
def load_metadata(meta_path: Path) -> Dict[str, dict]:
    """
    Load the metadata JSON file.

    Returns a dict mapping *filename* (e.g. ``"img_001.jpg"``) to a dict
    with keys ``latitude``, ``longitude``, and optionally ``location_scope``.
    """
    if not meta_path.exists():
        logger.warning(f"Metadata file not found: {meta_path}")
        logger.warning("Will create a minimal metadata template for you.")
        return {}

    with open(meta_path, "r", encoding="utf-8") as f:
        metadata = json.load(f)

    logger.info(f"Loaded metadata for {len(metadata)} images from {meta_path}")
    return metadata


def collect_image_files(ref_dir: Path) -> List[Path]:
    """Return sorted list of supported image files in *ref_dir*."""
    supported = {".jpg", ".jpeg", ".png"}
    files = sorted(
        p for p in ref_dir.iterdir()
        if p.suffix.lower() in supported and not p.name.startswith(".")
    )
    logger.info(f"Found {len(files)} image(s) in {ref_dir}")
    return files


def extract_all_embeddings(
    extractor: FeatureExtractor,
    image_paths: List[Path],
    metadata: Dict[str, dict],
) -> Tuple[np.ndarray, List[str], Dict[str, tuple], Dict[str, str]]:
    """
    Extract embeddings for every image that has a metadata entry.

    Returns
    -------
    vectors : ndarray of shape ``(N, 768)``
    ids : list of landmark IDs (``"img_001"`` etc.)
    positions : dict mapping landmark ID → ``(lat, lon, floor=0)``
    scopes : dict mapping landmark ID → ``location_scope`` string
    """
    vectors: List[np.ndarray] = []
    ids: List[str] = []
    positions: Dict[str, tuple] = {}
    scopes: Dict[str, str] = {}

    for img_path in image_paths:
        stem = img_path.stem  # filename without extension
        fname = img_path.name

        # Look up metadata
        meta_entry = metadata.get(fname)
        if meta_entry is None:
            logger.warning(f"Skipping {fname} — no metadata entry found")
            continue

        lat = meta_entry.get("latitude")
        lon = meta_entry.get("longitude")
        if lat is None or lon is None:
            logger.warning(
                f"Skipping {fname} — metadata missing 'latitude' or 'longitude'"
            )
            continue

        # Extract embedding
        try:
            image = Image.open(img_path).convert("RGB")
            vec = extractor.extract_features(image)
        except Exception as exc:
            logger.error(f"Failed to process {fname}: {exc}")
            continue

        lid = f"ref_{stem}"
        vectors.append(vec)
        ids.append(lid)
        positions[lid] = (lat, lon, 0)  # floor = 0 (outdoor default)

        scope = meta_entry.get("location_scope", "")
        if scope:
            scopes[lid] = scope

        logger.info(f"  ✓ {fname:40s} → {lid:20s}  ({lat:.4f}, {lon:.4f})")

    if not vectors:
        raise RuntimeError(
            "No valid embeddings extracted. "
            "Check that reference_images/ contains images and metadata.json "
            "has valid entries for them."
        )

    return (
        np.stack(vectors).astype(np.float32),
        ids,
        positions,
        scopes,
    )


def build_and_save_index(
    vectors: np.ndarray,
    ids: List[str],
    positions: Dict[str, tuple],
    scopes: Dict[str, str],
    index_prefix: Path,
) -> VectorDatabase:
    """
    Build a FAISS ``IndexFlatL2``, add vectors, and persist to disk.
    Also saves a separate JSON file mapping landmark IDs to scopes.
    """
    dimension = vectors.shape[1]
    logger.info(
        f"Building FAISS index with {len(ids)} vectors (dim={dimension})"
    )

    db = VectorDatabase(dimension=dimension, index_type="flat_l2")
    db.add_vectors(vectors, ids, metadata=positions)

    # Persist FAISS index + pickle metadata
    index_prefix.parent.mkdir(parents=True, exist_ok=True)
    db.save(str(index_prefix.with_suffix("")))

    # Also save a human-readable JSON metadata that includes location_scope
    scope_file = index_prefix.with_name("faiss_scopes.json")
    scope_data = {}
    for lid in ids:
        scope_data[lid] = {
            "position": list(positions.get(lid, (0, 0, 0))),
            "location_scope": scopes.get(lid, ""),
        }
    with open(scope_file, "w", encoding="utf-8") as f:
        json.dump(scope_data, f, indent=2, ensure_ascii=False)
    logger.info(f"Scope metadata saved to {scope_file}")

    return db


def create_metadata_template(ref_dir: Path, meta_path: Path):
    """Scans *ref_dir* and writes a skeleton ``metadata.json`` for the user."""
    images = collect_image_files(ref_dir)
    if not images:
        logger.warning(f"No images found in {ref_dir}; nothing to do.")
        return

    template = {}
    for img_path in images:
        template[img_path.name] = {
            "latitude": 50.4501,
            "longitude": 30.5234,
            "location_scope": "Kyiv",
        }

    meta_path.parent.mkdir(parents=True, exist_ok=True)
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(template, f, indent=2, ensure_ascii=False)

    logger.info(
        f"Template metadata written to {meta_path}. "
        f"Please edit the GPS coordinates for each image before running again."
    )


# ======================================================================
def main():
    parser = argparse.ArgumentParser(
        description="Build a FAISS vector index from reference images using ViT."
    )
    parser.add_argument(
        "--ref-dir",
        type=Path,
        default=DEFAULT_REF_DIR,
        help=f"Directory of reference images (default: {DEFAULT_REF_DIR})",
    )
    parser.add_argument(
        "--metadata",
        type=Path,
        default=DEFAULT_META_FILE,
        help=f"JSON metadata file (default: {DEFAULT_META_FILE})",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_INDEX_PREFIX,
        help=f"Output path prefix for .index / .meta files (default: {DEFAULT_INDEX_PREFIX})",
    )
    parser.add_argument(
        "--create-template",
        action="store_true",
        help="Only create a skeleton metadata.json from images, then exit.",
    )
    args = parser.parse_args()

    # ── Ensure reference directory exists ──────────────────────────────
    args.ref_dir.mkdir(parents=True, exist_ok=True)

    # ── Template mode ──────────────────────────────────────────────────
    if args.create_template:
        create_metadata_template(args.ref_dir, args.metadata)
        return

    # ── Load metadata ──────────────────────────────────────────────────
    metadata = load_metadata(args.metadata)

    # ── Collect image files ────────────────────────────────────────────
    image_paths = collect_image_files(args.ref_dir)

    if not image_paths:
        logger.warning(
            "No images found. To get started:\n"
            f"  1. Place your JPEG/PNG photos in:  {args.ref_dir}\n"
            f"  2. Run:  python -m app.init_vector_db --create-template\n"
            f"  3. Edit: {args.metadata} with actual GPS coordinates\n"
            f"  4. Run:  python -m app.init_vector_db\n"
        )
        return

    # ── Load ViT extractor ─────────────────────────────────────────────
    logger.info("Loading ViT feature extractor (google/vit-base-patch16-224)...")
    extractor = FeatureExtractor(model_type="vit")

    # ── Extract embeddings ─────────────────────────────────────────────
    vectors, ids, positions, scopes = extract_all_embeddings(
        extractor, image_paths, metadata
    )
    logger.info(
        f"Extracted {len(ids)} embeddings — shape: {vectors.shape}"
    )

    # ── Build & save FAISS index ───────────────────────────────────────
    build_and_save_index(vectors, ids, positions, scopes, args.output)

    logger.info("=" * 60)
    logger.info("Vector database initialised successfully!")
    logger.info(f"  Index  : {args.output}.index")
    logger.info(f"  Meta   : {args.output}.meta")
    logger.info(f"  Scopes : {args.output.parent / 'faiss_scopes.json'}")
    logger.info("=" * 60)
    logger.info(
        "To test the visual-locate endpoint:\n"
        f"  1. Start the server:  uvicorn app.main:app --reload --port 8000\n"
        f"  2. Upload a test image to POST /api/visual-locate\n"
    )


if __name__ == "__main__":
    main()
