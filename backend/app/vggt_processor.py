import sys
import os
import torch
import numpy as np

current_dir = os.path.dirname(os.path.abspath(__file__))
vggt_dir = os.path.join(current_dir, "vggt")
if vggt_dir not in sys.path:
    sys.path.insert(0, vggt_dir)

from vggt.models.vggt import VGGT
from vggt.utils.pose_enc import pose_encoding_to_extri_intri

class VGGTProcessor:
    """
    A server-friendly wrapper around the VGGT-1B model.

    The model processes a batch of image sequences, recovers camera poses,
    and returns the **relative** camera-center coordinates (in world space)
    for the last frame — no 3D meshes, no visualisation, pure positional data.
    """

    def __init__(self, device: str | None = None):
        """
        Load the VGGT-1B model and move it to the target device.

        Args:
            device: Target device string (``"cuda"``, ``"cpu"``, …).
                    Defaults to ``"cuda"`` if available, otherwise ``"cpu"``.
        """
        if device is None:
            device = "cuda" if torch.cuda.is_available() else "cpu"
        self.device = torch.device(device)

        print(f"[VGGTProcessor] Loading model on {self.device} ...")
        self.model = VGGT.from_pretrained("facebook/VGGT-1B")
        self.model.eval()
        self.model = self.model.to(self.device)
        print("[VGGTProcessor] Model loaded successfully.")

    @torch.no_grad()
    def get_relative_position(self, images_tensor: torch.Tensor) -> list[float]:
        """
        Estimate the relative camera position from a sequence of images.

        Args:
            images_tensor: Preprocessed image tensor of shape ``(1, N, 3, 518, 518)``
                           where ``N`` is the number of frames, values in ``[0, 1]``.

        Returns:
            A 3-element ``[x, y, z]`` list representing the camera centre
            of the **last** frame in world coordinates.

        Raises:
            ValueError: If the input tensor does not have 5 dimensions or the batch size is not 1.
        """
        if images_tensor.dim() != 5:
            raise ValueError(
                f"Expected 5D tensor (B, S, C, H, W), got shape {images_tensor.shape}"
            )
        if images_tensor.size(0) != 1:
            raise ValueError(
                f"Expected batch size 1, got {images_tensor.size(0)}"
            )

        # Move to the correct device
        images_tensor = images_tensor.to(self.device)

        # ---- 1. Run the model ------------------------------------------------
        with torch.cuda.amp.autocast(enabled=self.device.type == "cuda"):
            predictions = self.model(images_tensor)

        pose_enc = predictions["pose_enc"]  # shape (1, N, 9)

        # ---- 2. Decode pose encoding → extrinsic / intrinsic matrices --------
        # image_size_hw = (height, width) of the input images
        image_size_hw = (images_tensor.shape[-2], images_tensor.shape[-1])  # (518, 518)
        extrinsics, _ = pose_encoding_to_extri_intri(
            pose_enc, image_size_hw=image_size_hw
        )  # extrinsics shape: (1, N, 3, 4)

        # ---- 3. Extract rotation & translation of the LAST frame ------------
        # extrinsics = [R | t]  in OpenCV convention (camera-from-world)
        R_last = extrinsics[0, -1, :3, :3]  # (3, 3)
        t_last = extrinsics[0, -1, :3, 3]   # (3,)

        # ---- 4. Camera centre in world coordinates --------------------------
        # For an extrinsic [R | t]  that maps world → camera, the camera
        # centre in world space is  C = -R^T @ t
        camera_centre = -R_last.T @ t_last  # (3,)

        # Return as a plain Python list
        return camera_centre.cpu().tolist()
