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

        val w = width.toFloat()
        val h = height.toFloat()

        if (w == 0f || h == 0f) return

        val padding = w * 0.03f
        val holeWidth = w - padding * 2
        val holeHeight = holeWidth / targetRatio

        val left = padding
        val top = (h - holeHeight) / 2f
        val right = w - padding
        val bottom = top + holeHeight

        holeRect.set(left, top, right, bottom)

        val path = Path()
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        
        val radius = 24f
        val holePath = Path()
        holePath.addRoundRect(holeRect, radius, radius, Path.Direction.CW)
        
        path.op(holePath, Path.Op.DIFFERENCE)
        
        canvas.drawPath(path, backgroundPaint)
        canvas.drawRoundRect(holeRect, radius, radius, strokePaint)
    }

    // Keep these to prevent compilation errors if called from Fragment
    fun getCorners(): List<PointF>? = null
    fun updateCorners(newCorners: List<PointF>?) {
        // Ignored for fixed overlay
    }
}
