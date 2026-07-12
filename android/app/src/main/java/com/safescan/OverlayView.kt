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
        // Overlay visuals are now handled by ViewfinderOverlay in Compose
        super.onDraw(canvas)
    }

    // Keep these to prevent compilation errors if called from Fragment
    fun getCorners(): List<PointF>? = null
    fun updateCorners(newCorners: List<PointF>?) {
        // Ignored for fixed overlay
    }
}
