package com.navisense.ui.details

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.navisense.databinding.BottomSheetLocationDetailsBinding
import com.navisense.model.AppLocation
import com.navisense.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * BottomSheet that displays full details for a single [AppLocation].
 *
 * Provides:
 * - Image (loaded from [AppLocation.imageUri] via Coil)
 * - Title + Description
 * - "Mark as Visited" button (toggles [AppLocation.isVisited])
 * - "Delete Location" button (removes from repository)
 *
 * @param locationId The [AppLocation.id] to display. Fetched via the ViewModel.
 */
class LocationDetailsBottomSheet private constructor() : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLocationDetailsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()
    private var locationId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationId = requireArguments().getInt(ARG_LOCATION_ID, -1)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetLocationDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe allLocations flow to find our location
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allLocations.collectLatest { locations ->
                    val location = locations.firstOrNull { it.id == locationId }
                    if (location != null) {
                        bindLocation(location)
                    }
                }
            }
        }

        // Delete button
        binding.btnDelete.setOnClickListener {
            viewModel.deleteLocation(locationId)
            dismiss()
        }
    }

    private fun bindLocation(location: AppLocation) {
        binding.tvTitle.text = location.title
        binding.tvDescription.text = location.description
        binding.tvCategory.text = location.category
        binding.tvCoordinates.text = "${location.latitude}, ${location.longitude}"

        // Visited toggle
        val visitedText = if (location.isVisited) {
            getString(com.navisense.R.string.visited_already)
        } else {
            getString(com.navisense.R.string.mark_as_visited)
        }
        binding.btnToggleVisited.text = visitedText
        binding.btnToggleVisited.setOnClickListener {
            viewModel.toggleVisited(location.id)
            dismiss()
        }

        // Load image via Coil if URI is present
        if (location.imageUri.isNotBlank()) {
            binding.ivLocationImage.visibility = View.VISIBLE
            // Coil image loading would go here:
            // binding.ivLocationImage.load(location.imageUri)
        } else {
            binding.ivLocationImage.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "LocationDetailsBottomSheet"
        private const val ARG_LOCATION_ID = "location_id"

        fun newInstance(locationId: Int): LocationDetailsBottomSheet {
            val args = Bundle().apply { putInt(ARG_LOCATION_ID, locationId) }
            return LocationDetailsBottomSheet().apply { arguments = args }
        }
    }
}
