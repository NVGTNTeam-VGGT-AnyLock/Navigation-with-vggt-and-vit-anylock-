package com.navisense.ui.analytics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A custom Canvas-drawn horizontal bar chart showing location counts per district.
 *
 * Data is provided via [setData].
 */
class DistrictBarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.LEFT
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 28f
        textAlign = Paint.Align.LEFT
    }

    /** Data: map of district name → count. */
    private var data: Map<String, Int> = emptyMap()
    private var maxCount = 1

    /** Distinct colors for districts, cycled. */
    private val districtColors = listOf(
        0xFF1565C0.toInt(),  // Blue
        0xFF43A047.toInt(),  // Green
        0xFFFB8C00.toInt(),  // Orange
        0xFFE53935.toInt(),  // Red
        0xFF8E24AA.toInt(),  // Purple
        0xFF00ACC1.toInt(),  // Cyan
        0xFFF06292.toInt(),  // Pink
        0xFF6D4C41.toInt(),  // Brown
    )

    fun setData(districtCounts: Map<String, Int>) {
        data = districtCounts
        maxCount = maxOf(districtCounts.values.maxOrNull() ?: 1, 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (data.isEmpty()) {
            paintNoData(canvas)
            return
        }

        val chartLeft = 160f  // Space for district labels
        val chartWidth = width.toFloat() - chartLeft - 60f  // Space for value labels
        val chartTop = 20f
        val barHeight = 36f
        val barGap = 8f
        val totalBarHeight = barHeight + barGap

        val sortedEntries = data.entries.sortedByDescending { it.value }

        sortedEntries.forEachIndexed { index, (district, count) ->
            val topY = chartTop + index * totalBarHeight

            // District label
            labelPaint.color = Color.WHITE
            canvas.drawText(
                district,
                8f,
                topY + barHeight - 8f,
                labelPaint
            )

            // Bar
            val barWidth = (count.toFloat() / maxCount) * chartWidth
            val barRight = chartLeft + barWidth.coerceAtLeast(4f)

            barPaint.color = districtColors[index % districtColors.size]
            barPaint.style = Paint.Style.FILL
            canvas.drawRect(chartLeft, topY, barRight, topY + barHeight, barPaint)

            // Value label at end of bar
            valuePaint.color = Color.WHITE
            canvas.drawText(
                "$count",
                barRight + 12f,
                topY + barHeight - 8f,
                valuePaint
            )
        }
    }

    private fun paintNoData(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 40f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No data", width / 2f, height / 2f, paint)
    }
}
