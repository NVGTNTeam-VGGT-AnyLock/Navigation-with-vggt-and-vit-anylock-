package com.navisense.ui.analytics

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * A custom Canvas-drawn BarChart that shows "Visited" vs "Favorites" counts.
 *
 * Data is provided via [setData].
 */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Four bars: Visited, Not Visited, Favorites, Not Favorites
    private var visitedCount = 0
    private var notVisitedCount = 0
    private var favoriteCount = 0
    private var notFavoriteCount = 0
    private var maxCount = 1

    fun setData(visited: Int, notVisited: Int, favorites: Int = 0, notFavorites: Int = 0) {
        visitedCount = visited
        notVisitedCount = notVisited
        favoriteCount = favorites
        notFavoriteCount = notFavorites
        maxCount = maxOf(visited, notVisited, favorites, notFavorites, 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val chartWidth = width.toFloat()
        val chartHeight = height - 80f // leave room for labels

        val totalBars = 4
        val totalGaps = totalBars + 1
        val gap = chartWidth * 0.06f
        val barWidth = (chartWidth - gap * totalGaps) / totalBars
        val baselineY = chartHeight

        val bars = listOf(
            Triple("Visited", visitedCount, Color.parseColor("#43A047")),  // Green
            Triple("Not Visited", notVisitedCount, Color.parseColor("#E53935")),  // Red
            Triple("Favorites", favoriteCount, Color.parseColor("#E91E63")),  // Pink
            Triple("Others", notFavoriteCount, Color.parseColor("#9E9E9E"))   // Gray
        )

        bars.forEachIndexed { index, (label, count, color) ->
            val left = gap + index * (barWidth + gap)
            val right = left + barWidth
            val barHeight = (count.toFloat() / maxCount) * (chartHeight - 40f)

            // Draw bar
            barPaint.color = color
            barPaint.style = Paint.Style.FILL
            canvas.drawRect(left, baselineY - barHeight, right, baselineY, barPaint)

            // Value label above bar
            canvas.drawText(
                "$count",
                (left + right) / 2f,
                baselineY - barHeight - 10f,
                valuePaint
            )

            // X-axis label below bar
            canvas.drawText(label, (left + right) / 2f, baselineY + 40f, labelPaint)
        }
    }
}
