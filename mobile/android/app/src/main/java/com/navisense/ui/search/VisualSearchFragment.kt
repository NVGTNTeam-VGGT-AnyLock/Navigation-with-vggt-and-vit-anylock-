package com.navisense.ui.search

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.navisense.R
import com.navisense.databinding.FragmentVisualSearchBinding
import com.navisense.model.AppLocation
import com.navisense.model.AppLocationCategory
import com.navisense.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Visual Search screen — UI-only mock of the ML/ViT comparison flow.
 *
 * **No actual ML logic is implemented here.** The user picks a photo
 * (from Gallery or Camera), a 2-second loading spinner is shown (mocking
 * network inference), and then a mock "Match Found" marker is dropped
 * on the Map via [MainViewModel.setMockMatchResult].
 */
class VisualSearchFragment : Fragment() {

    private var _binding: FragmentVisualSearchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()

    // Camera permission launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            takePhoto()
        } else {
            Toast.makeText(requireContext(), R.string.permission_camera_denied, Toast.LENGTH_SHORT).show()
        }
    }

    // Gallery picker
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            startMockSearch()
        }
    }

    // Camera capture
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
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

        binding.btnUploadPhoto.setOnClickListener { pickFromGallery() }
        binding.btnTakePhoto.setOnClickListener { requestCameraAndCapture() }
    }

    // ── Gallery ────────────────────────────────────────────────────

    private fun pickFromGallery() {
        galleryLauncher.launch("image/*")
    }

    // ── Camera ─────────────────────────────────────────────────────

    private fun requestCameraAndCapture() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            takePhoto()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun takePhoto() {
        cameraLauncher.launch(null)
    }

    // ── Mock Search ────────────────────────────────────────────────

    /**
     * Shows a loading spinner for 2 seconds, then navigates to the Map
     * screen and drops a mock "Match Found" marker via the ViewModel.
     */
    private fun startMockSearch() {
        // Show loading spinner
        binding.layoutLoading.visibility = View.VISIBLE
        binding.btnUploadPhoto.isEnabled = false
        binding.btnTakePhoto.isEnabled = false

        // Use lifecycleScope so the coroutine is automatically cancelled
        // when the fragment's lifecycle is destroyed.
        viewLifecycleOwner.lifecycleScope.launch {
            delay(2000L) // Mock 2-second network inference

            // Create a mock match location
            val mockMatch = AppLocation(
                title = getString(R.string.match_found_title),
                description = getString(R.string.match_found_description),
                latitude = 50.4501 + Math.random() * 0.02 - 0.01, // Slightly randomized near Kyiv centre
                longitude = 30.5234 + Math.random() * 0.02 - 0.01,
                category = AppLocationCategory.MONUMENT.key,
                isVisited = false
            )

            // Store the mock result and navigate to Map
            viewModel.setMockMatchResult(mockMatch)

            // Hide loading
            binding.layoutLoading.visibility = View.GONE
            binding.btnUploadPhoto.isEnabled = true
            binding.btnTakePhoto.isEnabled = true

            // Navigate to Map tab (first destination in bottom nav)
            findNavController().navigate(R.id.mapFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
