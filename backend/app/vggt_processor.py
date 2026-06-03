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
    for every frame — no 3D meshes, no visualisation, pure positional data.
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
        image_size_hw = (images_tensor.shape[-2], images_tensor.shape[-1])  # (518, 518)
        extrinsics, _ = pose_encoding_to_extri_intri(
            pose_enc, image_size_hw=image_size_hw
        )  # extrinsics shape: (1, N, 3, 4)

        # ---- 3. Extract rotation & translation of the LAST frame ------------
        R_last = extrinsics[0, -1, :3, :3]  # (3, 3)
        t_last = extrinsics[0, -1, :3, 3]   # (3,)

        # ---- 4. Camera centre in world coordinates --------------------------
        camera_centre = -R_last.T @ t_last  # (3,)

        return camera_centre.cpu().tolist()

    @torch.no_grad()
    def get_full_odometry(self, images_tensor: torch.Tensor) -> dict:
        """
        Run VGGT-1B inference and return the **full** per-frame camera centres
        (trajectory) plus the heading vector of the last frame.

        Args:
            images_tensor: Preprocessed image tensor of shape ``(1, N, 3, 518, 518)``
                           with values in ``[0, 1]``.

        Returns:
            A dict with:
            - ``"trajectory"``: ``list[dict]`` — each dict has ``dx``, ``dy``, ``dz``
              representing the camera-centre displacement of that frame **relative
              to the first frame**.
            - ``"heading_vector"``: ``dict`` with keys ``"x"`` and ``"y"`` — the
              normalised 2D forward direction on the ground plane (XZ plane),
              where ``x`` = lateral component, ``y`` = forward (depth) component.

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

        images_tensor = images_tensor.to(self.device)

        # ---- 1. Run the model ------------------------------------------------
        with torch.cuda.amp.autocast(enabled=self.device.type == "cuda"):
            predictions = self.model(images_tensor)

        pose_enc = predictions["pose_enc"]  # (1, N, 9)

        # ---- 2. Decode pose encoding → extrinsic matrices --------------------
        image_size_hw = (images_tensor.shape[-2], images_tensor.shape[-1])
        extrinsics, _ = pose_encoding_to_extri_intri(
            pose_enc, image_size_hw=image_size_hw
        )  # (1, N, 3, 4)

        num_frames = extrinsics.shape[1]

        # ---- 3. Compute camera centre for EVERY frame -----------------------
        # extrinsics[0, i] = [R_i | t_i]  (camera-from-world)
        camera_centres = []
        for i in range(num_frames):
            R_i = extrinsics[0, i, :3, :3]   # (3, 3)
            t_i = extrinsics[0, i, :3, 3]    # (3,)
            C_i = -R_i.T @ t_i                # (3,) world-space camera centre
            camera_centres.append(C_i.cpu())

        # Stack into (N, 3)
        centres = torch.stack(camera_centres, dim=0)  # (N, 3)

        # ---- 4. Build trajectory (displacement relative to first frame) ------
        first_centre = centres[0]  # (3,)
        trajectory = []
        for i in range(num_frames):
            displacement = centres[i] - first_centre
            trajectory.append({
                "dx": round(float(displacement[0].item()), 6),
                "dy": round(float(displacement[1].item()), 6),
                "dz": round(float(displacement[2].item()), 6),
            })

        # ---- 5. Heading vector from the LAST frame ---------------------------
        # The last frame's rotation matrix columns define the camera axes.
        # In OpenCV convention:
        #   R = [r_x | r_y | r_z]  where r_z = forward (depth) axis
        # We extract r_z (the third column) and project onto the XZ ground plane,
        # normalising to a unit 2D vector.
        R_last = extrinsics[0, -1, :3, :3]  # (3, 3)
        # Forward axis in camera coordinates is +Z → third column of R
        forward_axis = R_last[:, 2]  # (3,)  — direction the camera is facing

        # Project onto the XZ ground plane (ignore Y / vertical component)
        heading_x = float(forward_axis[0].item())  # lateral component
        heading_z = float(forward_axis[2].item())  # forward (depth) component

        # Normalise to unit vector
        norm_2d = np.sqrt(heading_x**2 + heading_z**2)
        if norm_2d > 1e-8:
            heading_x /= norm_2d
            heading_z /= norm_2d

        return {
            "trajectory": trajectory,
            "heading_vector": {
                "x": round(heading_x, 6),
                "y": round(heading_z, 6),  # we map Z → Y for the 2D response
            },
        }
