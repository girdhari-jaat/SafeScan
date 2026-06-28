package com.safescan.scanner

import android.graphics.Bitmap
import com.safescan.android.scanner.Point
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EdgeDetectionEngine @Inject constructor() {

    fun detectEdgesSafe(bitmap: Bitmap): List<Point> {
        return detectEdges(bitmap) ?: getFallbackQuad(bitmap.width.toDouble(), bitmap.height.toDouble())
    }

    /**
     * Attempts to detect document edges.
     * In a pure Kotlin implementation without OpenCV, we use a robust fallback
     * that ensures the crop tool is always functional.
     */
    fun detectEdges(bitmap: Bitmap): List<Point>? {
        val width = bitmap.width.toDouble()
        val height = bitmap.height.toDouble()
        
        // Return a slightly smaller rectangle as the "detected" document
        return getFallbackQuad(width, height)
    }

    private fun orderPoints(pts: List<Point>): List<Point> {
        if (pts.size != 4) return pts
        val sums = pts.map { it.x + it.y }
        val diffs = pts.map { it.y - it.x }

        val tl = pts[sums.indexOf(sums.minOrNull()!!)]
        val br = pts[sums.indexOf(sums.maxOrNull()!!)]
        val tr = pts[diffs.indexOf(diffs.minOrNull()!!)]
        val bl = pts[diffs.indexOf(diffs.maxOrNull()!!)]

        return listOf(tl, tr, br, bl)
    }
    
    fun getFallbackQuad(w: Double, h: Double): List<Point> {
        val paddingX = w * 0.1
        val paddingY = h * 0.1
        return listOf(
            Point(paddingX, paddingY),
            Point(w - paddingX, paddingY),
            Point(w - paddingX, h - paddingY),
            Point(paddingX, h - paddingY)
        )
    }
}
