package com.safescan

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#99000000") // 60% black
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val strokePaint = Paint().apply {
        color = Color.parseColor("#FF10B981") // Emerald green
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private var targetRatio: Float = 1.4142f // Default A4
    private val holeRect = RectF()

    private var corners: List<PointF>? = null

    fun setAspectRatio(ratio: Float) {
        if (targetRatio != ratio) {
            targetRatio = ratio
            invalidate()
        }
    }
    
    fun getHoleRect(): RectF {
        return holeRect
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw live edge detection corners if available
        corners?.let { pts ->
            if (pts.size == 4) {
                val edgePath = Path()
                edgePath.moveTo(pts[0].x, pts[0].y)
                edgePath.lineTo(pts[1].x, pts[1].y)
                edgePath.lineTo(pts[2].x, pts[2].y)
                edgePath.lineTo(pts[3].x, pts[3].y)
                edgePath.close()
                canvas.drawPath(edgePath, strokePaint)
            }
        }
    }

    // Keep these to prevent compilation errors if called from Fragment
    fun getCorners(): List<PointF>? = corners
    fun updateCorners(newCorners: List<PointF>?) {
        corners = newCorners
        invalidate()
    }
}
