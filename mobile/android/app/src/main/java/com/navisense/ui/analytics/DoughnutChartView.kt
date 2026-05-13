package com.navisense.ui.analytics

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * A premium thin DoughnutChart showing category distribution with:
 * - Thin arc segments (20dp stroke)
 * - "Total Points" text rendered in the center
 * - Legend on the right side with custom circular markers
 * - Clean typography matching the McKinsey/BCG aesthetic
 *
 * Data is provided via [setData].
 * Category keys are expected to be already-localized display strings.
 */
class DoughnutChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Paints ──────────────────────────────────────────────────────
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }

    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = Typeface.SANS_SERIF
    }

    private val centerSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8") // Slate
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SANS_SERIF
    }

    private val legendDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val legendTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0") // Light text
        textSize = 30f
        textAlign = Paint.Align.LEFT
        typeface = Typeface.SANS_SERIF
        isFakeBoldText = true
    }

    private val legendValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8") // Slate
        textSize = 26f
        textAlign = Paint.Align.LEFT
        typeface = Typeface.SANS_SERIF
    }

    private val legendPercentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B") // Muted
        textSize = 22f
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.SANS_SERIF
    }

    // ── Data ────────────────────────────────────────────────────────
    private var data: Map<String, Int> = emptyMap()
    private var total = 0

    /**
     * Fixed palette assigned in entry order.
     * Decoupled from raw keys so the chart works with any localized display string.
     */
    private val palette = listOf(
        Color.parseColor("#2DD4BF"), // Teal
        Color.parseColor("#F59E0B"), // Amber
        Color.parseColor("#FB7185"), // Rose
        Color.parseColor("#818CF8"), // Indigo
        Color.parseColor("#34D399"), // Emerald
        Color.parseColor("#94A3B8")  // Slate (fallback / "Others")
    )

    // ── Public API ──────────────────────────────────────────────────

    fun setData(categoryCounts: Map<String, Int>) {
        data = categoryCounts
        total = categoryCounts.values.sum()
        invalidate()
    }

    // ── Drawing ─────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (total == 0) {
            drawNoData(canvas)
            return
        }

        val chartAreaWidth = width * 0.55f // Left 55% for doughnut
        val centerX = chartAreaWidth / 2f
        val centerY = height / 2f
        val radius = minOf(centerX, centerY) - 30f

        // ── Draw arc segments ───────────────────────────────────────
        var startAngle = -90f
        val sortedEntries = data.entries.sortedByDescending { it.value }

        sortedEntries.forEachIndexed { index, (_, count) ->
            val sweepAngle = (count.toFloat() / total) * 360f
            arcPaint.color = palette[index % palette.size]
            canvas.drawArc(
                centerX - radius, centerY - radius,
                centerX + radius, centerY + radius,
                startAngle, sweepAngle, false, arcPaint
            )
            startAngle += sweepAngle
        }

        // ── Draw center text ────────────────────────────────────────
        val totalStr = total.toString()
        val centerTextY = centerY - 8f
        val subTextY = centerY + 28f
        canvas.drawText(totalStr, centerX, centerTextY, centerTextPaint)
        canvas.drawText("Points", centerX, subTextY, centerSubTextPaint)

        // ── Draw right-side legend ──────────────────────────────────
        drawLegend(canvas, sortedEntries)
    }

    private fun drawLegend(canvas: Canvas, sortedEntries: List<Map.Entry<String, Int>>) {
        val legendLeft = width * 0.58f
        val dotRadius = 10f
        val rowHeight = 52f
        val startY = (height - (sortedEntries.size * rowHeight)) / 2f + 24f

        sortedEntries.forEachIndexed { index, (category, count) ->
            val y = startY + index * rowHeight

            // Dot marker
            legendDotPaint.color = palette[index % palette.size]
            canvas.drawCircle(legendLeft + dotRadius, y, dotRadius, legendDotPaint)

            // Category name (already localized)
            val labelX = legendLeft + dotRadius * 3 + 12f
            canvas.drawText(category, labelX, y + 6f, legendTitlePaint)

            // Count value
            val valueStr = count.toString()
            val valueX = width - 16f
            canvas.drawText(valueStr, valueX, y + 6f, legendValuePaint)

            // Percentage
            val percentStr = "${(count.toFloat() / total * 100).toInt()}%"
            val percentX = valueX - legendValuePaint.measureText(valueStr) - 16f
            canvas.drawText(percentStr, percentX, y + 6f, legendPercentPaint)
        }
    }

    private fun drawNoData(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 36f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.SANS_SERIF
        }
        canvas.drawText("No data", width / 2f, height / 2f, paint)
    }
}
