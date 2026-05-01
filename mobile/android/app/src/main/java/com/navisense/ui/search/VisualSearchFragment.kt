package com.navisense.ui.search

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.navisense.R
import com.navisense.core.FileManagerService
import com.navisense.core.ScannerCamera
import com.navisense.databinding.FragmentVisualSearchBinding
import com.navisense.model.AppLocation
import com.navisense.model.AppLocationCategory
import com.navisense.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Visual Search screen — CameraX live preview with single-frame capture,
 * blur validation, temporary file storage, and mock visual positioning.
 *
 * **Camera Flow (Sprint 1 wiring):**
 * 1. On fragment creation, requests CAMERA permission if not granted.
 * 2. Once granted, instantiates [ScannerCamera] with a live [PreviewView].
 * 3. User taps the capture FAB → [ScannerCamera.captureSharpImage()] is called.
 * 4. On success: the sharp image is saved to `TempScans/` via [FileManagerService],
 *    a toast is shown, and the mock search flow begins.
 * 5. On failure (blurry, insufficient storage, camera error): a descriptive toast
 *    is shown and the user can retry.
 * 6. After the mock search completes (and the UI navigates to Map), the
 *    `TempScans/` folder is wiped via [FileManagerService.clearTempScansFolder()].
 *
 * **Gallery Flow (unchanged from Sprint 2):**
 * - Tapping "Upload Photo" opens the system gallery picker.
 * - The selected image triggers the same mock search flow (no TempScans save).
 */
class VisualSearchFragment : Fragment() {

    private var _binding: FragmentVisualSearchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    // ── Core Services (initialised after permission grant) ──────────
    private var fileManagerService: FileManagerService? = null
    private var scannerCamera: ScannerCamera? = null

    // ── Camera Permission Launcher ──────────────────────────────────
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initializeCamera()
        } else {
            binding.tvCameraPlaceholder.visibility = View.VISIBLE
            binding.btnCapture.isEnabled = false
            Toast.makeText(
                requireContext(),
                R.string.permission_camera_denied,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── Gallery Picker ──────────────────────────────────────────────
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            startMockSearch()
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

        // Initialise FileManagerService (needed before ScannerCamera)
        fileManagerService = FileManagerService(requireContext())

        // Set up click listeners
        binding.btnCapture.setOnClickListener { capturePhoto() }
        binding.btnUploadPhoto.setOnClickListener { pickFromGallery() }

        // Check camera permission and initialise preview
        checkCameraPermissionAndInit()
    }

    // ═════════════════════════════════════════════════════════════════
    //  Permission Handling
    // ═════════════════════════════════════════════════════════════════

    /**
     * Checks if CAMERA permission is already granted. If so, initialises
     * the camera preview immediately. Otherwise, requests the permission
     * via the runtime launcher.
     */
    private fun checkCameraPermissionAndInit() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            initializeCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
     */
    private fun initializeCamera() {
        val fms = fileManagerService ?: run {
            fileManagerService = FileManagerService(requireContext())
            fileManagerService!!
        }

        val previewView = binding.previewView
        // Hide the placeholder text once camera is initialising
        binding.tvCameraPlaceholder.visibility = View.GONE

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
     * 2. Starts the mock search flow.
     * 3. After the mock search completes, cleans up the TempScans folder.
     *
     * On failure:
     * - Shows a user-friendly toast describing the issue.
     * - The user can retry immediately.
     */
    private fun capturePhoto() {
        val scanner = scannerCamera
        if (scanner == null) {
            Toast.makeText(requireContext(), R.string.toast_camera_error, Toast.LENGTH_SHORT).show()
            return
        }

        // Disable capture button during processing to prevent double-taps
        binding.btnCapture.isEnabled = false

        scanner.captureSharpImage(
            onSuccess = { savedFile ->
                // Re-enable capture button
                binding.btnCapture.isEnabled = true

                // Notify the user
                Toast.makeText(
                    requireContext(),
                    R.string.toast_image_captured,
                    Toast.LENGTH_SHORT
                ).show()

                // Proceed with the visual search pipeline
                // (mock for now — real backend integration is Sprint 3)
                startMockSearchWithCleanup(savedFile)
            },
            onError = { exception ->
                // Re-enable capture button so the user can retry
                binding.btnCapture.isEnabled = true

                val messageResId = when (exception) {
                    is ScannerCamera.ImageTooBlurryException -> R.string.toast_image_blurry
                    is FileManagerService.InsufficientStorageException -> R.string.toast_storage_insufficient
                    is FileManagerService.FileManagerException -> R.string.toast_file_error
                    else -> R.string.toast_camera_error
                }

                Toast.makeText(requireContext(), messageResId, Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ═════════════════════════════════════════════════════════════════
    //  Gallery Upload
    // ═════════════════════════════════════════════════════════════════

    private fun pickFromGallery() {
        galleryLauncher.launch("image/*")
    }

    // ═════════════════════════════════════════════════════════════════
    //  Mock Search + File Cleanup
    // ═════════════════════════════════════════════════════════════════

    /**
     * Starts the mock search — 2-second loading spinner, then navigates
     * to the Map tab with a mock match marker. Also cleans up the
     * TempScans folder after navigation.
     *
     * This variant is called after a CameraX capture, so we have a [File]
     * reference that was saved to TempScans. The file will be cleaned up
     * by [clearTempScansAfterSearch].
     */
    private fun startMockSearchWithCleanup(capturedFile: File?) {
        showLoading()

        viewLifecycleOwner.lifecycleScope.launch {
            delay(2000L) // Mock 2-second network inference

            dropMockMatchMarker()
            hideLoading()
            navigateToMap()

            // Clean up TempScans folder after processing
            clearTempScansAfterSearch(capturedFile)
        }
    }

    /**
     * Starts the mock search without a TempScans file (gallery upload path).
     */
    private fun startMockSearch() {
        showLoading()

        viewLifecycleOwner.lifecycleScope.launch {
            delay(2000L) // Mock 2-second network inference

            dropMockMatchMarker()
            hideLoading()
            navigateToMap()
        }
    }

    /**
     * Shows the loading overlay and disables buttons.
     */
    private fun showLoading() {
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
     * Creates a random mock match location near Kyiv centre and stores
     * it in the ViewModel so the Map tab can show it.
     */
    private fun dropMockMatchMarker() {
        val mockMatch = AppLocation(
            title = getString(R.string.match_found_title),
            description = getString(R.string.match_found_description),
            latitude = 50.4501 + Math.random() * 0.02 - 0.01,
            longitude = 30.5234 + Math.random() * 0.02 - 0.01,
            category = AppLocationCategory.MONUMENT.key,
            isVisited = false
        )
        viewModel.setMockMatchResult(mockMatch)
    }

    /**
     * Navigates to the Map tab (first destination in the bottom nav).
     */
    private fun navigateToMap() {
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
                android.util.Log.d(
                    "VisualSearchFragment",
                    "Cleaned up $deletedCount file(s) from TempScans"
                )
            }
        } catch (e: IOException) {
            fms.logError("Failed to clear TempScans after search: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════

    override fun onDestroyView() {
        // Shutdown CameraX and release resources
        scannerCamera?.shutdown()
        scannerCamera = null
        fileManagerService = null

        super.onDestroyView()
        _binding = null
    }
}
