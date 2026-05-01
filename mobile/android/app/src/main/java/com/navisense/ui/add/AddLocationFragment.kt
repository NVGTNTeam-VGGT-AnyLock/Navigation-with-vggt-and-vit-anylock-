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
import androidx.fragment.app.activityViewModels
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.navisense.R
import com.navisense.databinding.FragmentAddLocationBinding
import com.navisense.model.AppLocation
import com.navisense.model.AppLocationCategory
import com.navisense.ui.MainViewModel

/**
 * Screen for adding or editing a [AppLocation].
 *
 * Supports two modes:
 * - **Add mode** (default): User picks coordinates by tapping on a map preview,
 *   fills in fields, and saves a new location.
 * - **Edit mode**: Activated via [newInstance] with existing data. Pre-fills
 *   all fields. Save updates the existing location.
 *
 * Includes "No Category" option in the category dropdown.
 */
class AddLocationFragment : Fragment() {

    private var _binding: FragmentAddLocationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var map: GoogleMap
    private var selectedLatLng: LatLng? = null
    private var photoUri: Uri? = null

    /** Maps display name (localized) → category key for the dropdown. */
    private var categoryPairs: List<Pair<String, String>> = emptyList()

    // Edit mode fields
    private var editLocationId: Int? = null
    private var editOriginalCategory: String? = null
    private var editIsVisited: Boolean = false
    private var editIsFavorite: Boolean = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read edit-mode arguments
        arguments?.let { args ->
            editLocationId = args.getInt(ARG_LOCATION_ID, -1).let { if (it == -1) null else it }
            editIsVisited = args.getBoolean(ARG_IS_VISITED, false)
            editIsFavorite = args.getBoolean(ARG_IS_FAVORITE, false)
            val title = args.getString(ARG_TITLE)
            val description = args.getString(ARG_DESCRIPTION)
            val lat = args.getDouble(ARG_LATITUDE, Double.NaN)
            val lng = args.getDouble(ARG_LONGITUDE, Double.NaN)
            val category = args.getString(ARG_CATEGORY)
            val imageUri = args.getString(ARG_IMAGE_URI)

            if (editLocationId != null && !lat.isNaN() && !lng.isNaN()) {
                selectedLatLng = LatLng(lat, lng)
                editOriginalCategory = category
                // Store in a bundle for later use after view creation
                savedInstanceState?.putString("edit_title", title)
                savedInstanceState?.putString("edit_description", description)
                savedInstanceState?.putString("edit_category", category)
                savedInstanceState?.putString("edit_image_uri", imageUri)
            }
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

        // Pre-fill fields in edit mode
        if (editLocationId != null) {
            binding.btnSave.text = getString(R.string.update)
            binding.tvAddTitle.text = getString(R.string.edit_location_title)

            arguments?.let { args ->
                args.getString(ARG_TITLE)?.let { binding.etTitle.setText(it) }
                args.getString(ARG_DESCRIPTION)?.let { binding.etDescription.setText(it) }
                args.getString(ARG_CATEGORY)?.let { catKey ->
                    // Set the localized display name in the dropdown
                    val displayName = categoryPairs.firstOrNull { it.second == catKey }?.first
                        ?: catKey
                    binding.actvCategory.setText(displayName, false)
                }
            }
        }
    }

    // ── Map Picker ─────────────────────────────────────────────────

    private fun initMapPicker() {
        val mapFragment = childFragmentManager
            .findFragmentById(R.id.map_picker) as SupportMapFragment

        mapFragment.getMapAsync { googleMap ->
            map = googleMap

            val defaultLatLng = selectedLatLng ?: LatLng(50.4501, 30.5234)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLatLng, 13f))
            map.uiSettings.isZoomControlsEnabled = true
            map.uiSettings.isMapToolbarEnabled = false

            // If in edit mode, show existing marker
            if (selectedLatLng != null) {
                map.addMarker(
                    MarkerOptions()
                        .position(selectedLatLng!!)
                        .title(getString(R.string.selected_position))
                )
                binding.tvSelectedCoords.text = getString(
                    R.string.coords_format,
                    selectedLatLng!!.latitude,
                    selectedLatLng!!.longitude
                )
            }

