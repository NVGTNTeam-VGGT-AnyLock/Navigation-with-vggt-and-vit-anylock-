package com.navisense.core

import com.navisense.model.CameraOffset
import com.navisense.model.HeadingVector
import com.navisense.model.TrajectoryPoint
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Data class representing a single landmark returned by the backend.
 */
data class Landmark(
    val id: String,
    val distance: Float,
    val confidence: Float
)

/**
 * Data class representing the position estimation response from the backend
 * (used by the DINOv2 /api/v1/position endpoint).
 */
data class PositionResponse(
    val latitude: Double,
    val longitude: Double,
    val floor: Int,
    val confidence: Double,
    val nearest_landmarks: List<Landmark>
)

/**
 * Response from the ViT-based /api/visual-locate endpoint.
 *
 * @property latitude        WGS‑84 latitude of the matched location.
 * @property longitude       WGS‑84 longitude of the matched location.
 * @property confidence_score A value in (0..1] indicating how confident the
 *                            model is about the match (1 = perfect).
 */
data class VisualLocateResponse(
    val latitude: Double,
    val longitude: Double,
    val confidence_score: Double
)

/**
 * Response from the VGGT-1B visual odometry endpoint (`POST /api/v1/vggt-odometry`).
 *
 * References [CameraOffset] from the model package (which also provides
 * bearing computation via [CameraOffset.toBearingDegrees]).
 *
 * @property status               Status string, typically "success".
 * @property camera_center_offset The 3D metric translation vector [x, y, z]
 *                                representing the relative camera-centre movement
 *                                from the first to the last frame in the sequence.
 */
data class VggtOdometryResponse(
    val status: String,
    val camera_center_offset: CameraOffset
)

// ── Fusion endpoint DTOs ─────────────────────────────────────────────

/**
 * Simplified response from the NaviSense 2.0 fusion endpoint
 * (`POST /api/v1/navigate-fusion`).
 *
 * The backend runs ViT (absolute positioning) and VGGT-1B (visual odometry)
 * **sequentially** and returns a single combined result.
 *
 * @property lat     WGS‑84 latitude estimated by ViT + FAISS.
 * @property lon     WGS‑84 longitude estimated by ViT + FAISS.
 * @property heading Forward direction angle in degrees
 *                   (0° = North, 90° = East, 180° = South, 270° = West).
 */
data class FusionResponse(
    val lat: Double,
    val lon: Double,
    val heading: Double
)

/**
 * WGS‑84 location estimated by the ViT model via FAISS search.
 *
 * @property lat WGS‑84 latitude.
 * @property lng WGS‑84 longitude.
 */
data class CurrentLocation(
    val lat: Double,
    val lng: Double
)

/**
 * Combined response from the fusion endpoint (`POST /api/v1/navigate-fusion`).
 *
 * This single response contains everything needed for navigation:
 * - [currentLocation]: absolute position on the map (from ViT).
 * - [trajectory]: per-frame 3D camera-centre displacement (from VGGT).
 * - [headingVector]: normalised 2D forward direction (from VGGT).
 *
 * @property currentLocation The estimated WGS‑84 coordinates.
 * @property trajectory      List of per-frame 3D displacements relative to
 *                           the first frame.
 * @property headingVector   Normalised 2D direction vector on the ground plane.
 */
data class NavigateFusionResponse(
    val current_location: CurrentLocation,
    val trajectory: List<TrajectoryPoint>,
    val heading_vector: HeadingVector
)


/**
 * Retrofit interface for communicating with the NaviSense backend API.
 * All endpoints are relative to the base URL configured via BuildConfig.BACKEND_URL.
 */
interface NaviSenseApi {

    /**
     * Uploads a JPEG image for visual positioning using DINOv2.
     *
     * @param image Multipart body part containing the image file.
     * @return PositionResponse with estimated coordinates and confidence.
     */
    @Multipart
    @POST("api/v1/position")
    suspend fun uploadImage(@Part image: MultipartBody.Part): Response<PositionResponse>

    /**
     * Visual Place Recognition using **ViT** + FAISS.
     *
     * Sends a captured image together with an optional [locationScope] string
     * (e.g. "Kyiv", "Shevchenkivskyi") to narrow the search space.
     *
     * @param image         Multipart body part containing the JPEG image file.
     * @param locationScope RequestBody containing the confirmed location scope
     *                      as a form parameter, or an empty string for a full-world search.
     * @return VisualLocateResponse with estimated coordinates and confidence score.
     */
    @Multipart
    @POST("api/visual-locate")
    suspend fun visualLocate(
        @Part image: MultipartBody.Part,
        @Part("location_scope") locationScope: RequestBody
    ): Response<VisualLocateResponse>

    /**
     * Visual Odometry using **VGGT-1B**.
     *
     * Sends a **sequence** of 2+ JPEG images to compute the relative
     * camera-centre offset between the first and last frame.
     *
     * **Critical:** Each part must be created with form field name `"files"`
     * (not `"image"`) to match the backend signature:
     * `files: List[FixedUploadFile] = File(...)`.
     *
     * @param files List of MultipartBody.Part, each created via
     *              `FileManagerService.prepareImagePart(file, fieldName = "files")`.
     * @return VggtOdometryResponse with status and camera_center_offset.
     */
    @Multipart
    @POST("api/v1/vggt-odometry")
    suspend fun vggtOdometry(
        @Part files: List<MultipartBody.Part>
    ): Response<VggtOdometryResponse>

    /**
     * **Fused visual navigation** — absolute position (ViT) + visual odometry
     * (VGGT-1B) in a single request.
     *
     * Sends **4 images** as a single multipart request. The backend runs
     * both models **sequentially** (ViT → empty_cache → VGGT → empty_cache)
     * to prevent CUDA OOM errors.
     *
     * **Critical:** Each part must be created with form field name `"files"`
     * to match the backend signature:
     * `files: List[FixedUploadFile] = File(...)`.
     *
     * @param files List of MultipartBody.Part (exactly 4),
     *              each created via
     *              `FileManagerService.prepareImagePart(file, fieldName = "files")`.
     * @return [FusionResponse] with lat, lon, and heading.
     */
    @Multipart
    @POST("api/v1/navigate-fusion")
    suspend fun navigateFusion(
        @Part files: List<MultipartBody.Part>
    ): Response<FusionResponse>

}