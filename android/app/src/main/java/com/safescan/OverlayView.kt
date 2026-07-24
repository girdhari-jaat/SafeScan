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

        // Draw live edge detection polygon if corners are detected
        corners?.let { pts ->
            if (pts.size == 4) {
                edgePath.reset()
                edgePath.moveTo(pts[0].x, pts[0].y)
                edgePath.lineTo(pts[1].x, pts[1].y)
                edgePath.lineTo(pts[2].x, pts[2].y)
                edgePath.lineTo(pts[3].x, pts[3].y)
                edgePath.close()

                // Dynamic green outline for live detected document boundary
                canvas.drawPath(edgePath, strokePaint)
            }
        }
    }

    fun getCorners(): List<PointF>? = corners

    fun updateCorners(newCorners: List<PointF>?) {
        if (corners == newCorners) return
        corners = newCorners
        invalidate()
    }
}