            // In edit mode, do NOT allow changing coordinates
            if (editLocationId == null) {
                // Tap to pick coordinates
                map.setOnMapClickListener { latLng ->
                    map.clear()
                    selectedLatLng = latLng
                    binding.tvSelectedCoords.text =
                        getString(R.string.coords_format, latLng.latitude, latLng.longitude)
                    map.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title(getString(R.string.selected_position))
                    )
                }
            }
        }
    }

    // ── Category Dropdown ──────────────────────────────────────────

    private fun initCategoryDropdown() {
        // Build a list of (localized display name → category key) pairs
        categoryPairs = AppLocationCategory.entries.map { entry ->
            val resId = requireContext().resources.getIdentifier(
                "cat_${entry.key.lowercase().replace(" ", "_")}",
                "string",
                requireContext().packageName
            )
            val displayName = if (resId != 0) getString(resId) else entry.key
            displayName to entry.key
        }
        // Adapter shows localized display names
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            categoryPairs.map { it.first }
        )
        binding.actvCategory.setAdapter(adapter)

        // Set initial selection from edit mode (or default to Monument)
        val initialKey = editOriginalCategory ?: AppLocationCategory.MONUMENT.key
        val initialDisplay = categoryPairs.firstOrNull { it.second == initialKey }?.first
            ?: initialKey
        binding.actvCategory.setText(initialDisplay, false)
    }

    // ── Photo Attachment ───────────────────────────────────────────

    private fun initPhotoButton() {
        binding.btnAttachPhoto.setOnClickListener {
            showPhotoPicker()
        }
    }

    private fun showPhotoPicker() {
        galleryLauncher.launch("image/*")
    }

    // ── Save / Update ──────────────────────────────────────────────

    private fun initSaveButton() {
        binding.btnSave.setOnClickListener {
            val title = binding.etTitle.text?.toString()?.trim()
            val description = binding.etDescription.text?.toString()?.trim()
            val coords = selectedLatLng
            // Map the displayed localized name back to the category key
            val selectedDisplayName = binding.actvCategory.text.toString()
            val category = categoryPairs.firstOrNull { it.first == selectedDisplayName }?.second
                ?: selectedDisplayName

            // Validation
            if (title.isNullOrEmpty()) {
                binding.etTitle.error = getString(R.string.required_field)
                return@setOnClickListener
            }
            if (coords == null) {
                Toast.makeText(requireContext(), R.string.select_coords, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val locationId = editLocationId
            if (locationId != null) {
                // Update existing location — preserve isVisited/isFavorite from original
                viewModel.updateLocation(
                    AppLocation(
                        id = locationId,
                        title = title,
                        description = description ?: "",
                        latitude = coords.latitude,
                        longitude = coords.longitude,
                        category = category,
                        imageUri = photoUri?.toString() ?: "",
                        isVisited = editIsVisited,
                        isFavorite = editIsFavorite
                    )
                )
                Toast.makeText(requireContext(), R.string.location_updated, Toast.LENGTH_SHORT).show()
            } else {
                // Insert new location
                viewModel.addLocation(
                    title = title,
                    description = description ?: "",
                    latitude = coords.latitude,
                    longitude = coords.longitude,
                    category = category,
                    imageUri = photoUri?.toString() ?: ""
                )
                Toast.makeText(requireContext(), R.string.location_saved, Toast.LENGTH_SHORT).show()
            }

            // Navigate back
            parentFragmentManager.popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_LOCATION_ID = "location_id"
        private const val ARG_TITLE = "title"
        private const val ARG_DESCRIPTION = "description"
        private const val ARG_LATITUDE = "latitude"
        private const val ARG_LONGITUDE = "longitude"
        private const val ARG_CATEGORY = "category"
        private const val ARG_IMAGE_URI = "image_uri"
        private const val ARG_IS_VISITED = "is_visited"
        private const val ARG_IS_FAVORITE = "is_favorite"

        /**
         * Creates a new instance for ADD mode.
         */
        fun newInstance(): AddLocationFragment {
            return AddLocationFragment()
        }

        /**
         * Creates a new instance for EDIT mode with pre-filled data.
         */
        fun newInstance(
            locationId: Int,
            title: String,
            description: String,
            latitude: Double,
            longitude: Double,
            category: String,
            imageUri: String,
            isVisited: Boolean = false,
            isFavorite: Boolean = false
        ): AddLocationFragment {
            val args = Bundle().apply {
                putInt(ARG_LOCATION_ID, locationId)
                putString(ARG_TITLE, title)
                putString(ARG_DESCRIPTION, description)
                putDouble(ARG_LATITUDE, latitude)
                putDouble(ARG_LONGITUDE, longitude)
                putString(ARG_CATEGORY, category)
                putString(ARG_IMAGE_URI, imageUri)
                putBoolean(ARG_IS_VISITED, isVisited)
                putBoolean(ARG_IS_FAVORITE, isFavorite)
            }
            return AddLocationFragment().apply { arguments = args }
        }
    }
}
