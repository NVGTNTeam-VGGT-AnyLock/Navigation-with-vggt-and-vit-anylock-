package com.navisense.core

import okhttp3.MultipartBody
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
 * Data class representing the position estimation response from the backend.
 */
data class PositionResponse(
    val latitude: Double,
    val longitude: Double,
    val floor: Int,
    val confidence: Double,
    val nearest_landmarks: List<Landmark>
)

/**
 * Retrofit interface for communicating with the NaviSense backend API.
 * All endpoints are relative to the base URL configured via BuildConfig.BACKEND_URL.
 */
interface NaviSenseApi {

    /**
     * Uploads a JPEG image for visual positioning.
     *
     * @param image Multipart body part containing the image file.
     * @return PositionResponse with estimated coordinates and confidence.
     */
    @Multipart
    @POST("api/v1/position")
    suspend fun uploadImage(@Part image: MultipartBody.Part): Response<PositionResponse>
}