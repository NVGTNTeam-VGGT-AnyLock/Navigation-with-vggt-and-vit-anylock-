package com.navisense.ui.analytics

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * A custom Canvas-drawn BarChart that shows "Visited" vs "Not Visited" counts.
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

    private var visitedCount = 0
    private var notVisitedCount = 0
    private var maxCount = 1

    fun setData(visited: Int, notVisited: Int) {
        visitedCount = visited
        notVisitedCount = notVisited
        maxCount = maxOf(visited, notVisited, 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val chartWidth = width.toFloat()
        val chartHeight = height - 80f // leave room for labels

        val barWidth = chartWidth * 0.3f
        val gap = chartWidth * 0.1f
        val baselineY = chartHeight

        // ── Draw "Visited" bar ────────────────────────────────────
        val visitedBarHeight = (visitedCount.toFloat() / maxCount) * (chartHeight - 40f)
        val visitedLeft = gap
        val visitedRight = visitedLeft + barWidth

        barPaint.color = Color.parseColor("#43A047") // Green
        barPaint.style = Paint.Style.FILL
        canvas.drawRect(visitedLeft, baselineY - visitedBarHeight, visitedRight, baselineY, barPaint)

        // Value label
        canvas.drawText(
            "$visitedCount",
            (visitedLeft + visitedRight) / 2f,
            baselineY - visitedBarHeight - 10f,
            valuePaint
        )

        // X-axis label
        canvas.drawText("Visited", (visitedLeft + visitedRight) / 2f, baselineY + 40f, labelPaint)

        // ── Draw "Not Visited" bar ────────────────────────────────
        val notVisitedBarHeight = (notVisitedCount.toFloat() / maxCount) * (chartHeight - 40f)
        val notVisitedLeft = visitedRight + gap
        val notVisitedRight = notVisitedLeft + barWidth

        barPaint.color = Color.parseColor("#E53935") // Red
        canvas.drawRect(
            notVisitedLeft, baselineY - notVisitedBarHeight,
            notVisitedRight, baselineY, barPaint
        )

        // Value label
        canvas.drawText(
            "$notVisitedCount",
            (notVisitedLeft + notVisitedRight) / 2f,
            baselineY - notVisitedBarHeight - 10f,
            valuePaint
        )

        // X-axis label
        canvas.drawText(
            "Not Visited",
            (notVisitedLeft + notVisitedRight) / 2f,
            baselineY + 40f,
            labelPaint
        )
    }
}
