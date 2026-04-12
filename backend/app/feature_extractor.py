import torch
import torchvision.transforms as T
from transformers import AutoImageProcessor, AutoModel
from PIL import Image
import numpy as np
import logging
import io
from typing import Optional

logger = logging.getLogger(__name__)

class DINOv2FeatureExtractor:
    """Wrapper for DINOv2 model to extract feature vectors from images."""
    
    def __init__(self, model_name: str = "facebook/dinov2-base", device: Optional[str] = None):
        """
        Initialize DINOv2 model and processor.
        
        Args:
            model_name: Hugging Face model identifier.
            device: 'cuda' or 'cpu'. If None, auto-selects CUDA if available.
        """
        self.model_name = model_name
        self.device = device if device else ("cuda" if torch.cuda.is_available() else "cpu")
        logger.info(f"Loading DINOv2 model '{model_name}' on device '{self.device}'")
        
        self.processor = AutoImageProcessor.from_pretrained(model_name)
        self.model = AutoModel.from_pretrained(model_name).to(self.device)
        self.model.eval()
        
        # Define image transformation pipeline
        self.transform = T.Compose([
            T.Resize((224, 224)),
            T.ToTensor(),
            T.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
        ])
        logger.info("DINOv2 model loaded successfully")
    
    def extract_features(self, image: Image.Image) -> np.ndarray:
        """
        Extract a 768-dimensional feature vector from an RGB PIL Image.
        
        Args:
            image: PIL Image object (RGB).
            
        Returns:
            numpy array of shape (768,), L2-normalized.
        """
        # Preprocess image
        input_tensor = self.transform(image).unsqueeze(0).to(self.device)
        
        # Forward pass
        with torch.no_grad():
            outputs = self.model(input_tensor)
            # Use the [CLS] token representation (patch tokens are also available)
            features = outputs.last_hidden_state[:, 0, :].cpu().numpy().squeeze()
        
        # L2 normalize the feature vector (common practice for similarity search)
        norm = np.linalg.norm(features)
        if norm > 0:
            features = features / norm
        
        return features.astype(np.float32)
    
    def extract_features_from_file(self, image_path: str) -> np.ndarray:
        """Load image from file and extract features."""
        image = Image.open(image_path).convert("RGB")
        return self.extract_features(image)
    
    def extract_features_from_bytes(self, image_bytes: bytes) -> np.ndarray:
        """Load image from bytes and extract features."""
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        return self.extract_features(image)

# Global instance for reuse
_extractor: Optional[DINOv2FeatureExtractor] = None

def get_extractor() -> DINOv2FeatureExtractor:
    """Singleton getter for the feature extractor."""
    global _extractor
    if _extractor is None:
        _extractor = DINOv2FeatureExtractor()
    return _extractor