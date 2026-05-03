package com.navisense.ui.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.navisense.R
import com.navisense.core.FileManagerService
import com.navisense.core.ScannerCamera
import com.navisense.databinding.FragmentVisualSearchBinding
import com.navisense.model.AppLocation
import com.navisense.model.AppLocationCategory
import com.navisense.ui.MainViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * NaviSense unified debug tag for all Logcat output.
 */
private const val NAVISENSE_DEBUG_TAG = "NaviSense_Debug"

/**
 * Visual Search screen — CameraX live preview with single-frame capture,
 * blur validation, temporary file storage, rough-location confirmation,
 * and visual positioning via the ViT backend.
 *
 * ## Location Confirmation Flow
 * After the user captures a photo (or picks from gallery), the fragment
 * intercepts the flow **before** navigating to the Map and shows a
 * sequential [AlertDialog] chain:
 *   1. "Are you currently in [District]?" (Yes / No)
 *   2. If No: "Are you in [City]?" (Yes / No)
 *   3. If No: "Are you in [Country]?" (Yes / No)
 *
 * The confirmed scope (narrowest yes) is stored in [VisualSearchViewModel.confirmedScope]
 * and is sent to the backend to narrow the search space.
 *
 * ## API Flow
 * After scope is confirmed, [proceedWithSearch] calls
 * [VisualSearchViewModel.performVisualLocate] which triggers the real
 * ViT-based visual-locate API call. On success, the fragment stores the
 * coordinates in the shared [MainViewModel] and navigates to the Map tab.
 * On failure, a Toast is shown with the error message.
 *
 * ## Camera Flow
 * 1. On fragment creation, **checks both CAMERA and LOCATION permissions**.
 *    If either is missing, shows a "Permissions Required" dialog with
 *    an "Open Settings" button so the user can grant permissions.
 * 2. Once both are granted, instantiates [ScannerCamera] with a live [PreviewView].
 * 3. User taps the capture FAB → [ScannerCamera.captureSharpImage()] is called.
 * 4. On success: the sharp image is saved to `TempScans/` via [FileManagerService],
 *    a toast is shown, and the location confirmation flow begins.
 * 5. After location is confirmed → real API call → navigate to Map.
 * 6. After navigation, `TempScans/` folder is wiped.
 *
 * ## Gallery Flow
 * - Tapping "Upload Photo" opens the system gallery picker.
 * - The selected image triggers the same location confirmation + search flow.
 */
class VisualSearchFragment : Fragment() {

    private var _binding: FragmentVisualSearchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    /** Dedicated ViewModel for the rough-location confirmation flow & API call. */
    private val visualSearchViewModel: VisualSearchViewModel by viewModels()

    // ── Core Services (initialised after permission grant) ──────────
    private var fileManagerService: FileManagerService? = null
    private var scannerCamera: ScannerCamera? = null

    // ── Location Confirmation Dialog ────────────────────────────────
    private var confirmationDialog: AlertDialog? = null

    // ── Permissions Required Dialog ─────────────────────────────────
    private var permissionsRequiredDialog: AlertDialog? = null

    // ── Captured file reference (held across dialog flow) ───────────
    private var pendingCapturedFile: File? = null

