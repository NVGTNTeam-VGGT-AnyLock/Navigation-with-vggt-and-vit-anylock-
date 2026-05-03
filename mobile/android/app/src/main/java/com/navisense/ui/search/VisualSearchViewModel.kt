package com.navisense.ui.search

import android.app.Application
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.navisense.core.LocalizationApiClient
import com.navisense.core.VisualLocateResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * NaviSense unified debug tag for all Logcat output.
 * Use this constant everywhere so logs can be filtered with a single tag.
 */
private const val NAVISENSE_DEBUG_TAG = "NaviSense_Debug"

/**
 * Represents the parsed address components derived from the last known location.
 *
 * @property district  Sub-administrative area (e.g. "Shevchenkivskyi", "Podilskyi").
 *                     May be `null` if the Geocoder could not resolve this level.
 * @property city      Locality / city (e.g. "Kyiv").
 *                     May be `null` if the Geocoder could not resolve this level.
 * @property country   Country name (e.g. "Ukraine").
 *                     May be `null` if the Geocoder could not resolve this level.
 */
data class LocationInfo(
    val district: String?,
    val city: String?,
    val country: String?
)

/**
 * Finite-state machine for the rough-location confirmation flow.
 *
 * States:
 * - [IDLE] — No photo captured yet; waiting for user action.
 * - [FETCHING_LOCATION] — Fetching the last known GPS position via
 *   [FusedLocationProviderClient].
 * - [RESOLVING_ADDRESS] — Last known location obtained; now reverse-geocoding
 *   via [Geocoder] to extract district / city / country.
 * - [CONFIRM_DISTRICT] — Showing **"Are you currently in [District]?"** dialog.
 * - [CONFIRM_CITY] — District denied; showing **"Are you in [City]?"** dialog.
 * - [CONFIRM_COUNTRY] — City denied; showing **"Are you in [Country]?"** dialog.
 * - [SCOPE_CONFIRMED] — User confirmed one of the above scopes; ready to proceed
 *   with the narrowed-down visual search.
 * - [LOCATION_UNAVAILABLE] — Could not obtain a last known location or the
 *   Geocoder returned no results; fall back to full-world search.
 * - [PERMISSION_DENIED] — Location permission was not granted by the user;
 *   search cannot be narrowed.
 * - [ANALYZING] — Visual search is in progress; show "Analyzing visual data…".
 */
enum class LocationConfirmationState {
    IDLE,
    FETCHING_LOCATION,
    RESOLVING_ADDRESS,
    CONFIRM_DISTRICT,
    CONFIRM_CITY,
    CONFIRM_COUNTRY,
    SCOPE_CONFIRMED,
    LOCATION_UNAVAILABLE,
    PERMISSION_DENIED,
    ANALYZING
}

/**
 * Result of a successful Visual Locate API call.
 * Used to pass coordinates to the MapFragment via the shared MainViewModel.
 *
 * @property latitude    WGS‑84 latitude from the backend.
 * @property longitude   WGS‑84 longitude from the backend.
 * @property confidence  Confidence score returned by the backend (0..1].
 */
data class VisualLocateResult(
    val latitude: Double,
    val longitude: Double,
    val confidence: Double
)

/**
 * ViewModel responsible for the rough-location confirmation flow AND
 * the actual ViT-based visual-locate API call in the [VisualSearchFragment].
 *
 * ## Confirmation Flow
 * 1. [fetchAndConfirmLocation] ⇒ obtains last known GPS → reverse-geocode
 *    → sequential scope dialogs.
 * 2. When scope is confirmed → the fragment calls [performVisualLocate]
 *    with the captured file and confirmed scope.
 *
 * ## Visual Locate Flow
 * 3. [performVisualLocate] sets state to ANALYZING, calls the backend,
 *    and emits the result via [visualLocateResult] or an error via
 *    [visualLocateError].
 *
 * This design works even if GPS is spoofed, because it relies on the
 * **last known good location** cached by the device. The user's manual
 * confirmation compensates for potential staleness.
 */
class VisualSearchViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** Maximum time (ms) to wait for a GPS / fused-location fix. */
        private const val LOCATION_FETCH_TIMEOUT_MS = 5000L

        /** Maximum time (ms) to wait for reverse-geocoding. */
        private const val GEOCODER_TIMEOUT_MS = 5000L
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(application)

    private val localizationApiClient: LocalizationApiClient by lazy {
        LocalizationApiClient.create(getApplication())
    }

    // ─── Exposed State ─────────────────────────────────────────────

    /** Current step in the confirmation state machine. */
    private val _confirmationState = MutableStateFlow(LocationConfirmationState.IDLE)
    val confirmationState: StateFlow<LocationConfirmationState> =
        _confirmationState.asStateFlow()

    /** Parsed location components obtained from the Geocoder. */
    private val _locationInfo = MutableStateFlow<LocationInfo?>(null)
    val locationInfo: StateFlow<LocationInfo?> = _locationInfo.asStateFlow()

    /**
     * The narrowest geographic scope the user has confirmed.
     *
     * Examples: "Shevchenkivskyi" (district), "Kyiv" (city), "Ukraine" (country),
     * or `null` if the user denied everything or location is unavailable.
     */
    private val _confirmedScope = MutableStateFlow<String?>(null)
    val confirmedScope: StateFlow<String?> = _confirmedScope.asStateFlow()

    /** Result of a successful visual-locate API call — observed by the fragment. */
    private val _visualLocateResult = MutableStateFlow<VisualLocateResult?>(null)
    val visualLocateResult: StateFlow<VisualLocateResult?> =
        _visualLocateResult.asStateFlow()

    /** Error message from a failed visual-locate call — observed by the fragment. */
    private val _visualLocateError = MutableStateFlow<String?>(null)
    val visualLocateError: StateFlow<String?> =
        _visualLocateError.asStateFlow()

    /** The raw latitude obtained from [FusedLocationProviderClient]. */
    private var lastLatitude: Double? = null

    /** The raw longitude obtained from [FusedLocationProviderClient]. */
    private var lastLongitude: Double? = null

    // ─── Public API ─────────────────────────────────────────────────

    /**
     * Initiates the location confirmation flow.
     *
     * 1. Checks [Manifest.permission.ACCESS_FINE_LOCATION] or
     *    [Manifest.permission.ACCESS_COARSE_LOCATION].
     * 2. If granted: fetches last known location via [FusedLocationProviderClient]
     *    **with a 5-second timeout** to avoid blocking indefinitely when GPS
     *    is disabled or no cached location exists.
     * 3. Reverse-geocodes via [Geocoder] on a background thread.
     * 4. Updates [confirmationState] to drive the dialog flow in the fragment.
     *
     * Safe to call multiple times — resets all previous state.
     */
    fun fetchAndConfirmLocation() {
        viewModelScope.launch {
            resetState()
            _confirmationState.value = LocationConfirmationState.FETCHING_LOCATION

            val context = getApplication<Application>()
            val hasFineLocation = android.Manifest.permission.ACCESS_FINE_LOCATION
                .let { perm ->
                    android.content.pm.PackageManager.PERMISSION_GRANTED ==
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, perm
                            )
                }
            val hasCoarseLocation = android.Manifest.permission.ACCESS_COARSE_LOCATION
                .let { perm ->
                    android.content.pm.PackageManager.PERMISSION_GRANTED ==
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, perm
                            )
                }

            if (!hasFineLocation && !hasCoarseLocation) {
                Log.e(NAVISENSE_DEBUG_TAG, "Location permission denied — cannot fetch location")
                _confirmationState.value = LocationConfirmationState.PERMISSION_DENIED
                return@launch
            }

            // ── Attempt to get the last known location with a timeout ──
            val location = withContext(Dispatchers.IO) {
                try {
                    withTimeout(LOCATION_FETCH_TIMEOUT_MS) {
                        Tasks.await(fusedLocationClient.lastLocation)
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(
                        NAVISENSE_DEBUG_TAG,
                        "Location fetch timed out after ${LOCATION_FETCH_TIMEOUT_MS}ms — GPS may be disabled or no cached location",
                        e
                    )
                    null
                } catch (e: SecurityException) {
                    Log.e(
                        NAVISENSE_DEBUG_TAG,
                        "SecurityException during location fetch: ${e.message}",
                        e
                    )
                    null
                } catch (e: ExecutionException) {
                    Log.e(
                        NAVISENSE_DEBUG_TAG,
                        "ExecutionException during location fetch: ${e.message}",
                        e
                    )
                    null
                } catch (e: InterruptedException) {
                    Log.e(
                        NAVISENSE_DEBUG_TAG,
                        "InterruptedException during location fetch: ${e.message}",
                        e
                    )
                    null
                } catch (e: Exception) {
                    Log.e(
                        NAVISENSE_DEBUG_TAG,
                        "Unexpected error during location fetch: ${e.message}",
                        e
                    )
                    null
                }
            }

            if (location == null) {
                Log.w(NAVISENSE_DEBUG_TAG, "Location is null after fetch — falling back to full-world search")
                _confirmationState.value = LocationConfirmationState.LOCATION_UNAVAILABLE
                return@launch
            }

            lastLatitude = location.latitude
            lastLongitude = location.longitude

            Log.d(
                NAVISENSE_DEBUG_TAG,
                "Location obtained: lat=${location.latitude}, lon=${location.longitude}"
            )

            // ── Reverse-geocode on background thread ──
            _confirmationState.value = LocationConfirmationState.RESOLVING_ADDRESS

            val address = withContext(Dispatchers.IO) {
                try {
                    withTimeout(GEOCODER_TIMEOUT_MS) {
                        reverseGeocode(context, location.latitude, location.longitude)
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(
                        NAVISENSE_DEBUG_TAG,
                        "Geocoder timed out after ${GEOCODER_TIMEOUT_MS}ms for lat=${location.latitude}, lon=${location.longitude}",
                        e
                    )
                    null
                } catch (e: Exception) {
                    Log.e(
                        NAVISENSE_DEBUG_TAG,
                        "Unexpected error during reverse-geocoding: ${e.message}",
                        e
                    )
                    null
                }
            }

            if (address == null) {
                Log.w(
                    NAVISENSE_DEBUG_TAG,
                    "Geocoder returned no address — falling back to full-world search"
                )
                _confirmationState.value = LocationConfirmationState.LOCATION_UNAVAILABLE
                return@launch
            }

            val info = LocationInfo(
                district = address.subAdminArea,
                city = address.locality,
                country = address.countryName
            )
            _locationInfo.value = info

            Log.d(
                NAVISENSE_DEBUG_TAG,
                "Reverse-geocode result: district=${info.district}, city=${info.city}, country=${info.country}"
            )

            // Start the sequential confirmation dialogs
            when {
                !info.district.isNullOrBlank() -> {
                    _confirmationState.value = LocationConfirmationState.CONFIRM_DISTRICT
                }
                !info.city.isNullOrBlank() -> {
                    _confirmationState.value = LocationConfirmationState.CONFIRM_CITY
                }
                !info.country.isNullOrBlank() -> {
                    _confirmationState.value = LocationConfirmationState.CONFIRM_COUNTRY
                }
                else -> {
                    Log.w(
                        NAVISENSE_DEBUG_TAG,
                        "All address components are blank — falling back to full-world search"
                    )
                    _confirmationState.value = LocationConfirmationState.LOCATION_UNAVAILABLE
                }
            }
        }
    }

    /**
     * Called when the user taps **Yes** on the current confirmation dialog.
     *
     * Saves the current scope (district, city, or country) into [confirmedScope]
     * and transitions to [LocationConfirmationState.SCOPE_CONFIRMED].
     */
    fun onScopeConfirmed() {
        val info = _locationInfo.value ?: return
        val scope = when (_confirmationState.value) {
            LocationConfirmationState.CONFIRM_DISTRICT -> info.district
            LocationConfirmationState.CONFIRM_CITY -> info.city
            LocationConfirmationState.CONFIRM_COUNTRY -> info.country
            else -> null
        }
        _confirmedScope.value = scope
        Log.d(NAVISENSE_DEBUG_TAG, "Scope confirmed: $scope")
        _confirmationState.value = LocationConfirmationState.SCOPE_CONFIRMED
    }

    /**
     * Called when the user taps **No** on the current confirmation dialog.
     *
     * Moves to the next broader scope (district → city → country).
     * If all scopes have been denied, falls back to [LocationConfirmationState.SCOPE_CONFIRMED]
     * with a `null` scope (meaning search the entire database).
     */
    fun onScopeDenied() {
        val info = _locationInfo.value ?: return
        val currentState = _confirmationState.value

        Log.d(NAVISENSE_DEBUG_TAG, "Scope denied at state=$currentState")

        when (currentState) {
            LocationConfirmationState.CONFIRM_DISTRICT -> {
                // District denied → try city
                if (!info.city.isNullOrBlank()) {
                    _confirmationState.value = LocationConfirmationState.CONFIRM_CITY
                } else if (!info.country.isNullOrBlank()) {
                    _confirmationState.value = LocationConfirmationState.CONFIRM_COUNTRY
                } else {
                    // No broader scope available — search entire database
                    _confirmedScope.value = null
                    _confirmationState.value = LocationConfirmationState.SCOPE_CONFIRMED
                }
            }
            LocationConfirmationState.CONFIRM_CITY -> {
                // City denied → try country
                if (!info.country.isNullOrBlank()) {
                    _confirmationState.value = LocationConfirmationState.CONFIRM_COUNTRY
                } else {
                    // No broader scope available — search entire database
                    _confirmedScope.value = null
                    _confirmationState.value = LocationConfirmationState.SCOPE_CONFIRMED
                }
            }
            LocationConfirmationState.CONFIRM_COUNTRY -> {
                // Country denied — search entire database
                _confirmedScope.value = null
                _confirmationState.value = LocationConfirmationState.SCOPE_CONFIRMED
            }
            else -> {
                // Should not happen, but handle gracefully
                _confirmedScope.value = null
                _confirmationState.value = LocationConfirmationState.SCOPE_CONFIRMED
            }
        }
    }

    /**
     * Transitions to the [LocationConfirmationState.ANALYZING] state.
     * Call this when the visual search backend call is about to start.
     */
    fun startAnalyzing() {
        Log.d(NAVISENSE_DEBUG_TAG, "Starting analysis phase")
        _confirmationState.value = LocationConfirmationState.ANALYZING
    }

    /**
     * Performs the actual ViT-based visual-locate API call.
     *
     * 1. Sets state to ANALYZING (triggers loading overlay in fragment).
     * 2. Calls [LocalizationApiClient.visualLocate] with the captured file
     *    and the confirmed scope.
     * 3. On success: emits [VisualLocateResult] via [visualLocateResult].
     * 4. On failure: emits an error message via [visualLocateError].
     *
     * The fragment observes these flows and handles navigation / error display.
     *
     * @param file          The captured JPEG file to send to the backend.
     * @param locationScope The confirmed geographic scope string (may be null).
     */
    fun performVisualLocate(file: File, locationScope: String?) {
        viewModelScope.launch {
            // Clear previous results
            _visualLocateResult.value = null
            _visualLocateError.value = null

            // Show loading overlay
            _confirmationState.value = LocationConfirmationState.ANALYZING

            Log.d(
                NAVISENSE_DEBUG_TAG,
                "performVisualLocate: file=${file.name}, scope=$locationScope"
            )

            try {
                val response: VisualLocateResponse = withContext(Dispatchers.IO) {
                    localizationApiClient.visualLocate(file, locationScope)
                }

                // Successful response — store result for the fragment to consume
                _visualLocateResult.value = VisualLocateResult(
                    latitude = response.latitude,
                    longitude = response.longitude,
                    confidence = response.confidence_score
                )

                Log.d(
                    NAVISENSE_DEBUG_TAG,
                    "Visual locate succeeded: lat=${response.latitude}, " +
                            "lon=${response.longitude}, " +
                            "confidence=${response.confidence_score}"
                )

            } catch (e: IOException) {
                val message = e.message ?: "Unknown IO error during visual search"
                Log.e(NAVISENSE_DEBUG_TAG, "Visual locate IO failure: $message", e)
                _visualLocateError.value = message

            } catch (e: Exception) {
                val message = e.message ?: "Unexpected error during visual search"
                Log.e(NAVISENSE_DEBUG_TAG, "Visual locate crashed: $message", e)
                _visualLocateError.value = message
            }
        }
    }

    /**
     * Resets the entire ViewModel state back to [LocationConfirmationState.IDLE].
     * Safe to call at any point to restart the flow.
     */
    fun resetState() {
        Log.d(NAVISENSE_DEBUG_TAG, "Resetting ViewModel state")
        _confirmationState.value = LocationConfirmationState.IDLE
        _locationInfo.value = null
        _confirmedScope.value = null
        lastLatitude = null
        lastLongitude = null
        _visualLocateResult.value = null
        _visualLocateError.value = null
    }

    // ─── Private Helpers ────────────────────────────────────────────

    /**
     * Reverse-geocodes the given [latitude] / [longitude] and returns the
     * first [Address] result, or `null` if the Geocoder is unavailable or
     * returns no results.
     *
     * Runs synchronously — call from a background thread via [withContext].
     */
    private fun reverseGeocode(
        context: Context,
        latitude: Double,
        longitude: Double
    ): Address? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)

            if (addresses.isNullOrEmpty()) {
                Log.w(
                    NAVISENSE_DEBUG_TAG,
                    "Geocoder returned empty/null list for ($latitude, $longitude)"
                )
                null
            } else {
                val address = addresses[0]
                Log.d(
                    NAVISENSE_DEBUG_TAG,
                    "Geocoder result: subAdminArea=${address.subAdminArea}, " +
                            "locality=${address.locality}, " +
                            "countryName=${address.countryName}"
                )
                address
            }
        } catch (e: Exception) {
            // Geocoder may throw on devices without Google Play Services
            // or in airplane mode. Return null gracefully.
            Log.e(
                NAVISENSE_DEBUG_TAG,
                "Geocoder threw exception for ($latitude, $longitude): ${e.message}",
                e
            )
            null
        }
    }
}
