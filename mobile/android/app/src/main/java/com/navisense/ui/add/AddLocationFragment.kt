package com.navisense.ui.add

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.navisense.R
import com.navisense.databinding.FragmentAddLocationBinding
import com.navisense.model.AppLocationCategory
import com.navisense.ui.MainViewModel

/**
 * Screen for adding a new [com.navisense.model.AppLocation].
 *
 * User picks coordinates by tapping on a map preview, fills in title,
 * description, category (dropdown), and optionally attaches a photo
 * (Gallery or Camera intent). Saves via [MainViewModel].
 */
class AddLocationFragment : Fragment() {

    private var _binding: FragmentAddLocationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()
    private lateinit var map: GoogleMap
    private var selectedLatLng: LatLng? = null
    private var photoUri: Uri? = null

    // Gallery picker
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            photoUri = it
            binding.ivPhotoPreview.visibility = View.VISIBLE
            binding.ivPhotoPreview.setImageURI(it)
        }
    }

    // Camera capture
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            // For demo: store a placeholder URI (real app would save to file)
            photoUri = Uri.parse("content://media/external/images/media/1")
            binding.ivPhotoPreview.visibility = View.VISIBLE
            binding.ivPhotoPreview.setImageBitmap(bitmap)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddLocationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initMapPicker()
        initCategoryDropdown()
        initPhotoButton()
        initSaveButton()
    }

    // ── Map Picker ─────────────────────────────────────────────────

    private fun initMapPicker() {
        val mapFragment = childFragmentManager
            .findFragmentById(R.id.map_picker) as SupportMapFragment

        mapFragment.getMapAsync { googleMap ->
            map = googleMap
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(50.4501, 30.5234), 13f))
            map.uiSettings.isZoomControlsEnabled = true
            map.uiSettings.isMapToolbarEnabled = false

            // Default selection: Kyiv centre
            selectedLatLng = LatLng(50.4501, 30.5234)
            binding.tvSelectedCoords.text = "50.4501, 30.5234"

            // Tap to pick coordinates
            map.setOnMapClickListener { latLng ->
                map.clear()
                selectedLatLng = latLng
                binding.tvSelectedCoords.text =
                    getString(com.navisense.R.string.coords_format, latLng.latitude, latLng.longitude)
                map.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title(getString(com.navisense.R.string.selected_position))
                )
            }
        }
    }

    // ── Category Dropdown ──────────────────────────────────────────

    private fun initCategoryDropdown() {
        val categories = AppLocationCategory.names
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        binding.actvCategory.setAdapter(adapter)
        binding.actvCategory.setText(AppLocationCategory.MONUMENT.key, false)
    }

    // ── Photo Attachment ───────────────────────────────────────────

    private fun initPhotoButton() {
        binding.btnAttachPhoto.setOnClickListener {
            // Show a simple dialog-style choice: Gallery or Camera
          //  val options = arrayOf(
          //      getString(com.navisense.R.string.gallery),
           //     getString(com.navisense.R.string.camera)
          //  )
            // Simple approach: cycle between gallery and camera
            showPhotoPicker()
        }
    }

    private fun showPhotoPicker() {
        // For simplicity: directly open gallery
        // In production, show a bottom sheet picker
        galleryLauncher.launch("image/*")
    }

    // ── Save ───────────────────────────────────────────────────────

    private fun initSaveButton() {
        binding.btnSave.setOnClickListener {
            val title = binding.etTitle.text?.toString()?.trim()
            val description = binding.etDescription.text?.toString()?.trim()
            val coords = selectedLatLng
            val category = binding.actvCategory.text.toString()

            // Validation
            if (title.isNullOrEmpty()) {
                binding.etTitle.error = getString(com.navisense.R.string.required_field)
                return@setOnClickListener
            }
            if (coords == null) {
                Toast.makeText(requireContext(), com.navisense.R.string.select_coords, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.addLocation(
                title = title,
                description = description ?: "",
                latitude = coords.latitude,
                longitude = coords.longitude,
                category = category,
                imageUri = photoUri?.toString() ?: ""
            )

            Toast.makeText(requireContext(), com.navisense.R.string.location_saved, Toast.LENGTH_SHORT).show()

            // Clear form
            binding.etTitle.text?.clear()
            binding.etDescription.text?.clear()
            selectedLatLng = null
            photoUri = null
            binding.ivPhotoPreview.visibility = View.GONE
            map.clear()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
