import faiss
import json
import numpy as np
import pickle
import logging
from typing import Dict, List, Optional, Tuple
import os
from pathlib import Path

logger = logging.getLogger(__name__)

# Default path for pre-built FAISS index (created by init_vector_db.py)
_DEFAULT_INDEX_DIR = Path(__file__).resolve().parent.parent / "vector_index"
_DEFAULT_INDEX_PREFIX = str(_DEFAULT_INDEX_DIR / "faiss_index")
_DEFAULT_SCOPES_FILE = _DEFAULT_INDEX_DIR / "faiss_scopes.json"


class VectorDatabase:
    """FAISS‑based vector database for landmark feature vectors."""
    
    def __init__(self, dimension: int = 768, index_type: str = "flat_l2"):
        """
        Initialize a FAISS index.
        
        Args:
            dimension: dimensionality of feature vectors (must match model output).
            index_type: ``'flat_l2'`` (exact L2) or ``'ivf_flat'`` (approximate).
        """
        self.dimension = dimension
        self.index_type = index_type
        self.index = None
        self.landmark_ids: List[str] = []
        self.landmark_metadata: Dict[str, Tuple[float, float, int]] = {}
        self.landmark_scopes: Dict[str, str] = {}  # landmark_id → location_scope

        if index_type == "flat_l2":
            self.index = faiss.IndexFlatL2(dimension)
        elif index_type == "ivf_flat":
            nlist = 100
            quantizer = faiss.IndexFlatL2(dimension)
            self.index = faiss.IndexIVFFlat(quantizer, dimension, nlist, faiss.METRIC_L2)
            self.index.nprobe = 10
        else:
            raise ValueError(f"Unsupported index type: {index_type}")
        
        logger.info(f"FAISS index created: {index_type}, dimension {dimension}")
    
    # ── Index population ──────────────────────────────────────────────
    
    def add_vectors(
        self,
        vectors: np.ndarray,
        landmark_ids: List[str],
        metadata: Optional[dict] = None,
        scopes: Optional[Dict[str, str]] = None,
    ):
        """
        Add landmark vectors to the index.
        
        Args:
            vectors: numpy array of shape ``(n, dimension)``.
            landmark_ids: list of string IDs of length *n*.
            metadata: optional dict mapping landmark_id → ``(lat, lon, floor)``.
            scopes: optional dict mapping landmark_id → location_scope string.
        """
        if len(vectors) != len(landmark_ids):
            raise ValueError("Number of vectors must match number of IDs")
        
        vectors = vectors.astype(np.float32)
        self.index.add(vectors)
        
        self.landmark_ids.extend(landmark_ids)
        if metadata:
            self.landmark_metadata.update(metadata)
        if scopes:
            self.landmark_scopes.update(scopes)
        
        logger.info(f"Added {len(vectors)} vectors. Total size: {self.index.ntotal}")
    
    # ── Search ────────────────────────────────────────────────────────
    
    def search(
        self,
        query_vector: np.ndarray,
        k: int = 5,
        scope_filter: Optional[str] = None,
    ) -> Tuple[np.ndarray, np.ndarray, List[str]]:
        """
        Search for *k* nearest neighbours, optionally filtered by location scope.
        
        When ``scope_filter`` is provided, the method retrieves ``max(k * 10, 50)``
        candidates first, then discards those whose ``location_scope`` does not
        match, and finally returns the top *k* among the survivors.
        
        Returns:
            distances: array of shape ``(k,)`` with L2 distances.
            indices: array of shape ``(k,)`` with integer indices in the index.
            landmark_ids: list of landmark IDs corresponding to indices.
        """
        if self.index.ntotal == 0:
            raise RuntimeError("Index is empty; cannot search.")
        
        query_vector = query_vector.astype(np.float32).reshape(1, -1)

        if scope_filter:
            # Retrieve a larger candidate pool so scope filtering still yields k results
            pool_k = max(k * 10, 50)
            distances, indices = self.index.search(query_vector, pool_k)
            
            filtered_dists: List[float] = []
            filtered_ids: List[str] = []
            for dist, idx in zip(distances[0], indices[0]):
                if idx == -1:
                    continue
                lid = self.landmark_ids[idx]
                # Check scope match (case-insensitive substring match)
                lid_scope = self.landmark_scopes.get(lid, "")
                if scope_filter.lower() in lid_scope.lower():
                    filtered_dists.append(dist)
                    filtered_ids.append(lid)
                if len(filtered_ids) >= k:
                    break
            
            if not filtered_ids:
                logger.warning(
                    f"No results match scope '{scope_filter}'. "
                    "Returning top unfiltered results as fallback."
                )
                # Fallback: return unfiltered top-k
                distances, indices = self.index.search(query_vector, k)
                landmark_ids = [self.landmark_ids[i] for i in indices[0] if i != -1]
                return distances[0], indices[0], landmark_ids

            # Pad with unfiltered results if not enough scope matches
            if len(filtered_ids) < k:
                extra_k = k - len(filtered_ids)
                extra_distances, extra_indices = self.index.search(query_vector, extra_k)
                for dist, idx in zip(extra_distances[0], extra_indices[0]):
                    if idx == -1:
                        continue
                    lid = self.landmark_ids[idx]
                    if lid not in filtered_ids:
                        filtered_dists.append(dist)
                        filtered_ids.append(lid)
                    if len(filtered_ids) >= k:
                        break

            return (
                np.array(filtered_dists[:k], dtype=np.float32),
                np.zeros(k, dtype=np.int64),  # indices are meaningless after filter
                filtered_ids[:k],
            )
        else:
            distances, indices = self.index.search(query_vector, k)
            landmark_ids = [self.landmark_ids[i] for i in indices[0] if i != -1]
            return distances[0], indices[0], landmark_ids
    
    # ── Metadata accessors ────────────────────────────────────────────
    
    def get_landmark_position(self, landmark_id: str) -> Optional[Tuple[float, float, int]]:
        """Retrieve ``(latitude, longitude, floor)`` for a given landmark ID."""
        return self.landmark_metadata.get(landmark_id)
    
    def get_landmark_scope(self, landmark_id: str) -> str:
        """Retrieve location scope for a given landmark ID (empty string if absent)."""
        return self.landmark_scopes.get(landmark_id, "")
    
    # ── Persistence ───────────────────────────────────────────────────
    
    def save(self, filepath: str):
        """Save the index and associated metadata to disk."""
        faiss.write_index(self.index, filepath + ".index")
        with open(filepath + ".meta", "wb") as f:
            pickle.dump({
                "dimension": self.dimension,
                "index_type": self.index_type,
                "landmark_ids": self.landmark_ids,
                "landmark_metadata": self.landmark_metadata,
                "landmark_scopes": self.landmark_scopes,
            }, f)
        logger.info(f"Vector database saved to {filepath}.index/.meta")
    
    def load(self, filepath: str):
        """Load index and metadata from disk."""
        if not os.path.exists(filepath + ".index"):
            raise FileNotFoundError(f"Index file {filepath}.index not found")
        
        self.index = faiss.read_index(filepath + ".index")
        with open(filepath + ".meta", "rb") as f:
            data = pickle.load(f)
            self.dimension = data["dimension"]
            self.index_type = data["index_type"]
            self.landmark_ids = data["landmark_ids"]
            self.landmark_metadata = data.get("landmark_metadata", {})
            self.landmark_scopes = data.get("landmark_scopes", {})
        
        # Also try to load scopes from JSON (for human readability)
        scopes_file = Path(filepath).parent / "faiss_scopes.json"
        if scopes_file.exists() and not self.landmark_scopes:
            with open(scopes_file, "r", encoding="utf-8") as f:
                scope_data = json.load(f)
            for lid, info in scope_data.items():
                self.landmark_scopes[lid] = info.get("location_scope", "")
        
        logger.info(
            f"Vector database loaded from {filepath}.index/.meta "
            f"(size {self.index.ntotal}, {len(self.landmark_scopes)} scopes)"
        )
    
    # ── Demo index ────────────────────────────────────────────────────
    
    def create_demo_index(self, num_landmarks: int = 1000):
        """Create a demo index with random vectors (for testing)."""
        logger.info(f"Creating demo index with {num_landmarks} random landmarks")
        random_vectors = np.random.randn(num_landmarks, self.dimension).astype(np.float32)
        norms = np.linalg.norm(random_vectors, axis=1, keepdims=True)
        random_vectors = random_vectors / norms
        
        landmark_ids = [f"landmark_{i:04d}" for i in range(num_landmarks)]
        metadata = {}
        scopes: Dict[str, str] = {}
        center_lat, center_lon = 50.4501, 30.5234
        districts = ["Nyvky District", "Pechersk District", "Podil District", "Obolon District"]
        for i, lid in enumerate(landmark_ids):
            lat = center_lat + np.random.uniform(-0.001, 0.001)
            lon = center_lon + np.random.uniform(-0.001, 0.001)
            floor = np.random.randint(0, 5)
            metadata[lid] = (lat, lon, floor)
            scopes[lid] = np.random.choice(districts)
        
        self.add_vectors(random_vectors, landmark_ids, metadata, scopes)
        logger.info("Demo index created successfully")


