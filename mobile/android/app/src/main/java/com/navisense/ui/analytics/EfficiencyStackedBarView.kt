package com.navisense.ui.analytics

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * A "Constant Width Stacked Bar" showing the efficiency ratio of
 * Visited / Not Visited locations.
 *
 * - The bar ALWAYS occupies the full width of the card (match_parent with minimal margins).
 * - Segments (Visited / Not Visited) divide this fixed length proportionally.
 * - Each segment has a distinct colour:
 *   Teal (Visited), Rose (Not Visited).
 * - Percentage labels above each segment, colour-coded legend below.
 * - Accepts localized label strings via [setLabels].
 */
class EfficiencyStackedBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Colours ────────────────────────────────────────────────────
    companion object {
        val COLOR_VISITED     = Color.parseColor("#2DD4BF") // Teal
        val COLOR_NOT_VISITED = Color.parseColor("#FB7185") // Rose
        val COLOR_BG          = Color.parseColor("#2A2D30") // Track background
    }

    // ── Paints ──────────────────────────────────────────────────────
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BG
        style = Paint.Style.FILL
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = Typeface.SANS_SERIF
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8") // Slate
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SANS_SERIF
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0") // Light text
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = Typeface.SANS_SERIF
    }
    private val legendDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── Data ────────────────────────────────────────────────────────
    private var visitedCount = 0
    private var notVisitedCount = 0
    private var total = 1

    // Localized labels (default English fallback)
    private var labelVisited = "Visited"
    private var labelNotVisited = "Not Visited"

    fun setData(visited: Int, notVisited: Int) {
        visitedCount = visited
        notVisitedCount = notVisited
        total = maxOf(visited + notVisited, 1)
        invalidate()
    }

    /**
     * Sets localized labels for the legend.
     * Call this before [setData] or whenever locale changes.
     */
    fun setLabels(visited: String, notVisited: String) {
        labelVisited = visited
        labelNotVisited = notVisited
        invalidate()
    }

    // ── Drawing ─────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val barHeight = 32f
        val barRadius = barHeight / 2f

        // ── Bar fills almost full width (constant width) ────────────
        val margin = 4f // minimal margin – bar spans nearly full width
        val barLeft = margin
        val barRight = width - margin
        val barWidth = barRight - barLeft

        // Vertical positioning: bar sits in the upper portion,
        // leaving room for percentage labels above and legend below.
        val barY = height * 0.28f
        val barCenterY = barY + barHeight / 2f

        // ── Background track ─────────────────────────────────────────
        val bgRect = RectF(barLeft, barY, barRight, barY + barHeight)
        canvas.drawRoundRect(bgRect, barRadius, barRadius, bgPaint)

        // ── Stacked segments (always divide full width proportionally) ──
        data class Segment(val label: String, val count: Int, val color: Int)
        val segments = listOf(
            Segment(labelVisited, visitedCount, COLOR_VISITED),
            Segment(labelNotVisited, notVisitedCount, COLOR_NOT_VISITED)
        )

        var currentX = barLeft

        segments.forEach { (_, count, color) ->
            if (count <= 0) return@forEach

            val fraction = count.toFloat() / total
            val segWidth = barWidth * fraction

            segmentPaint.color = color
            val segRect = RectF(currentX, barY, currentX + segWidth, barY + barHeight)
            canvas.drawRoundRect(segRect, barRadius, barRadius, segmentPaint)

            // Draw percentage label ABOVE the segment
            val percent = (count.toFloat() / total * 100).toInt()
            val labelX = currentX + segWidth / 2f
            canvas.drawText("$percent%", labelX, barY - 14f, percentPaint)

            currentX += segWidth
        }

        // ── Legend below the bar (2 evenly-spaced columns) ───────────
        val legendEntries = listOf(
            Triple(labelVisited, visitedCount, COLOR_VISITED),
            Triple(labelNotVisited, notVisitedCount, COLOR_NOT_VISITED)
        )

        val dotRadius = 8f
        val legendTop = barY + barHeight + 40f
        val columnWidth = barWidth / 2f

        legendEntries.forEachIndexed { index, (label, count, color) ->
            val colCenterX = barLeft + columnWidth * index + columnWidth / 2f

            // Colour dot
            legendDotPaint.color = color
            val dotX = colCenterX - 48f
            canvas.drawCircle(dotX, legendTop + 6f, dotRadius, legendDotPaint)

            // Count value (bold)
            canvas.drawText("$count", colCenterX + 8f, legendTop + 10f, countPaint)

            // Label text below
            canvas.drawText(label, colCenterX + 8f, legendTop + 40f, labelPaint)
        }
    }
}
