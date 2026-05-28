package com.navisense.core

import com.navisense.model.CameraOffset
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
}