# ── Singleton management ──────────────────────────────────────────────
_vector_db: Optional[VectorDatabase] = None


def get_vector_db(
    index_prefix: Optional[str] = None,
    fallback_to_demo: bool = True,
) -> VectorDatabase:
    """
    Singleton getter for the vector database.
    
    Loading priority:
    1. Pre-built index at *index_prefix* (set by :mod:`app.init_vector_db`).
    2. If not found and *fallback_to_demo* is ``True``, creates a random demo.
    
    Args:
        index_prefix: path prefix (without ``.index``) to load from.
            Defaults to :const:`_DEFAULT_INDEX_PREFIX`.
        fallback_to_demo: if ``True``, create a demo index when no pre-built
            index exists.
    """
    global _vector_db
    if _vector_db is not None:
        return _vector_db

    prefix = index_prefix or _DEFAULT_INDEX_PREFIX

    # Try to load pre-built index
    if os.path.exists(prefix + ".index"):
        _vector_db = VectorDatabase(dimension=768, index_type="flat_l2")
        _vector_db.load(prefix)
        return _vector_db

    # Fallback to demo
    if fallback_to_demo:
        logger.warning(
            f"Pre-built index not found at {prefix}.index. "
            "Falling back to random demo index."
        )
        logger.warning(
            "Run `python -m app.init_vector_db` to build a real index "
            "from reference images."
        )
        _vector_db = VectorDatabase(dimension=768, index_type="flat_l2")
        _vector_db.create_demo_index(1000)
        return _vector_db

    raise FileNotFoundError(
        f"Index file {prefix}.index not found and fallback_to_demo=False"
    )


