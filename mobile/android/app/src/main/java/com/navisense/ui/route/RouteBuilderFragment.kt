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
import androidx.fragment.app.activityViewModels
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
 * - Top half: Google Map showing waypoints and a Polyline route (road-aware mock).
 * - Bottom half: List of saved locations for the user to select waypoints.
 *
 * Implements the "Pac-Man" algorithm:
 * 1. First selected waypoint MUST remain the Start.
 * 2. Last selected waypoint MUST remain the Finish.
 * 3. Middle waypoints are automatically re-ordered to find the SHORTEST total
 *    Haversine distance (nearest-neighbor TSP heuristic).
 */
class RouteBuilderFragment : Fragment() {

    private var _binding: FragmentRouteBuilderBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
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

        // Draw road-aware Polyline using mock interpolated points
        val polylinePoints = viewModel.routePolylinePoints.value
        if (polylinePoints.isNotEmpty()) {
            val routeLatLngs = polylinePoints.map { LatLng(it.first, it.second) }
            routePolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(routeLatLngs)
                    .width(6f)
                    .color(0xFF1565C0.toInt())
                    .geodesic(false)
            )
        } else {
            // Fallback: straight line if no polyline data
            routePolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(latLngs)
                    .width(5f)
                    .color(0xFF1565C0.toInt())
                    .geodesic(true)
            )
        }

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

        // Optimize Route button: reorders middle waypoints using TSP heuristic
        binding.btnOptimizeRoute.setOnClickListener {
            val waypoints = viewModel.routeWaypoints.value
            if (waypoints.size < 3) {
                Toast.makeText(requireContext(), R.string.route_optimize_min, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.optimizeRoute()
            // Update local selectedIds to match new order
            selectedIds.clear()
            selectedIds.addAll(viewModel.routeWaypoints.value.map { it.id })
            (binding.rvWaypoints.adapter as? WaypointAdapter)?.notifyDataSetChanged()
            Toast.makeText(requireContext(), R.string.route_optimized, Toast.LENGTH_SHORT).show()
        }

        binding.btnStartNavigation.setOnClickListener {
            val waypoints = viewModel.routeWaypoints.value
            if (waypoints.isEmpty()) {
                Toast.makeText(requireContext(), R.string.route_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Build a multi-destination Google Maps URL with all waypoints
            val destination = waypoints.last()
            val gmmIntentUri = Uri.parse("google.navigation:q=${destination.latitude},${destination.longitude}")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")

            if (mapIntent.resolveActivity(requireActivity().packageManager) != null) {
                startActivity(mapIntent)
            } else {
                // Fallback to web URL with waypoints
                val waypointsParam = waypoints.drop(1).dropLast(1).joinToString("|") {
                    "${it.latitude},${it.longitude}"
                }
                val webUri = Uri.parse(
                    "https://www.google.com/maps/dir/?api=1" +
                            "&origin=${waypoints.first().latitude},${waypoints.first().longitude}" +
                            "&destination=${destination.latitude},${destination.longitude}" +
                            (if (waypointsParam.isNotEmpty()) "&waypoints=$waypointsParam" else "")
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
                TextViewCompat.setTextAppearance(
                    textView,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
                )
            }
        }
    }
}
