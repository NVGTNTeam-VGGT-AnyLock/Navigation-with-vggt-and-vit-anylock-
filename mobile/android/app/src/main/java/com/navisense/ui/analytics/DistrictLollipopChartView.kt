package com.navisense.ui.analytics

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * A modern Lollipop Chart for district-level statistics.
 *
 * Each district is represented as a thin vertical line (the "stick")
 * topped with a filled circle (the "lollipop"). The chart is sorted
 * by value descending, with the highest district first.
 *
 * Design philosophy:
 * - Minimal data-ink ratio (no grid lines, no axis labels)
 * - Clean typography with values shown above the lollipop heads
 * - Consistent colour using Teal (#2DD4BF) for all sticks and heads
 * - District names at the bottom, rotated for readability
 */
class DistrictLollipopChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Colours ─────────────────────────────────────────────────────
    companion object {
        val COLOR_PRIMARY   = Color.parseColor("#2DD4BF") // Teal
        val COLOR_SECONDARY = Color.parseColor("#5EEAD4") // Light Teal (gradient effect)
        val COLOR_TEXT      = Color.parseColor("#E2E8F0")
        val COLOR_MUTED     = Color.parseColor("#94A3B8")
        val COLOR_BG_LINE   = Color.parseColor("#2A2D30")
    }

    // ── Paints ──────────────────────────────────────────────────────
    private val stickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_PRIMARY
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_PRIMARY
        style = Paint.Style.FILL
    }

    private val headGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2DD4BF") // Semi-transparent Teal
        style = Paint.Style.FILL
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = Typeface.SANS_SERIF
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_MUTED
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SANS_SERIF
    }

    private val bgLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BG_LINE
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    // ── Data ────────────────────────────────────────────────────────
    private var data: Map<String, Int> = emptyMap()
    private var maxCount = 1
    private var sortedEntries: List<Map.Entry<String, Int>> = emptyList()

    fun setData(districtCounts: Map<String, Int>) {
        data = districtCounts
        maxCount = maxOf(districtCounts.values.maxOrNull() ?: 1, 1)
        sortedEntries = districtCounts.entries.sortedByDescending { it.value }
        invalidate()
    }

    // ── Drawing ─────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (data.isEmpty()) {
            drawNoData(canvas)
            return
        }

        val count = sortedEntries.size
        if (count == 0) return

        val chartTop = 40f
        val chartBottom = height - 20f
        val chartHeight = chartBottom - chartTop

        val labelAreaHeight = 120f
        val stickBottom = chartBottom - labelAreaHeight

        val margin = 24f
        val totalSpacing = width - margin * 2
        val columnWidth = totalSpacing / count
        val stickMaxHeight = chartHeight - labelAreaHeight - 30f

        // ── Draw faint baseline ─────────────────────────────────────
        canvas.drawLine(
            margin, stickBottom,
            width - margin, stickBottom,
            bgLinePaint
        )

        sortedEntries.forEachIndexed { index, (district, countValue) ->
            val centerX = margin + columnWidth * index + columnWidth / 2f
            val fraction = countValue.toFloat() / maxCount
            val stickHeight = fraction * stickMaxHeight
            val stickTop = stickBottom - stickHeight
            val headRadius = 14f

            // ── Stick (thin line) ───────────────────────────────────
            stickPaint.color = COLOR_PRIMARY
            canvas.drawLine(centerX, stickBottom, centerX, stickTop, stickPaint)

            // ── Glow (outer ring) ───────────────────────────────────
            canvas.drawCircle(centerX, stickTop, headRadius + 6f, headGlowPaint)

            // ── Head (filled circle) ────────────────────────────────
            headPaint.color = COLOR_PRIMARY
            canvas.drawCircle(centerX, stickTop, headRadius, headPaint)

            // ── Value above head ────────────────────────────────────
            canvas.drawText(
                "$countValue",
                centerX,
                stickTop - headRadius - 12f,
                valuePaint
            )

            // ── District label at bottom (rotated -45° to avoid cramping) ──
            canvas.save()
            canvas.rotate(-45f, centerX, stickBottom + 36f)

            // Use full district name without truncation since rotation gives more room
            val displayName = district

            // Adjust label paint alignment for rotated text
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                displayName,
                centerX,
                stickBottom + 36f,
                labelPaint
            )
            canvas.restore()

            // Reset alignment for the next iteration (other text may use CENTER)
            labelPaint.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawNoData(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 36f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.SANS_SERIF
        }
        canvas.drawText("No district data", width / 2f, height / 2f, paint)
    }
}
