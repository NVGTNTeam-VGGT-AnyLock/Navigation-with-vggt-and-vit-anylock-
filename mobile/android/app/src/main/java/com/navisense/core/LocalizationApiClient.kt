package com.navisense.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
     * Sends an image file to the backend for localization.
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
                    fileManagerService.logError("Backend returned HTTP ${response.code}: $errorBody")
                    // Retry only on server errors (5xx) and not on client errors (4xx)
                    if (response.code >= 500 && attempt < MAX_RETRIES) {
                        // Exponential backoff before retry
                        val backoffDelay = (INITIAL_RETRY_DELAY_MS * BACKOFF_MULTIPLIER.pow(attempt.toDouble())).toLong()
                        delay(backoffDelay)
                        continue
                    } else {
                        // Client error or final attempt → throw
                        throw IOException("Backend error ${response.code}: $errorBody")
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
}