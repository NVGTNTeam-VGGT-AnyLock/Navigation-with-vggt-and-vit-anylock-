package com.navisense.ui.details

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.navisense.R
import com.navisense.databinding.BottomSheetLocationDetailsBinding
import com.navisense.model.AppLocation
import com.navisense.ui.MainViewModel
import com.navisense.ui.add.AddLocationFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * BottomSheet that displays full details for a single [AppLocation].
 *
 * Provides:
 * - Large Photo (loaded from [AppLocation.imageUri] via Coil)
 * - Title, Coordinates, Category, Description
 * - Favorite toggle (heart icon)
 * - Visited toggle
 * - Edit button (opens AddLocationFragment in edit mode)
 * - Delete button (removes from repository, closes sheet, refreshes map)
 */
class LocationDetailsBottomSheet private constructor() : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLocationDetailsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()
    private var locationId: Int = -1
    private var currentLocation: AppLocation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationId = requireArguments().getInt(ARG_LOCATION_ID, -1)
        setStyle(STYLE_NORMAL, R.style.Theme_NaviSense_BottomSheet)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetLocationDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe allLocations flow to find our location and update UI
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allLocations.collectLatest { locations ->
                    val location = locations.firstOrNull { it.id == locationId }
                    if (location != null) {
                        currentLocation = location
                        bindLocation(location)
                    } else if (currentLocation == null && location == null) {
                        // Location was deleted before we ever loaded it, dismiss
                        dismiss()
                    }
                    // If currentLocation != null and location == null, it means
                    // the location was deleted — the map will update via the
                    // filteredLocations flow, and we dismiss here.
                    if (currentLocation != null && location == null) {
                        dismiss()
                    }
                }
            }
        }

        // ── Set up click listeners ONCE (not in bindLocation) ──────
        // These reference currentLocation which is kept up-to-date by the flow.

        // Visited toggle
        binding.btnToggleVisited.setOnClickListener {
            val loc = currentLocation ?: return@setOnClickListener
            viewModel.toggleVisited(loc.id)
        }

        // Favorite heart toggle
        binding.ivFavorite.setOnClickListener {
            val loc = currentLocation ?: return@setOnClickListener
            viewModel.toggleFavorite(loc.id)
        }

        // Delete button
        binding.btnDelete.setOnClickListener {
            viewModel.deleteLocation(locationId)
            dismiss()
        }

        // Edit button → navigate to AddLocationFragment in edit mode
        binding.btnEdit.setOnClickListener {
            val location = currentLocation ?: return@setOnClickListener
            val fragment = AddLocationFragment.newInstance(
                locationId = location.id,
                title = location.title,
                description = location.description,
                latitude = location.latitude,
                longitude = location.longitude,
                category = location.category,
                imageUri = location.imageUri,
                isVisited = location.isVisited,
                isFavorite = location.isFavorite
            )
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, fragment)
                .addToBackStack("edit_location")
                .commit()
            dismiss()
        }
    }

    private fun bindLocation(location: AppLocation) {
        binding.tvTitle.text = location.title
        binding.tvDescription.text = location.description

        // Resolve localized category string via resource identifier
        val catKey = location.category.lowercase().replace(" ", "_")
        val catResId = requireContext().resources.getIdentifier(
            "cat_$catKey",
            "string",
            requireContext().packageName
        )
        binding.tvCategory.text = if (catResId != 0) {
            getString(catResId)
        } else {
            location.category
        }

        binding.tvCoordinates.text = "${location.latitude}, ${location.longitude}"

        // Image loading
        if (location.imageUri.isNotBlank()) {
            binding.ivLocationImage.visibility = View.VISIBLE
            binding.cardImagePlaceholder.visibility = View.GONE
            binding.ivLocationImage.load(location.imageUri) {
                crossfade(true)
                placeholder(R.color.naviSense_primary)
                error(R.color.naviSense_primary_variant)
            }
        } else {
            binding.ivLocationImage.visibility = View.GONE
            binding.cardImagePlaceholder.visibility = View.VISIBLE
        }

        // Visited toggle — update text only (listener set once in onViewCreated)
        binding.btnToggleVisited.text = if (location.isVisited) {
            getString(R.string.visited_already)
        } else {
            getString(R.string.mark_as_visited)
        }

        // Favorite heart icon — update drawable only (listener set once in onViewCreated)
        if (location.isFavorite) {
            binding.ivFavorite.setImageDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_heart_filled)
            )
        } else {
            binding.ivFavorite.setImageDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_heart_outline)
            )
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
