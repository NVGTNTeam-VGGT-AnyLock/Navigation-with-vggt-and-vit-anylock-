package com.navisense.ui.route

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.navisense.R
import com.navisense.databinding.FragmentRouteBuilderBinding
import com.navisense.model.AppLocation
import com.navisense.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Route Builder screen with a split UI:
 * - Top half: Google Map showing waypoints and a Polyline route.
 * - Bottom half: List of saved locations for the user to select waypoints.
 *
 * The user selects waypoints from the list, specifies start/end points,
 * and can tap "Start Navigation" to open the external Google Maps app
 * with the final destination as a navigation intent.
 */
class RouteBuilderFragment : Fragment() {

    private var _binding: FragmentRouteBuilderBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()
    private lateinit var map: GoogleMap

    // Marker and Polyline state on the map
    private val waypointMarkers = mutableListOf<Marker>()
    private var routePolyline: Polyline? = null

    // List of selected waypoint IDs (shared with adapter)
    private val selectedIds = mutableSetOf<Int>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteBuilderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initMap()
        initWaypointsList()
        initButtons()
        observeWaypoints()
    }

    // ── Map Initialisation ─────────────────────────────────────────

    private fun initMap() {
        val mapFragment = childFragmentManager
            .findFragmentById(R.id.route_map) as SupportMapFragment

        mapFragment.getMapAsync { googleMap ->
            map = googleMap
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(50.4501, 30.5234), 12f))
            map.uiSettings.isZoomControlsEnabled = true
            map.uiSettings.isMapToolbarEnabled = false
        }
    }

    // ── Waypoints List ─────────────────────────────────────────────

    private fun initWaypointsList() {
        // Use lateinit to break the forward-reference cycle:
        // the onToggle lambda references the adapter variable which is
        // passed into the adapter's constructor.
        lateinit var adapter: WaypointAdapter
        adapter = WaypointAdapter(
            locations = emptyList(),
            selectedIds = selectedIds,
            onToggle = { location ->
                viewModel.toggleRouteWaypoint(location)
                // Update local selected set
                if (!selectedIds.remove(location.id)) {
                    selectedIds.add(location.id)
                }
                adapter.notifyDataSetChanged()
            }
        )

        binding.rvWaypoints.adapter = adapter

        // Observe all locations → update the list using Flow
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allLocations.collectLatest { locations ->
                    adapter.locations = locations
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    // ── Observe Selected Waypoints → Update Map ────────────────────

    private fun observeWaypoints() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.routeWaypoints.collectLatest { waypoints ->
                    updateMapWaypoints(waypoints)
                }
            }
        }
    }

    private fun updateMapWaypoints(waypoints: List<AppLocation>) {
        // Clear old markers and polyline
        waypointMarkers.forEach { it.remove() }
        waypointMarkers.clear()
        routePolyline?.remove()

        if (waypoints.isEmpty()) return

        // Add markers for each waypoint
        val latLngs = mutableListOf<LatLng>()
        waypoints.forEachIndexed { index, location ->
            val latLng = LatLng(location.latitude, location.longitude)
            latLngs.add(latLng)

            val snippet = when (index) {
                0 -> getString(R.string.route_start)
                waypoints.lastIndex -> getString(R.string.route_end)
                else -> getString(R.string.route_waypoint, index + 1)
            }

            val marker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(location.title)
                    .snippet(snippet)
                    .icon(
                        when (index) {
                            0 -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                            waypoints.lastIndex -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                            else -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                        }
                    )
            )
            if (marker != null) waypointMarkers.add(marker)
        }

        // Draw Polyline connecting waypoints in order
        routePolyline = map.addPolyline(
            PolylineOptions()
                .addAll(latLngs)
                .width(5f)
                .color(0xFF1565C0.toInt())
                .geodesic(true)
        )

        // Zoom to fit all waypoints
        if (latLngs.size > 1) {
            val boundsBuilder = LatLngBounds.builder()
            latLngs.forEach { boundsBuilder.include(it) }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 100))
        } else if (latLngs.size == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs[0], 15f))
        }
    }

    // ── Buttons ────────────────────────────────────────────────────

    private fun initButtons() {
        binding.btnClearRoute.setOnClickListener {
            viewModel.clearRouteWaypoints()
            selectedIds.clear()
            (binding.rvWaypoints.adapter as? WaypointAdapter)?.notifyDataSetChanged()
        }

        binding.btnStartNavigation.setOnClickListener {
            val waypoints = viewModel.routeWaypoints.value
            if (waypoints.isEmpty()) {
                Toast.makeText(requireContext(), R.string.route_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Launch Google Maps navigation to the final destination
            val destination = waypoints.last()
            val gmmIntentUri = Uri.parse("google.navigation:q=${destination.latitude},${destination.longitude}")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")

            if (mapIntent.resolveActivity(requireActivity().packageManager) != null) {
                startActivity(mapIntent)
            } else {
                // Fallback to web URL
                val webUri = Uri.parse(
                    "https://www.google.com/maps/dir/?api=1&destination=${destination.latitude},${destination.longitude}"
                )
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── RecyclerView Adapter ───────────────────────────────────────

    private class WaypointAdapter(
        var locations: List<AppLocation>,
        private val selectedIds: Set<Int>,
        private val onToggle: (AppLocation) -> Unit
    ) : RecyclerView.Adapter<WaypointAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_activated_1, parent, false)
            // Explicitly cast the inflated View to TextView
            return ViewHolder(view as TextView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val location = locations[position]
            holder.textView.text = location.title
            holder.textView.setOnClickListener { onToggle(location) }

            // Highlight if selected as waypoint
            holder.textView.isActivated = location.id in selectedIds
        }

        override fun getItemCount() = locations.size

        class ViewHolder(textView: TextView) : RecyclerView.ViewHolder(textView) {
            val textView: TextView = textView

            init {
                textView.setPadding(16, 12, 16, 12)
                // Use TextViewCompat.setTextAppearance instead of the property,
                // which may not be available on all API levels
                TextViewCompat.setTextAppearance(
                    textView,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
                )
            }
        }
    }
}
