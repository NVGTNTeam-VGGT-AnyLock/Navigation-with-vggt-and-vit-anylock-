# NaviSense Backend

Visual positioning backend using DINOv2 and FAISS. Provides a FastAPI REST API for estimating location from a single JPEG image.

## Features

- **FastAPI** with automatic OpenAPI documentation
- **DINOv2** (Facebook's self‑supervised vision transformer) for feature extraction
- **FAISS** vector similarity search over a landmark database
- **Mock mode** for testing without GPU or heavy dependencies
- **Docker** support for easy deployment

## Architecture

The backend implements the following flow:

1. Accept JPEG image upload via `POST /api/v1/position`
2. Validate file type and size
3. Extract a 768‑dimensional feature vector using DINOv2‑base
4. Search the FAISS index for the K nearest landmark vectors
5. Compute weighted average of landmark positions (latitude, longitude, floor)
6. Return estimated position with confidence score

## Installation

### Prerequisites

- Python 3.10+ (3.14 not fully supported due to dependency compatibility)
- pip (Python package manager)
- (Optional) CUDA‑enabled GPU for faster DINOv2 inference

### Using a Virtual Environment (Recommended)

```bash
cd backend
python -m venv venv
source venv/bin/activate   # On Windows: venv\Scripts\activate
pip install -r requirements.txt
```

### Installing Dependencies

If you encounter installation issues (especially on Windows with Python 3.14), you can use the provided Docker image (see below) or install the core dependencies manually:

```bash
pip install fastapi uvicorn pillow numpy
```

For ML dependencies (torch, transformers, faiss‑cpu) you may need to install them from pre‑built wheels that match your Python version. The `requirements.txt` includes tested versions; adjust as needed.

## Running the Server

### Development (with mock mode)

If torch/transformers/faiss are not installed, the server automatically falls back to mock implementations that return random positions. This is useful for testing the API without the ML stack.

```bash
cd backend
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

The API will be available at `http://localhost:8000`.

- Interactive API docs: `http://localhost:8000/docs`
- Health check: `GET http://localhost:8000/api/v1/health`

### Production (with full ML stack)

Ensure all dependencies are installed (see above). The server will automatically load the DINOv2 model and a demo FAISS index (random landmarks). For a real deployment, you must replace the demo index with your own landmark vectors.

```bash
cd backend
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## API Endpoints

### `GET /api/v1/health`

Returns `{"status": "ok"}` if the backend is ready.

### `POST /api/v1/position`

Upload a JPEG image to obtain a position estimate.

**Request:**
- `multipart/form‑data` with field `file` containing a JPEG image (max 5 MB).

**Response:**
```json
{
  "latitude": 50.4501,
  "longitude": 30.5234,
  "floor": 1,
  "confidence": 0.85,
  "nearest_landmarks": [
    {"id": "landmark_0001", "distance": 0.12, "confidence": 0.89},
    ...
  ]
}
```

### `POST /api/v1/calibrate` (placeholder)

Reserved for future blur‑detection calibration.

## Landmark Database

The backend includes a `VectorDatabase` class (`app/vector_db.py`) that manages a FAISS index of landmark feature vectors. By default, a demo index with 1000 random landmarks around Kyiv is created on first use.

To use your own landmarks:

1. Prepare a set of reference images with known geographic coordinates.
2. Extract DINOv2 feature vectors for each image (see `app/feature_extractor.py`).
3. Build a FAISS index and save it to disk (methods `VectorDatabase.add_vectors()` and `VectorDatabase.save()`).
4. Load the index at startup by modifying `get_vector_db()` in `app/vector_db.py`.

## Configuration

Environment variables (future extension):

- `USE_MOCK` – force mock mode even if ML dependencies are available.
- `FAISS_INDEX_PATH` – path to a pre‑built FAISS index file.
- `MODEL_NAME` – Hugging Face model identifier (default `facebook/dinov2‑base`).

## Docker

A `Dockerfile` is provided for containerized deployment.

```bash
docker build -t navisense-backend .
docker run -p 8000:8000 navisense-backend
```

## Development

### Project Structure

```
backend/
├── app/
│   ├── __init__.py
│   ├── main.py              # FastAPI application & endpoints
│   ├── feature_extractor.py # DINOv2 feature extraction
│   └── vector_db.py         # FAISS vector database
├── requirements.txt
├── README.md
└── Dockerfile
```

### Running Tests

Tests are not yet implemented. Planned with `pytest`.

## Performance Considerations

- **DINOv2 inference** takes ~100 ms on a GPU, ~500 ms on CPU (depending on hardware).
- **FAISS search** over 100k landmarks is sub‑millisecond on CPU.
- The demo index uses `IndexFlatL2` (exact search). For larger datasets (>100k) consider `IndexIVFFlat`.

## Troubleshooting

- **`ImportError: cannot import name 'get_extractor'`** – Ensure you are running from the `backend` directory and the `app` package is on the Python path.
- **`torch` or `faiss‑cpu` installation fails** – Use Python 3.10–3.12; Python 3.14 may have limited wheel support. Consider using Docker.
- **Pillow installation fails** – Upgrade pip and setuptools: `pip install --upgrade pip setuptools`.

## License

Proprietary – Part of the NaviSense MVP project.

## Contact

Tech Lead – NaviSense Team.