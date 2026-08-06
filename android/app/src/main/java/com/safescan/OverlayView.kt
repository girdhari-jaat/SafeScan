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
        strokeWidth = context.resources.displayMetrics.density * 3.5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#3310B981") // Semi-transparent emerald green (20% opacity)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#FF10B981") // Emerald green
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var corners: List<PointF>? = null
    private val edgePath = Path()

    fun clear() {
        if (corners != null) {
            corners = null
            edgePath.reset()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        corners?.let { pts ->
            if (pts.size == 4) {
                // Draw semi-transparent green polygon fill
                canvas.drawPath(edgePath, fillPaint)
                // Draw green outline
                canvas.drawPath(edgePath, strokePaint)
                // Draw corner dots
                val dotRadius = context.resources.displayMetrics.density * 4f
                for (pt in pts) {
                    canvas.drawCircle(pt.x, pt.y, dotRadius, cornerPaint)
                }
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
                edgePath.moveTo(pts[0].x, pts[0].y)
                edgePath.lineTo(pts[1].x, pts[1].y)
                edgePath.lineTo(pts[2].x, pts[2].y)
                edgePath.lineTo(pts[3].x, pts[3].y)
                edgePath.close()
            }
        }
        invalidate()
    }
}

