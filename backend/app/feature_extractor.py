import torch
import torchvision.transforms as T
from transformers import AutoImageProcessor, AutoModel
from PIL import Image
import numpy as np
import logging
import io
from typing import Optional

logger = logging.getLogger(__name__)

# ── Supported model configurations ─────────────────────────────────────
MODEL_REGISTRY = {
    "dinov2": {
        "description": "Meta DINOv2 (ViT-B/14) — 768-dim, used by existing /api/v1/position",
        "default_model": "facebook/dinov2-base",
        "feature_dim": 768,
    },
    "vit": {
        "description": "Google ViT (ViT-B/16) — 768-dim, used by /api/visual-locate",
        "default_model": "google/vit-base-patch16-224",
        "feature_dim": 768,
    },
}


class FeatureExtractor:
    """
    Unified wrapper for HuggingFace Vision Transformer models.
    
    Supports both DINOv2 and ViT architectures via the ``model_type``
    parameter.  Feature extraction always returns an L2-normalised
    768-dimensional vector.
    """
    
    def __init__(
        self,
        model_type: str = "dinov2",
        model_name: Optional[str] = None,
        device: Optional[str] = None,
    ):
        """
        Args:
            model_type: ``"dinov2"`` (default) or ``"vit"``.
            model_name: Hugging Face model ID.  If ``None``, uses the
                default from :data:`MODEL_REGISTRY`.
            device: ``"cuda"`` or ``"cpu"``.  Auto-detected when ``None``.
        """
        if model_type not in MODEL_REGISTRY:
            raise ValueError(
                f"Unsupported model_type '{model_type}'. "
                f"Choose from: {list(MODEL_REGISTRY.keys())}"
            )
        self.model_type = model_type
        self.model_name = model_name or MODEL_REGISTRY[model_type]["default_model"]
        self.feature_dim = MODEL_REGISTRY[model_type]["feature_dim"]
        self.device = device if device else ("cuda" if torch.cuda.is_available() else "cpu")

        logger.info(
            f"Loading {model_type} model '{self.model_name}' "
            f"(dim={self.feature_dim}) on device '{self.device}'"
        )

        self.processor = AutoImageProcessor.from_pretrained(self.model_name)
        self.model = AutoModel.from_pretrained(self.model_name).to(self.device)
        self.model.eval()

        # Standard ImageNet normalisation (used by both DINOv2 and ViT)
        self.transform = T.Compose([
            T.Resize((224, 224)),
            T.ToTensor(),
            T.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
        ])
        logger.info(f"{model_type} model loaded successfully")

    def extract_features(self, image: Image.Image) -> np.ndarray:
        """
        Extract a 768-dimensional feature vector from an RGB PIL Image.
        
        Args:
            image: PIL Image object (RGB).
            
        Returns:
            numpy array of shape ``(feature_dim,)``, L2-normalised.
        """
        input_tensor = self.transform(image).unsqueeze(0).to(self.device)

        with torch.no_grad():
            outputs = self.model(input_tensor)
            # Use the [CLS] token representation (first token)
            features = outputs.last_hidden_state[:, 0, :].cpu().numpy().squeeze()

        # L2 normalise (critical for cosine / inner-product search)
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


# ── Backward-compatible aliases ────────────────────────────────────────
DINOv2FeatureExtractor = FeatureExtractor  # alias for existing imports


# ── Global singleton instances ─────────────────────────────────────────
_extractor: Optional[FeatureExtractor] = None
_vit_extractor: Optional[FeatureExtractor] = None


def get_extractor() -> FeatureExtractor:
    """Singleton getter for the **DINOv2** feature extractor (default)."""
    global _extractor
    if _extractor is None:
        _extractor = FeatureExtractor(model_type="dinov2")
    return _extractor


def get_vit_extractor() -> FeatureExtractor:
    """Singleton getter for the **ViT** feature extractor."""
    global _vit_extractor
    if _vit_extractor is None:
        _vit_extractor = FeatureExtractor(model_type="vit")
    return _vit_extractor