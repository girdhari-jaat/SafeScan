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
        targetCorners = newCorners
        if (newCorners == null || newCorners.size != 4) {
            animateAlpha(0f)
        } else {
            if (currentCorners.isEmpty()) {
                currentCorners.clear()
                for (pt in newCorners) {
                    currentCorners.add(PointF(pt.x, pt.y))
                }
            }
            animateAlpha(1f)
            postInvalidateOnAnimation()
        }
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
    }

    private fun drawCornerBrackets(canvas: Canvas, pts: List<PointF>) {
        val bracketLength = 48f
        
        for (i in 0..3) {
            val pt = pts[i]
            
            // Determine direction of brackets based on corner index (ordered points: TL, TR, BR, BL)
            val dirX = when (i) {
                0 -> 1f   // TL goes Right
                1 -> -1f  // TR goes Left
                2 -> -1f  // BR goes Left
                3 -> 1f   // BL goes Right
                else -> 1f
            }
            val dirY = when (i) {
                0 -> 1f   // TL goes Down
                1 -> 1f   // TR goes Down
                2 -> -1f  // BR goes Up
                3 -> -1f  // BL goes Up
                else -> 1f
            }

            // Draw horizontal segment of the corner bracket
            canvas.drawLine(
                pt.x, pt.y,
                pt.x + dirX * bracketLength, pt.y,
                bracketPaint
            )
            // Draw vertical segment of the corner bracket
            canvas.drawLine(
                pt.x, pt.y,
                pt.x, pt.y + dirY * bracketLength,
                bracketPaint
            )
        }
    }
}
