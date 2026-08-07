package com.safescan

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val strokePaint = Paint().apply {
        color = Color.parseColor("#FF10B981") // Emerald green
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private var corners: List<PointF>? = null
    private val edgePath = Path()

    fun clear() {
        if (corners != null) {
            corners = null
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (corners?.size == 4) {
            val viewW = width.toFloat()
            val viewH = height.toFloat()
            if (viewW > 0 && viewH > 0) {
                canvas.save()
                canvas.clipRect(0f, 0f, viewW, viewH)
                canvas.drawPath(edgePath, strokePaint)
                canvas.restore()
            } else {
                canvas.drawPath(edgePath, strokePaint)
            }
        }
    }

    fun getCorners(): List<PointF>? = corners

    fun updateCorners(newCorners: List<PointF>?) {
        if (corners == newCorners) return
        corners = newCorners
        edgePath.reset()
        newCorners?.let { pts ->
            if (pts.size == 4) {
                val viewW = width.toFloat().let { if (it > 0) it else Float.MAX_VALUE }
                val viewH = height.toFloat().let { if (it > 0) it else Float.MAX_VALUE }

                edgePath.moveTo(pts[0].x.coerceIn(0f, viewW), pts[0].y.coerceIn(0f, viewH))
                edgePath.lineTo(pts[1].x.coerceIn(0f, viewW), pts[1].y.coerceIn(0f, viewH))
                edgePath.lineTo(pts[2].x.coerceIn(0f, viewW), pts[2].y.coerceIn(0f, viewH))
                edgePath.lineTo(pts[3].x.coerceIn(0f, viewW), pts[3].y.coerceIn(0f, viewH))
                edgePath.close()
            }
        }
        invalidate()
    }
}

