# NaviSense Backend GPU Optimization Plan

## Goal
Optimize the FastAPI backend to run entirely on NVIDIA GPUs (CUDA) to meet a **2-second latency requirement**.

---

## 1. `backend/requirements.txt` — Dependency Changes

| Change | Rationale |
|--------|-----------|
| `faiss-cpu` → `faiss-gpu` | FAISS GPU enables GPU-accelerated vector search for sub-2ms queries on large indexes. |
| Add `# CUDA-compatible` comment to `torch`/`torchvision` | Ensure user installs the CUDA variant (e.g. `pip install torch==2.1.0+cu121`). No version pin change needed — pip auto-selects CUDA build on CUDA-capable systems. |

**Final `requirements.txt` changes:**
```
faiss-gpu          # instead of faiss-cpu
torch              # ensure CUDA variant installed
torchvision        # ensure CUDA variant installed
transformers       # unchanged
```

---

## 2. `backend/app/feature_extractor.py` — Model & Inference Optimizations

### 2a. FP16 Half-Precision (`.half()`)
```python
# After loading & moving model to device:
self.model = AutoModel.from_pretrained(self.model_name).to(self.device)
if self.device.type == "cuda":
    self.model = self.model.half()       # ← NEW: cast weights to FP16
    logger.info("Model cast to FP16 (half precision)")
```

**Why:** FP16 halves memory bandwidth and doubles throughput on Tensor Cores.

**Caveat:** Only cast to FP16 when device is CUDA. CPU float16 is poorly supported.

### 2b. Input Tensor Dtype Alignment
```python
input_tensor = self.transform(image).unsqueeze(0).to(self.device)
# If model is half-precision, cast input to match:
if self.device.type == "cuda" and next(self.model.parameters()).dtype == torch.float16:
    input_tensor = input_tensor.half()
```

**Why:** Passing float32 input to a float16 model causes silent casting overhead or errors.

### 2c. Replace `torch.no_grad()` → `torch.inference_mode()`
```python
# Before:
with torch.no_grad():
    outputs = self.model(input_tensor)

# After: use decorator on the method
@torch.inference_mode()
def extract_features(self, image: Image.Image) -> np.ndarray:
    ...
    outputs = self.model(input_tensor)    # no need for explicit context manager
    ...
```

**Why:** `inference_mode()` is faster than `no_grad()` because it:
- Disables autograd entirely (like `no_grad()`)
- Also disables input caching and other overheads
- Introduced in PyTorch 1.9, stable since 1.13

### 2d. Feature Scaling for FP16 Stability
Normalised feature vectors in FP16 may lose precision. After `.cpu().numpy()`, the values are already float32, so the L2 normalisation and return remain float32. No change needed.

---

## 3. `backend/app/vector_db.py` — FAISS GPU Acceleration

### 3a. GPU Resource Management
Add to `__init__`:
```python
import faiss
import warnings

class VectorDatabase:
    def __init__(self, dimension: int = 768, index_type: str = "flat_l2"):
        ...
        self.gpu_res = None          # ← NEW: FAISS GPU resources
        self.use_gpu = False         # ← NEW: flag

        # After creating the CPU index:
        self.index = ...             # (existing CPU index creation)

        # Try to move to GPU:
        if faiss.get_num_gpus() > 0:
            try:
                self.gpu_res = faiss.StandardGpuResources()
                self.index = faiss.index_cpu_to_gpu(self.gpu_res, 0, self.index)
                self.use_gpu = True
                logger.info("FAISS index moved to GPU (device 0)")
            except Exception as e:
                logger.warning(f"Failed to move FAISS to GPU: {e}. Falling back to CPU.")
        else:
            logger.info("No GPU found for FAISS. Using CPU index.")
```

### 3b. Save — Convert GPU → CPU Before Serialization
```python
def save(self, filepath: str):
    """Save the index and associated metadata to disk."""
    index_to_write = self.index
    if self.use_gpu:
        index_to_write = faiss.index_gpu_to_cpu(self.index)
    faiss.write_index(index_to_write, filepath + ".index")
    ...
```

### 3c. Load — Convert CPU → GPU After Deserialization
```python
def load(self, filepath: str):
    ...
    self.index = faiss.read_index(filepath + ".index")
    
    # If GPU available, move loaded index to GPU
    if faiss.get_num_gpus() > 0:
        try:
            self.gpu_res = faiss.StandardGpuResources()
            self.index = faiss.index_cpu_to_gpu(self.gpu_res, 0, self.index)
            self.use_gpu = True
            logger.info("Loaded index moved to GPU")
        except Exception as e:
            logger.warning(f"Failed to move loaded index to GPU: {e}")
    ...
```

### 3d. add_vectors / search — Already Work with GPU Index
FAISS GPU index supports `.add()` and `.search()` directly. No changes needed in these methods.

---

## 4. Potential Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| FP16 model may produce slightly different feature vectors | Normalisation and search are robust; differences are within tolerance for top-k results. Test with a benchmark set. |
| `faiss-gpu` version mismatch with CUDA toolkit | Pin `faiss-gpu` to a CUDA-compatible version (e.g. `faiss-gpu==1.7.2`). Provide install instructions. |
| GPU memory exhaustion at inference time | Models are small (ViT-B ≈ 330MB in FP16). FAISS GPU index adds ~3MB per 1000 vectors. Well within typical 4-8GB GPU memory. |
| `init_vector_db.py` also uses `FeatureExtractor` and `VectorDatabase` | Changes propagate automatically. No separate modification needed. |
| Docker image needs CUDA base image | Change `FROM python:3.10-slim` → `FROM nvidia/cuda:12.1.0-runtime-ubuntu22.04` or add CUDA toolkit. Document in Dockerfile update. |

---

## 5. Testing Plan

1. **Unit test**: Start the backend with `uvicorn app.main:app --host 0.0.0.0 --port 8000` and verify health endpoint.
2. **CUDA test**: Verify `torch.cuda.is_available()`, models are on CUDA, FAISS uses GPU.
3. **Inference test**: POST a sample image to `/api/v1/position` and `/api/visual-locate`, verify response schema is unchanged.
4. **Latency check**: Measure end-to-end latency to confirm < 2s target.

---

## 6. Files Modified (Summary)

| File | Changes |
|------|---------|
| `backend/requirements.txt` | `faiss-cpu` → `faiss-gpu` |
| `backend/app/feature_extractor.py` | `.half()`, `@torch.inference_mode()`, input dtype alignment |
| `backend/app/vector_db.py` | `faiss.index_cpu_to_gpu` on init/load, `index_gpu_to_cpu` on save |
