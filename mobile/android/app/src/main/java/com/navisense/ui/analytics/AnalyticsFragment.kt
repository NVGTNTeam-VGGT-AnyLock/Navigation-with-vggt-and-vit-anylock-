package com.navisense.ui.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.navisense.databinding.FragmentAnalyticsBinding
import com.navisense.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Analytics screen displaying:
 * - A PieChart showing location distribution by category.
 * - A BarChart showing Visited vs Not Visited vs Favorites vs Others counts.
 * - A DistrictBarChart showing location counts per Kyiv district.
 * - Total location count.
 *
 * All data is read-only, derived from [MainViewModel.analyticsData].
 */
class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe analytics StateFlow → update charts
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.analyticsData.collectLatest { data ->
                    binding.pieChart.setData(data.categoryCounts)
                    binding.barChart.setData(
                        visited = data.visitedCount,
                        notVisited = data.notVisitedCount,
                        favorites = data.favoriteCount,
                        notFavorites = data.notFavoriteCount
                    )
                    binding.districtChart.setData(data.districtCounts)
                    binding.tvTotalCount.text = data.totalCount.toString()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
