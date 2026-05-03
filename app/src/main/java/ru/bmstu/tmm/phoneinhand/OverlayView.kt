package ru.bmstu.tmm.phoneinhand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var analysis: PhoneAnalysis? = null

    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.rgb(255, 82, 82)
    }
    private val personPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(66, 165, 245)
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 15, 23, 42)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        strokeWidth = 1f
    }

    fun submitAnalysis(value: PhoneAnalysis?) {
        analysis = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = analysis ?: return
        if (value.imageWidth <= 0 || value.imageHeight <= 0) return

        val scaleX = width.toFloat() / value.imageWidth.toFloat()
        val scaleY = height.toFloat() / value.imageHeight.toFloat()

        value.person?.let {
            drawBox(canvas, it, scaleX, scaleY, personPaint, "person ${(it.score * 100).toInt()}%")
        }
        value.phone?.let {
            drawBox(canvas, it, scaleX, scaleY, phonePaint, "phone ${(it.score * 100).toInt()}%")
        }
    }

    private fun drawBox(
        canvas: Canvas,
        detection: DetectionBox,
        scaleX: Float,
        scaleY: Float,
        paint: Paint,
        label: String
    ) {
        val rect = RectF(
            detection.rect.left * scaleX,
            detection.rect.top * scaleY,
            detection.rect.right * scaleX,
            detection.rect.bottom * scaleY
        )
        canvas.drawRoundRect(rect, 6f, 6f, paint)

        val labelWidth = labelPaint.measureText(label) + 24f
        val labelHeight = 46f
        val labelTop = max(0f, rect.top - labelHeight)
        canvas.drawRoundRect(
            rect.left,
            labelTop,
            rect.left + labelWidth,
            labelTop + labelHeight,
            6f,
            6f,
            labelBackgroundPaint
        )
        canvas.drawText(label, rect.left + 12f, labelTop + 33f, labelPaint)
    }
}
