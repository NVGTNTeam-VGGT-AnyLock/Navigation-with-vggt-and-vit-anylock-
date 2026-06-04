package com.navisense.core

import android.content.Context
import com.navisense.BuildConfig
import com.navisense.model.HeadingVector
import com.navisense.model.TrajectoryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Client responsible for sending image files to the NaviSense backend for visual positioning.
 *
 * Handles network communication, retry logic, and mandatory file cleanup after transmission.
 *
 * @property fileManagerService Service for preparing image parts and deleting temporary files.
 * @property api Retrofit API interface for the backend.
 */
class LocalizationApiClient private constructor(
    private val fileManagerService: FileManagerService,
    private val api: NaviSenseApi
) {

    companion object {
        private const val TAG = "LocalizationApiClient"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L // 1 second
        private const val BACKOFF_MULTIPLIER = 2.0

        /**
         * Creates a new instance using the provided Android context.
         * The base URL is taken from BuildConfig.BACKEND_URL.
         */
        fun create(context: Context): LocalizationApiClient {
            val fileManagerService = FileManagerService(context)
            val okHttpClient = buildOkHttpClient()
            val retrofit = buildRetrofit(okHttpClient)
            val api = retrofit.create(NaviSenseApi::class.java)
            return LocalizationApiClient(fileManagerService, api)
        }

        /**
         * Builds an OkHttpClient with timeouts tuned for mobile connections in poor signal areas.
         */
        private fun buildOkHttpClient(): OkHttpClient {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY // Use Level.NONE in production
            }

            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(loggingInterceptor)
                .build()
        }

        /**
         * Builds a Retrofit instance with the given OkHttpClient.
         */
        private fun buildRetrofit(client: OkHttpClient): Retrofit {
            val baseUrl = BuildConfig.BACKEND_URL // Ensure this is defined in your build.gradle
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
    }

    /**
     * Sends an image file to the backend for localization (DINOv2 endpoint).
     *
     * @param file The JPEG file to upload (must exist in TempScans directory).
     * @return A [PositionResponse] containing the estimated coordinates and confidence.
     * @throws IOException if the network request fails after all retries.
     * @throws FileManagerService.FileManagerException if the file cannot be prepared for upload.
     */
    suspend fun localizeImage(file: File): PositionResponse = withContext(Dispatchers.IO) {
        var lastException: IOException? = null
        var finalResponse: PositionResponse? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                // Prepare multipart image part using FileManagerService (fresh each attempt)
                val imagePart = fileManagerService.prepareImagePart(file)

                // Perform the network request
                val response: Response<PositionResponse> = api.uploadImage(imagePart)

                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    fileManagerService.logError("Backend returned HTTP ${response.code()}: $errorBody")
                    // Retry only on server errors (5xx) and not on client errors (4xx)
                    if (response.code() >= 500 && attempt < MAX_RETRIES) {
                        // Exponential backoff before retry
                        val backoffDelay = (INITIAL_RETRY_DELAY_MS * BACKOFF_MULTIPLIER.pow(attempt.toDouble())).toLong()
                        delay(backoffDelay)
                        continue
                    } else {
                        // Client error or final attempt → throw
                        throw IOException("Backend error ${response.code()}: $errorBody")
                    }
                }

                val positionResponse = response.body()
                    ?: throw IOException("Backend returned empty response body")

                // Successful response – delete the temporary file
                fileManagerService.deleteImage(file)
                finalResponse = positionResponse
                break // Exit loop

            } catch (e: SocketTimeoutException) {
                lastException = e
                fileManagerService.logError("Network timeout on attempt ${attempt + 1}: ${e.message}")
                if (attempt == MAX_RETRIES) break
                val backoffDelay = (INITIAL_RETRY_DELAY_MS * BACKOFF_MULTIPLIER.pow(attempt.toDouble())).toLong()
                delay(backoffDelay)
            } catch (e: IOException) {
                lastException = e
                fileManagerService.logError("Network I/O error on attempt ${attempt + 1}: ${e.message}")
                if (attempt == MAX_RETRIES) break
                val backoffDelay = (INITIAL_RETRY_DELAY_MS * BACKOFF_MULTIPLIER.pow(attempt.toDouble())).toLong()
                delay(backoffDelay)
            } catch (e: Exception) {
                // Unexpected exceptions (e.g., FileManagerException) are not retried
                fileManagerService.logError("Unexpected error during localization: ${e.message}")
                throw e
            }
        }

        // After loop, either we have a successful response or we've exhausted retries
        return@withContext finalResponse ?: run {
            // Delete the file after final failure
            fileManagerService.deleteImage(file)
            throw lastException ?: IOException("Localization failed after $MAX_RETRIES retries")
        }
    }

    /**
     * Sends an image file plus a location-scope string to the ViT-based
     * `/api/visual-locate` endpoint for visual place recognition.
     *
     * @param file          The JPEG file to upload (must exist in TempScans directory).
     * @param locationScope A string narrowing the search (e.g. "Kyiv", "Shevchenkivskyi").
     *                      Pass `null` or empty string for a full-world search.
     * @return A [VisualLocateResponse] containing the estimated coordinates and confidence.
     * @throws IOException if the network request fails after all retries.
     * @throws FileManagerService.FileManagerException if the file cannot be prepared for upload.
     */
    suspend fun visualLocate(
        file: File,
        locationScope: String?
    ): VisualLocateResponse = withContext(Dispatchers.IO) {
        var lastException: IOException? = null
        var finalResponse: VisualLocateResponse? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                // Prepare multipart image part — backend expects field name "file"
                // (matching the FastAPI parameter: file: UploadFile = File(...))
                val imagePart = fileManagerService.prepareImagePart(file, fieldName = "file")

                // Convert the location_scope string to a RequestBody form part
                val scopeValue = locationScope ?: ""
                val scopeBody: RequestBody = scopeValue
                    .toRequestBody("text/plain".toMediaTypeOrNull())

                // Perform the network request
                val response: Response<VisualLocateResponse> =
                    api.visualLocate(imagePart, scopeBody)

                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    fileManagerService.logError(
                        "Backend returned HTTP ${response.code()}: $errorBody"
                    )
                    // Retry only on server errors (5xx) and not on client errors (4xx)
                    if (response.code() >= 500 && attempt < MAX_RETRIES) {
                        val backoffDelay = (
                                INITIAL_RETRY_DELAY_MS *
                                        BACKOFF_MULTIPLIER.pow(attempt.toDouble())
                                ).toLong()
                        delay(backoffDelay)
                        continue
                    } else {
                        when (response.code()) {
                            404 -> throw IOException("Location not recognized in database")
                            else -> throw IOException("Backend error ${response.code()}: $errorBody")
                        }
                    }
                }

                val locateResponse = response.body()
                    ?: throw IOException("Backend returned empty response body")

                // Successful response – delete the temporary file
                fileManagerService.deleteImage(file)
                finalResponse = locateResponse
                break

            } catch (e: SocketTimeoutException) {
                lastException = e
                fileManagerService.logError(
                    "Network timeout on attempt ${attempt + 1}: ${e.message}"
                )
                if (attempt == MAX_RETRIES) break
                val backoffDelay = (
                        INITIAL_RETRY_DELAY_MS *
                                BACKOFF_MULTIPLIER.pow(attempt.toDouble())
                        ).toLong()
                delay(backoffDelay)
            } catch (e: IOException) {
                lastException = e
                fileManagerService.logError(
                    "Network I/O error on attempt ${attempt + 1}: ${e.message}"
                )
                if (attempt == MAX_RETRIES) break
                val backoffDelay = (
                        INITIAL_RETRY_DELAY_MS *
                                BACKOFF_MULTIPLIER.pow(attempt.toDouble())
                        ).toLong()
                delay(backoffDelay)
            } catch (e: Exception) {
                fileManagerService.logError(
                    "Unexpected error during visual locate: ${e.message}"
                )
                throw e
            }
        }

        return@withContext finalResponse ?: run {
            fileManagerService.deleteImage(file)
            throw lastException
                ?: IOException("Visual locate failed after $MAX_RETRIES retries")
        }
    }

    /**
     * Sends a **sequence** of JPEG images to the VGGT-1B visual odometry endpoint
     * (`POST /api/v1/vggt-odometry`) for relative camera-centre offset estimation.
     *
     * Each file is prepared as a `MultipartBody.Part` with the form field name
     * `"files"` (matching the backend signature
     * `files: List[FixedUploadFile] = File(...)`).
     *
     * ## Retry Policy
     * Identical to [visualLocate]: up to [MAX_RETRIES] attempts with exponential
     * backoff (base 1s, multiplier 2×). Server errors (5xx) and network timeouts
     * are retried; client errors (4xx) are not.
     *
     * ## Cleanup
     * All temporary files are deleted regardless of success or failure, enforcing
     * the "no image remains on device longer than 5 minutes" security policy.
     *
     * @param files The list of JPEG files to upload (typically 4 burst captures).
     *              All files must exist in the TempScans directory.
     * @return A [VggtOdometryResponse] containing the status and camera-centre offset.
     * @throws IOException if the network request fails after all retries.
     * @throws FileManagerService.FileManagerException if any file cannot be prepared.
     */
    suspend fun vggtOdometry(files: List<File>): VggtOdometryResponse = withContext(Dispatchers.IO) {
        var lastException: IOException? = null
        var finalResponse: VggtOdometryResponse? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                // Prepare each file as a multipart part with field name "files"
                val imageParts = files.map { file ->
                    fileManagerService.prepareImagePart(file, fieldName = "files")
                }

                // Perform the network request
                val response: Response<VggtOdometryResponse> = api.vggtOdometry(imageParts)

                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    fileManagerService.logError(
                        "VGGT odometry returned HTTP ${response.code()}: $errorBody"
                    )
                    // Retry only on server errors (5xx) and not on client errors (4xx)
                    if (response.code() >= 500 && attempt < MAX_RETRIES) {
                        val backoffDelay = (
                                INITIAL_RETRY_DELAY_MS *
                                        BACKOFF_MULTIPLIER.pow(attempt.toDouble())
                                ).toLong()
                        delay(backoffDelay)
                        continue
                    } else {
                        when (response.code()) {
                            400 -> throw IOException(
                                "VGGT odometry requires at least 2 images (got ${files.size})"
                            )
                            else -> throw IOException(
                                "Backend error ${response.code()}: $errorBody"
                            )
                        }
                    }
                }

                val odometryResponse = response.body()
                    ?: throw IOException("Backend returned empty response body")

                // Successful response – delete all temporary files
                files.forEach { fileManagerService.deleteImage(it) }
                finalResponse = odometryResponse
                break

            } catch (e: SocketTimeoutException) {
                lastException = e
                fileManagerService.logError(
                    "VGGT odometry timeout on attempt ${attempt + 1}: ${e.message}"
                )
                if (attempt == MAX_RETRIES) break
                val backoffDelay = (
                        INITIAL_RETRY_DELAY_MS *
                                BACKOFF_MULTIPLIER.pow(attempt.toDouble())
                        ).toLong()
                delay(backoffDelay)
            } catch (e: IOException) {
                lastException = e
                fileManagerService.logError(
                    "VGGT odometry I/O error on attempt ${attempt + 1}: ${e.message}"
                )
                if (attempt == MAX_RETRIES) break
                val backoffDelay = (
                        INITIAL_RETRY_DELAY_MS *
                                BACKOFF_MULTIPLIER.pow(attempt.toDouble())
                        ).toLong()
                delay(backoffDelay)
            } catch (e: Exception) {
                fileManagerService.logError(
                    "Unexpected error during VGGT odometry: ${e.message}"
                )
                throw e
            }
        }

        return@withContext finalResponse ?: run {
            // Delete all files after final failure
            files.forEach { fileManagerService.deleteImage(it) }
            throw lastException
                ?: IOException("VGGT odometry failed after $MAX_RETRIES retries")
        }
    }

    /**
     * **Fused visual navigation** — sends 4 images in a single request to
     * ``POST /api/v1/navigate-fusion``.
     *
     * The backend runs ViT (absolute position) and VGGT-1B (visual odometry)
     * **sequentially** (ViT → empty_cache → VGGT → empty_cache) to prevent
     * CUDA Out-Of-Memory errors on the server.
     *
     * ## Retry Policy
     * Same as [vggtOdometry]: up to [MAX_RETRIES] attempts with exponential
     * backoff (base 1s, multiplier 2×). Server errors (5xx) and network timeouts
     * are retried; client errors (4xx) are not.
     *
     * ## Cleanup
     * All temporary files are deleted regardless of success or failure.
     *
     * @param files The list of JPEG files to upload (exactly 4 burst captures).
     *              All files must exist in the TempScans directory.
     * @return [FusionResponse] with lat, lon, and heading.
     * @throws IOException if the network request fails after all retries.
     * @throws FileManagerService.FileManagerException if any file cannot be prepared.
     */
    suspend fun navigateFusion(files: List<File>): FusionResponse = withContext(Dispatchers.IO) {
        var lastException: IOException? = null
        var finalResponse: FusionResponse? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                // Prepare each file as a multipart part with field name "files"
                val imageParts = files.map { file ->
                    fileManagerService.prepareImagePart(file, fieldName = "files")
                }

                // Perform the network request
                val response: Response<FusionResponse> = api.navigateFusion(imageParts)

                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    fileManagerService.logError(
                        "Navigate-fusion returned HTTP ${response.code()}: $errorBody"
                    )
                    // Retry only on server errors (5xx) and not on client errors (4xx)
                    if (response.code() >= 500 && attempt < MAX_RETRIES) {
                        val backoffDelay = (
                                INITIAL_RETRY_DELAY_MS *
                                        BACKOFF_MULTIPLIER.pow(attempt.toDouble())
                                ).toLong()
                        delay(backoffDelay)
                        continue
                    } else {
                        when (response.code()) {
                            400 -> throw IOException(
                                "Navigate-fusion requires exactly 4 images (got ${files.size})"
                            )
                            404 -> throw IOException("Location not recognized in database")
                            else -> throw IOException(
                                "Backend error ${response.code()}: $errorBody"
                            )
                        }
                    }
                }

                val fusionResponse = response.body()
                    ?: throw IOException("Backend returned empty response body")

                // Successful response – delete all temporary files
                files.forEach { fileManagerService.deleteImage(it) }
                finalResponse = fusionResponse
                break

            } catch (e: SocketTimeoutException) {
                lastException = e
                fileManagerService.logError(
                    "Navigate-fusion timeout on attempt ${attempt + 1}: ${e.message}"
                )
                if (attempt == MAX_RETRIES) break
                val backoffDelay = (
                        INITIAL_RETRY_DELAY_MS *
                                BACKOFF_MULTIPLIER.pow(attempt.toDouble())
                        ).toLong()
                delay(backoffDelay)
            } catch (e: IOException) {
                lastException = e
                fileManagerService.logError(
                    "Navigate-fusion I/O error on attempt ${attempt + 1}: ${e.message}"
                )
                if (attempt == MAX_RETRIES) break
                val backoffDelay = (
                        INITIAL_RETRY_DELAY_MS *
                                BACKOFF_MULTIPLIER.pow(attempt.toDouble())
                        ).toLong()
                delay(backoffDelay)
            } catch (e: Exception) {
                fileManagerService.logError(
                    "Unexpected error during navigate-fusion: ${e.message}"
                )
                throw e
            }
        }

        return@withContext finalResponse ?: run {
            // Delete all files after final failure
            files.forEach { fileManagerService.deleteImage(it) }
            throw lastException
                ?: IOException("Navigate-fusion failed after $MAX_RETRIES retries")
        }
    }

}