def get_vit_vector_db(
    index_prefix: Optional[str] = None,
    fallback_to_demo: bool = True,
) -> VectorDatabase:
    """
    Singleton getter for the **ViT-based** vector database.
    
    Separate singleton from :func:`get_vector_db` so the DINOv2-based
    index (used by ``/api/v1/position``) and the ViT-based index (used by
    ``/api/visual-locate``) can coexist.
    """
    global _vector_db  # reuse same global but with separate loading
    # We use a module-level flag to distinguish
    if not hasattr(get_vit_vector_db, "_vit_db"):
        prefix = index_prefix or _DEFAULT_INDEX_PREFIX
        if os.path.exists(prefix + ".index"):
            db = VectorDatabase(dimension=768, index_type="flat_l2")
            db.load(prefix)
            get_vit_vector_db._vit_db = db
        elif fallback_to_demo:
            logger.warning(
                f"Pre-built index not found at {prefix}.index. "
                "Falling back to random demo index."
            )
            db = VectorDatabase(dimension=768, index_type="flat_l2")
            db.create_demo_index(1000)
            get_vit_vector_db._vit_db = db
        else:
            raise FileNotFoundError(
                f"Index file {prefix}.index not found and fallback_to_demo=False"
            )
    return get_vit_vector_db._vit_db