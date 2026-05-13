package com.navisense.ui.analytics

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.navisense.R
import com.navisense.databinding.FragmentAnalyticsBinding
import com.navisense.model.AppLocationCategory
import com.navisense.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Premium Analytics Dashboard — McKinsey/BCG style.
 *
 * Displays:
 * - A **KPI Summary Card** with Total Distance, Time Saved, and GPS Stability Score
 *   (derived from the Room [DeliveryHistory] database via [MainViewModel.deliverySummary]).
 * - A **Doughnut Chart** showing category distribution with center text and right-side legend.
 * - A **Horizontal Stacked Bar** showing the ratio of Visited / Not Visited / Favorites.
 * - A **Lollipop Chart** showing district-level statistics sorted by value descending.
 *
 * All data is read-only, derived reactively from [MainViewModel.analyticsData]
 * and [MainViewModel.deliverySummary].
 */
class AnalyticsFragment : Fragment() {

    companion object {
        private const val TAG = "AnalyticsFragment"
    }

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Log.d(TAG, "onViewCreated — binding analytics data flows")

        // ── Observe analytics data → update all charts ──────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Log.d(TAG, "Collecting analyticsData flow")
                viewModel.analyticsData.collectLatest { data ->
                    Log.d(TAG, "analyticsData emitted: " +
                                "categories=${data.categoryCounts.size}, " +
                                "visited=${data.visitedCount}, " +
                                "total=${data.totalCount}")

                    // Doughnut Chart: category distribution (localized names)
                    val localizedCategoryCounts = data.categoryCounts.mapKeys { (key, _) ->
                        val category = AppLocationCategory.fromKey(key)
                        val resId = when (category) {
                            AppLocationCategory.MONUMENT -> R.string.cat_monument
                            AppLocationCategory.GROCERY -> R.string.cat_grocery
                            AppLocationCategory.GAS_STATION -> R.string.cat_gas_station
                            AppLocationCategory.RESTAURANT -> R.string.cat_restaurant
                            AppLocationCategory.PHARMACY -> R.string.cat_pharmacy
                            AppLocationCategory.NO_CATEGORY -> R.string.cat_no_category
                        }
                        getString(resId)
                    }
                    binding.doughnutChart.setData(localizedCategoryCounts)

                    // Efficiency Stacked Bar: visited / not visited ratio (Favorites removed)
                    binding.efficiencyBar.setLabels(
                        visited = getString(R.string.efficiency_visited),
                        notVisited = getString(R.string.efficiency_not_visited)
                    )
                    binding.efficiencyBar.setData(
                        visited = data.visitedCount,
                        notVisited = data.notVisitedCount
                    )

                    // Lollipop Chart: district analysis
                    binding.districtChart.setData(data.districtCounts)
                }
            }
        }

        // ── Observe delivery summary → update KPI card ─────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Log.d(TAG, "Collecting deliverySummary flow")
                viewModel.deliverySummary.collectLatest { summary ->
                    Log.d(TAG, "deliverySummary emitted: " +
                            "distance=${summary.totalDistanceKm}, " +
                            "timeSaved=${summary.timeSavedMin}, " +
                            "gps=${summary.gpsStabilityScore}")

                    // Total Distance: format as "XX.X km"
                    val distanceStr = if (summary.totalDistanceKm >= 10) {
                        "${summary.totalDistanceKm.toInt()} km"
                    } else {
                        String.format("%.1f km", summary.totalDistanceKm)
                    }
                    binding.tvTotalDistance.text = distanceStr

                    // Time Saved: format as "XX min"
                    binding.tvTimeSaved.text = "${summary.timeSavedMin} min"

                    // GPS Stability Score: "XX%"
                    binding.tvGpsStability.text = "${summary.gpsStabilityScore}%"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
