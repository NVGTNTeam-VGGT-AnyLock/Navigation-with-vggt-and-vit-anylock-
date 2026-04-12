import faiss
import numpy as np
import pickle
import logging
from typing import Optional, Tuple, List
import os

logger = logging.getLogger(__name__)

class VectorDatabase:
    """FAISS‑based vector database for landmark feature vectors."""
    
    def __init__(self, dimension: int = 768, index_type: str = "flat_l2"):
        """
        Initialize a FAISS index.
        
        Args:
            dimension: dimensionality of feature vectors (must match DINOv2 output).
            index_type: 'flat_l2' (exact L2) or 'ivf_flat' (approximate).
        """
        self.dimension = dimension
        self.index_type = index_type
        self.index = None
        self.landmark_ids = []  # list of landmark IDs corresponding to index rows
        self.landmark_metadata = {}  # map landmark_id -> (lat, lon, floor, ...)
        
        if index_type == "flat_l2":
            self.index = faiss.IndexFlatL2(dimension)
        elif index_type == "ivf_flat":
            nlist = 100  # number of Voronoi cells
            quantizer = faiss.IndexFlatL2(dimension)
            self.index = faiss.IndexIVFFlat(quantizer, dimension, nlist, faiss.METRIC_L2)
            self.index.nprobe = 10  # number of cells to search
        else:
            raise ValueError(f"Unsupported index type: {index_type}")
        
        logger.info(f"FAISS index created: {index_type}, dimension {dimension}")
    
    def add_vectors(self, vectors: np.ndarray, landmark_ids: List[str], metadata: Optional[dict] = None):
        """
        Add landmark vectors to the index.
        
        Args:
            vectors: numpy array of shape (n, dimension).
            landmark_ids: list of string IDs of length n.
            metadata: optional dict mapping landmark_id to (lat, lon, floor).
        """
        if len(vectors) != len(landmark_ids):
            raise ValueError("Number of vectors must match number of IDs")
        
        # Ensure vectors are float32 as required by FAISS
        vectors = vectors.astype(np.float32)
        self.index.add(vectors)
        
        # Store IDs and metadata
        self.landmark_ids.extend(landmark_ids)
        if metadata:
            self.landmark_metadata.update(metadata)
        
        logger.info(f"Added {len(vectors)} vectors to index. Total size: {self.index.ntotal}")
    
    def search(self, query_vector: np.ndarray, k: int = 5) -> Tuple[np.ndarray, np.ndarray, List[str]]:
        """
        Search for k nearest neighbours.
        
        Args:
            query_vector: query vector of shape (dimension,) or (1, dimension).
            k: number of nearest neighbours to return.
            
        Returns:
            distances: array of shape (k,) with L2 distances.
            indices: array of shape (k,) with integer indices in the index.
            landmark_ids: list of landmark IDs corresponding to indices.
        """
        if self.index.ntotal == 0:
            raise RuntimeError("Index is empty; cannot search.")
        
        query_vector = query_vector.astype(np.float32).reshape(1, -1)
        distances, indices = self.index.search(query_vector, k)
        
        # Map indices to landmark IDs
        landmark_ids = [self.landmark_ids[i] for i in indices[0] if i != -1]
        
        return distances[0], indices[0], landmark_ids
    
    def get_landmark_position(self, landmark_id: str) -> Optional[Tuple[float, float, int]]:
        """Retrieve (latitude, longitude, floor) for a given landmark ID."""
        return self.landmark_metadata.get(landmark_id)
    
    def save(self, filepath: str):
        """Save the index and associated metadata to disk."""
        # Save FAISS index
        faiss.write_index(self.index, filepath + ".index")
        # Save metadata
        with open(filepath + ".meta", "wb") as f:
            pickle.dump({
                "dimension": self.dimension,
                "index_type": self.index_type,
                "landmark_ids": self.landmark_ids,
                "landmark_metadata": self.landmark_metadata
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
            self.landmark_metadata = data["landmark_metadata"]
        
        logger.info(f"Vector database loaded from {filepath}.index/.meta (size {self.index.ntotal})")
    
    def create_demo_index(self, num_landmarks: int = 1000):
        """Create a demo index with random vectors (for testing)."""
        logger.info(f"Creating demo index with {num_landmarks} random landmarks")
        random_vectors = np.random.randn(num_landmarks, self.dimension).astype(np.float32)
        # Normalize vectors (as DINOv2 features are normalized)
        norms = np.linalg.norm(random_vectors, axis=1, keepdims=True)
        random_vectors = random_vectors / norms
        
        landmark_ids = [f"landmark_{i:04d}" for i in range(num_landmarks)]
        metadata = {}
        # Assign random positions around a center (e.g., Kyiv coordinates)
        center_lat, center_lon = 50.4501, 30.5234
        for i, lid in enumerate(landmark_ids):
            # Random offset within ~100 meters
            lat = center_lat + np.random.uniform(-0.001, 0.001)
            lon = center_lon + np.random.uniform(-0.001, 0.001)
            floor = np.random.randint(0, 5)
            metadata[lid] = (lat, lon, floor)
        
        self.add_vectors(random_vectors, landmark_ids, metadata)
        logger.info("Demo index created successfully")

# Global instance
_vector_db: Optional[VectorDatabase] = None

def get_vector_db() -> VectorDatabase:
    """Singleton getter for the vector database."""
    global _vector_db
    if _vector_db is None:
        _vector_db = VectorDatabase(dimension=768, index_type="flat_l2")
        # For demo purposes, populate with random landmarks
        # In production, you would load a pre‑built index from disk
        _vector_db.create_demo_index(1000)
    return _vector_db