    // ── Camera Permission Launcher ──────────────────────────────────
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(NAVISENSE_DEBUG_TAG, "Camera permission granted")
            // Re-check all required permissions after grant
            checkPermissionsAndInitCamera()
        } else {
            Log.w(NAVISENSE_DEBUG_TAG, "Camera permission denied by user")
            binding.tvCameraPlaceholder.visibility = View.VISIBLE
            binding.btnCapture.isEnabled = false
            Toast.makeText(
                requireContext(),
                R.string.permission_camera_denied,
                Toast.LENGTH_SHORT
            ).show()
            // Show the "Permissions Required" dialog with a Settings button
            showPermissionsRequiredDialog()
        }
    }

    // ── Location Permission Launcher (for visual search) ────────────
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(NAVISENSE_DEBUG_TAG, "Location permission granted — retrying fetch")
            // Permission now granted — retry the fetch
            visualSearchViewModel.fetchAndConfirmLocation()
        } else {
            Log.w(NAVISENSE_DEBUG_TAG, "Location permission denied — proceeding with full-world search")
            // User denied location — proceed with full-world search
            Toast.makeText(
                requireContext(),
                R.string.location_permission_denied_visual,
                Toast.LENGTH_LONG
            ).show()
            proceedWithSearch()
        }
    }

    // ── Gallery Picker ──────────────────────────────────────────────
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            Log.d(NAVISENSE_DEBUG_TAG, "Photo picked from gallery: $uri")
            // Photo picked from gallery — start location confirmation
            pendingCapturedFile = null
            visualSearchViewModel.fetchAndConfirmLocation()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVisualSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Log.d(NAVISENSE_DEBUG_TAG, "onViewCreated — initialising VisualSearchFragment")

        // Initialise FileManagerService (needed before ScannerCamera)
        fileManagerService = FileManagerService(requireContext())

        // Set up click listeners
        binding.btnCapture.setOnClickListener { capturePhoto() }
        binding.btnUploadPhoto.setOnClickListener { pickFromGallery() }

        // Check BOTH camera and location permissions before initialising preview
        checkPermissionsAndInitCamera()

        // Observe the location confirmation state machine
        observeConfirmationState()

        // Observe the visual locate result & error
        observeVisualLocateResult()
    }

    // ═════════════════════════════════════════════════════════════════
    //  State Observation
    // ═════════════════════════════════════════════════════════════════

    /**
     * Observes [VisualSearchViewModel.confirmationState] and drives the
     * sequential confirmation dialogs, loading overlay, and error handling.
     *
     * All UI calls (Toast, AlertDialog) are made from the lifecycleScope
     * which runs on [Dispatchers.Main] by default, ensuring they are always
     * on the main thread.
     */
    private fun observeConfirmationState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                visualSearchViewModel.confirmationState.collect { state ->
                    Log.d(NAVISENSE_DEBUG_TAG, "Confirmation state: $state")

                    when (state) {
                        LocationConfirmationState.IDLE -> {
                            // No-op — waiting for user to capture
                        }

                        LocationConfirmationState.FETCHING_LOCATION -> {
                            dismissConfirmationDialog()
                            showLoading(getString(R.string.location_fetching))
                        }

                        LocationConfirmationState.RESOLVING_ADDRESS -> {
                            // Keep the loading indicator; address resolution
                            // usually completes instantly after location is known.
                        }

                        LocationConfirmationState.CONFIRM_DISTRICT -> {
                            hideLoading()
                            showLocationConfirmationDialog(
                                title = getString(R.string.location_confirm_title),
                                message = getString(
                                    R.string.location_confirm_district,
                                    visualSearchViewModel.locationInfo.value?.district
                                        ?: return@collect
                                )
                            )
                        }

                        LocationConfirmationState.CONFIRM_CITY -> {
                            hideLoading()
                            showLocationConfirmationDialog(
                                title = getString(R.string.location_confirm_title),
                                message = getString(
                                    R.string.location_confirm_city,
                                    visualSearchViewModel.locationInfo.value?.city
                                        ?: return@collect
                                )
                            )
                        }

                        LocationConfirmationState.CONFIRM_COUNTRY -> {
                            hideLoading()
                            showLocationConfirmationDialog(
                                title = getString(R.string.location_confirm_title),
                                message = getString(
                                    R.string.location_confirm_country,
                                    visualSearchViewModel.locationInfo.value?.country
                                        ?: return@collect
                                )
                            )
                        }

                        LocationConfirmationState.SCOPE_CONFIRMED -> {
                            dismissConfirmationDialog()
                            val scope = visualSearchViewModel.confirmedScope.value
                            if (scope != null) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.toast_scope_confirmed, scope),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            // Proceed with the visual search
                            proceedWithSearch()
                        }

                        LocationConfirmationState.LOCATION_UNAVAILABLE -> {
                            hideLoading()
                            dismissConfirmationDialog()
                            Toast.makeText(
                                requireContext(),
                                R.string.location_not_available,
                                Toast.LENGTH_LONG
                            ).show()
                            // Fall back to full-world search
                            proceedWithSearch()
                        }

                        LocationConfirmationState.PERMISSION_DENIED -> {
                            hideLoading()
                            dismissConfirmationDialog()
                            Log.d(NAVISENSE_DEBUG_TAG, "Location permission denied — launching permission request")
                            // Request location permission at runtime
                            locationPermissionLauncher.launch(
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        }

                        LocationConfirmationState.ANALYZING -> {
                            dismissConfirmationDialog()
                            showLoading(getString(R.string.analysing_visual_data))
                        }
                    }
                }
            }
        }
    }

    /**
     * Observes [VisualSearchViewModel.visualLocateResult] and
     * [VisualSearchViewModel.visualLocateError] to handle the API outcome.
     *
     * On success: stores the visual pin in [MainViewModel] and navigates to Map.
     * On error: shows a Toast with the error message and hides loading.
     */
    private fun observeVisualLocateResult() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe successful result
                launch {
                    visualSearchViewModel.visualLocateResult.collect { result ->
                        if (result != null) {
                            Log.d(
                                NAVISENSE_DEBUG_TAG,
                                "Visual locate result received: lat=${result.latitude}, " +
                                        "lon=${result.longitude}, confidence=${result.confidence}"
                            )
                            handleVisualLocateSuccess(result)
                        }
                    }
                }

                // Observe error
                launch {
                    visualSearchViewModel.visualLocateError.collect { errorMsg ->
                        if (errorMsg != null) {
                            Log.e(
                                NAVISENSE_DEBUG_TAG,
                                "Visual locate error received: $errorMsg"
                            )
                            handleVisualLocateError(errorMsg)
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the ViT backend returns a successful visual-locate result.
     *
     * 1. Creates an [AppLocation] with the returned coordinates.
     * 2. Stores it in the shared [MainViewModel] as a visual pin.
     * 3. Hides the loading overlay.
     * 4. Navigates to the MapFragment so the user can see the pin.
     * 5. Cleans up temporary files.
     */
    private fun handleVisualLocateSuccess(result: VisualLocateResult) {
        val visualPin = AppLocation(
            title = getString(R.string.match_found_title),
            description = getString(
                R.string.match_found_description_confidence,
                (result.confidence * 100).toInt()
            ),
            latitude = result.latitude,
            longitude = result.longitude,
            category = AppLocationCategory.MONUMENT.key,
            isVisited = false
        )

        // Store the visual pin in the shared ViewModel for MapFragment to pick up
        viewModel.setVisualPinResult(visualPin)

        hideLoading()
        navigateToMap()

        // Clean up TempScans folder after processing
        val capturedFile = pendingCapturedFile
        if (capturedFile != null) {
            clearTempScansAfterSearch(capturedFile)
        }
        pendingCapturedFile = null

        // Clear the consumed result to avoid re-triggering
        visualSearchViewModel.resetState()
    }

    /**
     * Called when the ViT backend API call fails.
     *
     * Shows a user-friendly Toast / Snackbar and re-enables the UI
     * so the user can retry.
     */
    private fun handleVisualLocateError(errorMessage: String) {
        hideLoading()

        Toast.makeText(
            requireContext(),
            errorMessage,
            Toast.LENGTH_LONG
        ).show()

        // Clear the consumed error to avoid re-triggering
        visualSearchViewModel.resetState()
    }

    // ═════════════════════════════════════════════════════════════════
    //  Location Confirmation Dialog
    // ═════════════════════════════════════════════════════════════════

    /**
     * Shows an [AlertDialog] with the given [title] and [message] and
     * **Yes** / **No** buttons. Delegates the response back to the
     * [VisualSearchViewModel] to advance the state machine.
     *
     * All AlertDialog calls are inherently on the main thread since
     * they require a UI Context.
     */
    private fun showLocationConfirmationDialog(title: String, message: String) {
        dismissConfirmationDialog()

        confirmationDialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false) // Must answer to proceed
            .setPositiveButton(R.string.location_confirm_yes) { _, _ ->
                Log.d(NAVISENSE_DEBUG_TAG, "User confirmed location scope")
                visualSearchViewModel.onScopeConfirmed()
            }
            .setNegativeButton(R.string.location_confirm_no) { _, _ ->
                Log.d(NAVISENSE_DEBUG_TAG, "User denied location scope")
                visualSearchViewModel.onScopeDenied()
            }
            .show()
    }

    private fun dismissConfirmationDialog() {
        confirmationDialog?.dismiss()
        confirmationDialog = null
    }

    // ═════════════════════════════════════════════════════════════════
    //  Permissions Handling
    // ═════════════════════════════════════════════════════════════════

    /**
     * Checks if **both** CAMERA and at least one LOCATION permission
     * (FINE or COARSE) are already granted.
     *
     * - If both are granted → initialises the camera preview immediately.
     * - If either is missing → shows a "Permissions Required" dialog
     *   with an "Open Settings" button so the user can grant permissions.
     *
     * This prevents the "Camera error" or "Unable to find location" flow
     * from happening when the user opens the Visual Search tab without
     * having granted the necessary permissions.
     */
    private fun checkPermissionsAndInitCamera() {
        val context = requireContext()

        val hasCamera = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val hasFineLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasLocation = hasFineLocation || hasCoarseLocation

        Log.d(
            NAVISENSE_DEBUG_TAG,
            "Permissions check — camera=$hasCamera, location(fine=$hasFineLocation, coarse=$hasCoarseLocation)"
        )

        if (hasCamera && hasLocation) {
            // Both permissions granted — initialise camera
            Log.d(NAVISENSE_DEBUG_TAG, "All required permissions granted — initialising camera")
            initializeCamera()
        } else {
            // Show the permissions required dialog
            Log.w(
                NAVISENSE_DEBUG_TAG,
                "Permissions missing — camera=$hasCamera, location=$hasLocation — showing permissions dialog"
            )
            showPermissionsRequiredDialog()
        }
    }

    /**
     * Shows a "Permissions Required" [AlertDialog] explaining that both
     * Camera and Location permissions are needed for visual search.
     *
     * Provides two options:
     * - **"Open Settings"** — opens the system app settings so the user
     *   can grant permissions manually.
     * - **"Cancel"** — dismisses the dialog; the user can tap the Upload
     *   button to use the gallery as an alternative.
     *
     * This dialog is shown whenever [checkPermissionsAndInitCamera]
     * detects that one or more required permissions are missing.
     */
    private fun showPermissionsRequiredDialog() {
        dismissPermissionsRequiredDialog()

        permissionsRequiredDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.permissions_required_title)
            .setMessage(R.string.permissions_camera_location_required)
            .setCancelable(false)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                Log.d(NAVISENSE_DEBUG_TAG, "User tapped 'Open Settings' — launching system settings")
                openAppSettings()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                Log.d(NAVISENSE_DEBUG_TAG, "User cancelled permissions dialog — showing camera placeholder")
                // Show placeholder and disable capture, but keep upload enabled
                binding.tvCameraPlaceholder.visibility = View.VISIBLE
                binding.btnCapture.isEnabled = false
            }
            .show()
    }

    private fun dismissPermissionsRequiredDialog() {
        permissionsRequiredDialog?.dismiss()
        permissionsRequiredDialog = null
    }

    /**
     * Opens the system Settings screen for this app so the user can
     * manually grant Camera and Location permissions.
     *
     * Uses [Settings.ACTION_APPLICATION_DETAILS_SETTINGS] which opens
     * the app-specific permission page.
     */
    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", requireContext().packageName, null)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // ═════════════════════════════════════════════════════════════════
    //  CameraX Initialisation
    // ═════════════════════════════════════════════════════════════════

    /**
     * Creates a [ScannerCamera] instance bound to the [PreviewView] in the layout.
     * The camera starts showing a live preview immediately.
     *
     * Safe to call multiple times — [ScannerCamera] handles its own lifecycle
     * and will unbind/bind as needed.
     *
     * NOTE: This method should only be called after [checkPermissionsAndInitCamera]
     * has confirmed that both CAMERA and LOCATION permissions are granted.
     */
    private fun initializeCamera() {
        val fms = fileManagerService ?: run {
            fileManagerService = FileManagerService(requireContext())
            fileManagerService!!
        }

        val previewView = binding.previewView
        // Hide the placeholder text once camera is initialising
        binding.tvCameraPlaceholder.visibility = View.GONE

        Log.d(NAVISENSE_DEBUG_TAG, "Initialising ScannerCamera with PreviewView")

        scannerCamera = ScannerCamera(
            context = requireContext(),
            lifecycleOwner = viewLifecycleOwner,
            fileManagerService = fms,
            previewView = previewView
        )
    }

    // ═════════════════════════════════════════════════════════════════
    //  Capture Flow
    // ═════════════════════════════════════════════════════════════════

    /**
     * Triggers a single-frame capture via [ScannerCamera.captureSharpImage].
     *
     * On success:
     * 1. Shows a toast confirming capture.
     * 2. Starts the **location confirmation flow** (instead of immediately
     *    doing the search), so we can narrow the backend's search space.
     * 3. After location is confirmed → visual search → navigate to Map.
     * 4. Cleans up the TempScans folder after navigation.
     *
     * On failure:
     * - Shows a user-friendly toast describing the issue.
     * - The user can retry immediately.
     */
    private fun capturePhoto() {
        val scanner = scannerCamera
        if (scanner == null) {
            Log.e(NAVISENSE_DEBUG_TAG, "capturePhoto called but scannerCamera is null")
            Toast.makeText(requireContext(), R.string.toast_camera_error, Toast.LENGTH_SHORT).show()
            return
        }

        // Disable capture button during processing to prevent double-taps
        binding.btnCapture.isEnabled = false

        Log.d(NAVISENSE_DEBUG_TAG, "Initiating photo capture")

        scanner.captureSharpImage(
            onSuccess = { savedFile ->
                // Re-enable capture button
                binding.btnCapture.isEnabled = true

                Log.d(
                    NAVISENSE_DEBUG_TAG,
                    "Image captured and saved: ${savedFile.absolutePath} (size=${savedFile.length()} bytes)"
                )

                // Notify the user
                Toast.makeText(
                    requireContext(),
                    R.string.toast_image_captured,
                    Toast.LENGTH_SHORT
                ).show()

                // Hold a reference so we can clean up after the search
                pendingCapturedFile = savedFile

                // Start the rough-location confirmation flow
                visualSearchViewModel.fetchAndConfirmLocation()
            },
            onError = { exception ->
                // Re-enable capture button so the user can retry
                binding.btnCapture.isEnabled = true

                val messageResId = when (exception) {
                    is ScannerCamera.ImageTooBlurryException -> {
                        Log.w(NAVISENSE_DEBUG_TAG, "Capture failed: image too blurry", exception)
                        R.string.toast_image_blurry
                    }
                    is FileManagerService.InsufficientStorageException -> {
                        Log.e(NAVISENSE_DEBUG_TAG, "Capture failed: insufficient storage", exception)
                        R.string.toast_storage_insufficient
                    }
                    is FileManagerService.FileManagerException -> {
                        Log.e(NAVISENSE_DEBUG_TAG, "Capture failed: file manager error", exception)
                        R.string.toast_file_error
                    }
                    else -> {
                        Log.e(NAVISENSE_DEBUG_TAG, "Capture failed: unexpected error", exception)
                        R.string.toast_camera_error
                    }
                }

                Toast.makeText(requireContext(), messageResId, Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ═════════════════════════════════════════════════════════════════
    //  Gallery Upload
    // ═════════════════════════════════════════════════════════════════

    private fun pickFromGallery() {
        Log.d(NAVISENSE_DEBUG_TAG, "Opening gallery picker")
        galleryLauncher.launch("image/*")
    }

    // ═════════════════════════════════════════════════════════════════
    //  Visual Search — Proceed After Location Confirmed
    // ═════════════════════════════════════════════════════════════════

    /**
     * Called when the location confirmation has completed (scope confirmed,
     * unavailable, or permission denied — all paths lead here).
     *
     * Triggers the real ViT-based API call via
     * [VisualSearchViewModel.performVisualLocate] with the captured file
     * and the confirmed scope. The result is handled by
     * [observeVisualLocateResult].
     *
     * If no file was captured (e.g., gallery flow), the ViewModel skips
     * the backend call and falls back gracefully.
     */
    private fun proceedWithSearch() {
        val file = pendingCapturedFile
        val scope = visualSearchViewModel.confirmedScope.value

        if (file == null) {
            Log.w(
                NAVISENSE_DEBUG_TAG,
                "proceedWithSearch called but pendingCapturedFile is null — nothing to search"
            )
            Toast.makeText(
                requireContext(),
                R.string.toast_camera_error,
                Toast.LENGTH_SHORT
            ).show()
            hideLoading()
            return
        }

        Log.d(NAVISENSE_DEBUG_TAG, "Proceeding with search: file=${file.name}, scope=$scope")

        // Launch the real ViT backend call
        visualSearchViewModel.performVisualLocate(file, scope)
    }

    /**
     * Shows the loading overlay with a custom message and disables buttons.
     */
    private fun showLoading(message: String) {
        binding.tvLoadingText.text = message
        binding.layoutLoading.visibility = View.VISIBLE
        binding.btnUploadPhoto.isEnabled = false
        binding.btnCapture.isEnabled = false
    }

    /**
     * Hides the loading overlay and re-enables buttons.
     */
    private fun hideLoading() {
        binding.layoutLoading.visibility = View.GONE
        binding.btnUploadPhoto.isEnabled = true
        binding.btnCapture.isEnabled = true
    }

    /**
     * Navigates to the Map tab (first destination in the bottom nav).
     */
    private fun navigateToMap() {
        Log.d(NAVISENSE_DEBUG_TAG, "Navigating to MapFragment")
        findNavController().navigate(R.id.mapFragment)
    }

    // ═════════════════════════════════════════════════════════════════
    //  File Cleanup
    // ═════════════════════════════════════════════════════════════════

    /**
     * Clears the entire TempScans folder after the search completes.
     * This enforces the "no temporary image remains on device" policy.
     *
     * @param capturedFile optional reference to the specific file that was
     *                     just captured — used for logging only.
     */
    private fun clearTempScansAfterSearch(capturedFile: File?) {
        val fms = fileManagerService ?: return

        try {
            val deletedCount = fms.clearTempScansFolder()

            if (deletedCount > 0) {
                Log.d(
                    NAVISENSE_DEBUG_TAG,
                    "Cleaned up $deletedCount file(s) from TempScans"
                )
            }
        } catch (e: IOException) {
            Log.e(NAVISENSE_DEBUG_TAG, "Failed to clear TempScans after search: ${e.message}", e)
            fms.logError("Failed to clear TempScans after search: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════

    override fun onDestroyView() {
        Log.d(NAVISENSE_DEBUG_TAG, "onDestroyView — cleaning up resources")

        // Dismiss any showing dialogs to prevent window leaks
        dismissConfirmationDialog()
        dismissPermissionsRequiredDialog()

        // Shutdown CameraX and release resources
        scannerCamera?.shutdown()
        scannerCamera = null
        fileManagerService = null

        super.onDestroyView()
        _binding = null
    }
}
