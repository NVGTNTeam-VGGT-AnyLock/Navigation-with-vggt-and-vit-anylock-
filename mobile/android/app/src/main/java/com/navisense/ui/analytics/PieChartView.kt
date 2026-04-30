package com.navisense.ui.analytics

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.navisense.model.AppLocationCategory

/**
 * A custom Canvas-drawn PieChart that shows the distribution of locations
 * by category. Each category gets a distinct color slice.
 *
 * Data is provided via [setData].
 */
class PieChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    /** Data: map of category → count. */
    private var data: Map<String, Int> = emptyMap()
    private var total = 0

    /** Predefined colours for categories (cycled if more categories than colours). */
    private val categoryColors = mapOf(
        AppLocationCategory.MONUMENT.key to Color.parseColor("#E53935"),
        AppLocationCategory.GROCERY.key to Color.parseColor("#43A047"),
        AppLocationCategory.GAS_STATION.key to Color.parseColor("#FB8C00"),
        AppLocationCategory.RESTAURANT.key to Color.parseColor("#00ACC1"),
        AppLocationCategory.PHARMACY.key to Color.parseColor("#1E88E5")
    )

    fun setData(categoryCounts: Map<String, Int>) {
        data = categoryCounts
        total = categoryCounts.values.sum()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (total == 0) {
            // Draw "No data" message
            paint.color = Color.GRAY
            paint.textSize = 40f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("No data", width / 2f, height / 2f, paint)
            return
        }

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(centerX, centerY) - 40f

        var startAngle = -90f

        data.forEach { (category, count) ->
            val sweepAngle = (count.toFloat() / total) * 360f

            // Draw slice
            paint.color = categoryColors[category] ?: Color.GRAY
            paint.style = Paint.Style.FILL
            canvas.drawArc(
                centerX - radius, centerY - radius,
                centerX + radius, centerY + radius,
                startAngle, sweepAngle, true, paint
            )

            // Draw border between slices
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawArc(
                centerX - radius, centerY - radius,
                centerX + radius, centerY + radius,
                startAngle, sweepAngle, true, paint
            )

            // Draw percentage label
            if (sweepAngle > 15f) {
                val midAngle = startAngle + sweepAngle / 2f
                val labelRadius = radius * 0.65f
                val labelX = centerX + labelRadius * Math.cos(Math.toRadians(midAngle.toDouble())).toFloat()
                val labelY = centerY + labelRadius * Math.sin(Math.toRadians(midAngle.toDouble())).toFloat()
                val percent = (count.toFloat() / total * 100).toInt()
                canvas.drawText("$percent%", labelX, labelY + 12f, labelPaint)
            }

            startAngle += sweepAngle
        }

        // Draw legend below the chart
        drawLegend(canvas, centerX, radius)
    }

    private fun drawLegend(canvas: Canvas, centerX: Float, chartBottom: Float) {
        val legendY = chartBottom + 60f
        var xOffset = centerX - 150f

        data.forEach { (category, count) ->
            paint.color = categoryColors[category] ?: Color.GRAY
            paint.style = Paint.Style.FILL
            canvas.drawRect(xOffset, legendY - 12f, xOffset + 20f, legendY + 8f, paint)

            paint.color = Color.DKGRAY
            paint.textSize = 28f
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("$category ($count)", xOffset + 28f, legendY + 8f, paint)

            xOffset += 200f
        }
    }
}
