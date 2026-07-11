package com.safescan

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val path = Path()
    
    // Fill paint for the inside of the document
    private val fillPaint = Paint().apply {
        color = Color.parseColor("#2610B981") // 15% opacity emerald green
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Stroke paint for the document border
    private val strokePaint = Paint().apply {
        color = Color.parseColor("#FF10B981") // Full emerald green
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        pathEffect = CornerPathEffect(24f) // Smooth rounded corners for high-quality feel
    }

    // Corner bracket paint for a precise camera-scanner look
    private val bracketPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    // Laser line paint for beautiful scanning animations
    private val laserPaint = Paint().apply {
        color = Color.parseColor("#E610B981") // 90% opacity emerald green
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    // Soft laser glow trailing paint
    private val laserGlowPaint = Paint().apply {
        color = Color.parseColor("#1510B981") // 8% opacity emerald green
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val currentCorners = ArrayList<PointF>()
    private var targetCorners: List<PointF>? = null
    private var opacityAnimator: ValueAnimator? = null
    private var overlayAlpha = 0f // Smooth fade in/out

    private val lerpFactor = 0.20f // Low pass filter factor to completely eliminate jitter (smoothed transition)

    private fun animateAlpha(target: Float) {
        if (opacityAnimator?.isRunning == true) {
            val currentTarget = opacityAnimator?.animatedValue as? Float
            if (currentTarget == target) return
            opacityAnimator?.cancel()
        }
        opacityAnimator = ValueAnimator.ofFloat(overlayAlpha, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                overlayAlpha = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun updateCorners(newCorners: List<PointF>?) {
        if (newCorners == null || newCorners.size != 4) {
            targetCorners = null
            animateAlpha(0f)
        } else {
            val sorted = sortScreenCorners(newCorners)
            targetCorners = sorted
            if (currentCorners.isEmpty()) {
                currentCorners.clear()
                for (pt in sorted) {
                    currentCorners.add(PointF(pt.x, pt.y))
                }
            }
            animateAlpha(1f)
            postInvalidateOnAnimation()
        }
    }

    private fun sortScreenCorners(pts: List<PointF>): List<PointF> {
        if (pts.size != 4) return pts
        val sums = pts.map { it.x + it.y }
        val diffs = pts.map { it.y - it.x }
        val tl = pts[sums.indexOf(sums.minOrNull()!!)]
        val br = pts[sums.indexOf(sums.maxOrNull()!!)]
        val tr = pts[diffs.indexOf(diffs.minOrNull()!!)]
        val bl = pts[diffs.indexOf(diffs.maxOrNull()!!)]
        return listOf(tl, tr, br, bl)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val targets = targetCorners
        if (targets != null && targets.size == 4) {
            if (currentCorners.size == 4) {
                var needsMoreInvalidation = false
                for (i in 0..3) {
                    val cur = currentCorners[i]
                    val tar = targets[i]
                    val dx = tar.x - cur.x
                    val dy = tar.y - cur.y
                    if (Math.abs(dx) > 0.3f || Math.abs(dy) > 0.3f) {
                        cur.x += dx * lerpFactor
                        cur.y += dy * lerpFactor
                        needsMoreInvalidation = true
                    } else {
                        cur.x = tar.x
                        cur.y = tar.y
                    }
                }
                if (needsMoreInvalidation) {
                    postInvalidateOnAnimation()
                }
            }
        }

        if (currentCorners.size != 4 || overlayAlpha <= 0.01f) return

        // Set alpha based on animated overlayAlpha
        fillPaint.alpha = (38 * overlayAlpha).toInt() // Max 15% opacity
        strokePaint.alpha = (255 * overlayAlpha).toInt()
        bracketPaint.alpha = (255 * overlayAlpha).toInt()

        path.reset()
        path.moveTo(currentCorners[0].x, currentCorners[0].y)
        path.lineTo(currentCorners[1].x, currentCorners[1].y)
        path.lineTo(currentCorners[2].x, currentCorners[2].y)
        path.lineTo(currentCorners[3].x, currentCorners[3].y)
        path.close()

        // 1. Draw beautiful semi-transparent filled polygon
        canvas.drawPath(path, fillPaint)

        // 2. Draw smooth rounded emerald green border
        canvas.drawPath(path, strokePaint)

        // 3. Draw high-tech white alignment brackets at the corners
        drawCornerBrackets(canvas, currentCorners)

        // 4. Draw modern scanning laser line animation inside the clipped polygon
        drawScanningLaser(canvas)
    }

    private fun drawScanningLaser(canvas: Canvas) {
        if (currentCorners.size != 4) return
        
        canvas.save()
        try {
            canvas.clipPath(path)
            
            val minY = currentCorners.minOf { it.y }
            val maxY = currentCorners.maxOf { it.y }
            val minX = currentCorners.minOf { it.x }
            val maxX = currentCorners.maxOf { it.x }
            
            if (maxY > minY) {
                val animTime = System.currentTimeMillis() % 2400
                val progress = if (animTime < 1200) animTime / 1200f else (2400 - animTime) / 1200f
                val laserY = minY + (maxY - minY) * progress
                
                // Draw soft glow on top of laser
                val glowPath = Path().apply {
                    moveTo(minX - 50f, minY)
                    lineTo(maxX + 50f, minY)
                    lineTo(maxX + 50f, laserY)
                    lineTo(minX - 50f, laserY)
                    close()
                }
                laserGlowPaint.alpha = (20 * overlayAlpha).toInt()
                canvas.drawPath(glowPath, laserGlowPaint)
                
                // Draw the bright laser line itself
                laserPaint.alpha = (230 * overlayAlpha).toInt()
                canvas.drawLine(minX - 50f, laserY, maxX + 50f, laserY, laserPaint)
            }
        } catch (e: Exception) {
            // Safe fall-back if clipPath fails
        } finally {
            canvas.restore()
        }
        
        // Post invalidate to keep laser line animating smoothly
        postInvalidateOnAnimation()
    }

    private fun drawCornerBrackets(canvas: Canvas, pts: List<PointF>) {
        if (pts.size != 4) return
        val bracketLength = 48f
        
        for (i in 0..3) {
            val current = pts[i]
            val next = pts[(i + 1) % 4]
            val prev = pts[(i + 3) % 4] // (i - 1 + 4) % 4
            
            // Draw segment towards next corner
            val dxNext = next.x - current.x
            val dyNext = next.y - current.y
            val distNext = Math.sqrt((dxNext * dxNext + dyNext * dyNext).toDouble()).toFloat()
            if (distNext > 0) {
                canvas.drawLine(
                    current.x, current.y,
                    current.x + (dxNext / distNext) * bracketLength,
                    current.y + (dyNext / distNext) * bracketLength,
                    bracketPaint
                )
            }

            // Draw segment towards previous corner
            val dxPrev = prev.x - current.x
            val dyPrev = prev.y - current.y
            val distPrev = Math.sqrt((dxPrev * dxPrev + dyPrev * dyPrev).toDouble()).toFloat()
            if (distPrev > 0) {
                canvas.drawLine(
                    current.x, current.y,
                    current.x + (dxPrev / distPrev) * bracketLength,
                    current.y + (dyPrev / distPrev) * bracketLength,
                    bracketPaint
                )
            }
        }
    }
}
