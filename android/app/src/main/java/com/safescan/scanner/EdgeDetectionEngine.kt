package com.safescan.scanner

import android.graphics.Bitmap
import com.safescan.domain.model.Point
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class EdgeDetectionEngine {

    @Synchronized
    fun detectEdgesSafe(bitmap: Bitmap): List<Point> {
        return detectEdges(bitmap) ?: getFallbackQuad(bitmap.width.toDouble(), bitmap.height.toDouble())
    }

    @Synchronized
    fun detectEdges(bitmap: Bitmap): List<Point>? {
        if (bitmap.isRecycled) return null
        var src: Mat? = null
        var resized: Mat? = null
        var gray: Mat? = null
        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)
            
            // Working scale for fast, high-quality edge detection (max 1000px)
            val maxDim = 1000.0
            val resizeRatio = maxDim / Math.max(src.width(), src.height())
            resized = Mat()
            val scaleFactor = if (resizeRatio < 1.0) {
                Imgproc.resize(src, resized, Size(src.width() * resizeRatio, src.height() * resizeRatio))
                resizeRatio
            } else {
                src.copyTo(resized)
                1.0
            }

            gray = Mat()
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
            
            // GaussianBlur for removing high-frequency noise while keeping document boundaries
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            
            val maxArea = (resized.width() * resized.height()).toDouble()
            val minArea = maxArea * 0.01 // Support small documents (at least 1% of the screen area)
            
            var bestPoints: List<Point>? = null
            var bestScore = -Double.MAX_VALUE

            // Canny thresholds [40, 70, 100] as requested
            val thresholds = listOf(40.0, 70.0, 100.0)
            for (threshold in thresholds) {
                var edges: Mat? = null
                var kernel: Mat? = null
                val contours = ArrayList<MatOfPoint>()
                var hierarchy: Mat? = null
                try {
                    edges = Mat()
                    Imgproc.Canny(gray, edges, threshold, threshold * 2.5)
                    
                    kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
                    Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

                    hierarchy = Mat()
                    // Use RETR_LIST to find nested contours, allowing detection even with textual boundaries inside the document
                    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

                    for (contour in contours) {
                        val area = Imgproc.contourArea(contour)
                        if (area < minArea) continue
                        
                        var contour2f: MatOfPoint2f? = null
                        try {
                            contour2f = MatOfPoint2f(*contour.toArray())
                            val peri = Imgproc.arcLength(contour2f, true)
                            
                            // Try to approximate with different epsilon factors to get exactly 4 corners
                            for (epsFactor in listOf(0.015, 0.02, 0.03, 0.04)) {
                                val approx = MatOfPoint2f()
                                try {
                                    Imgproc.approxPolyDP(contour2f, approx, epsFactor * peri, true)
                                    if (approx.total() == 4L) {
                                        val approxPointsResized = approx.toArray().map { Point(it.x, it.y) }
                                        
                                        if (isConvexPoints(approxPointsResized)) {
                                            val maxAngleError = getMaxCosinePoints(approxPointsResized)
                                            // Ensure the angle error is within threshold (< 0.5) to verify a good rectangular shape
                                            if (maxAngleError < 0.5) {
                                                val normArea = area / maxArea
                                                val score = normArea - maxAngleError
                                                if (score > bestScore) {
                                                    bestScore = score
                                                    val approxPointsOriginal = approxPointsResized.map { Point(it.x / scaleFactor, it.y / scaleFactor) }
                                                    bestPoints = orderPoints(approxPointsOriginal)
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    approx.release()
                                }
                            }

                            // FALLBACK WITHIN CONTOUR: If approxPolyDP failed, try extreme points
                            val extremePointsResized = getExtremePoints(contour).map { Point(it.x, it.y) }
                            if (extremePointsResized.size == 4 && isConvexPoints(extremePointsResized)) {
                                val maxAngleError = getMaxCosinePoints(extremePointsResized)
                                if (maxAngleError < 0.5) {
                                    val quadArea = getQuadArea(extremePointsResized)
                                    if (quadArea >= minArea) {
                                        val normArea = quadArea / maxArea
                                        val score = normArea - maxAngleError
                                        if (score > bestScore) {
                                            bestScore = score
                                            val extremePointsOriginal = extremePointsResized.map { Point(it.x / scaleFactor, it.y / scaleFactor) }
                                            bestPoints = orderPoints(extremePointsOriginal)
                                        }
                                    }
                                }
                            }
                        } finally {
                            contour2f?.release()
                        }
                    }
                } finally {
                    edges?.release()
                    kernel?.release()
                    contours.forEach { it.release() }
                    hierarchy?.release()
                }
            }

            return bestPoints
        } finally {
            src?.release()
            resized?.release()
            gray?.release()
        }
    }

    private fun getExtremePoints(contour: MatOfPoint): List<Point> {
        val pts = contour.toArray().map { Point(it.x, it.y) }
        if (pts.isEmpty()) return emptyList()

        val sums = pts.map { it.x + it.y }
        val diffs = pts.map { it.y - it.x }

        val tl = pts[sums.indexOf(sums.minOrNull()!!)]
        val br = pts[sums.indexOf(sums.maxOrNull()!!)]
        val tr = pts[diffs.indexOf(diffs.minOrNull()!!)]
        val bl = pts[diffs.indexOf(diffs.maxOrNull()!!)]

        return listOf(tl, tr, br, bl)
    }

    private fun isConvexPoints(points: List<Point>): Boolean {
        if (points.size != 4) return false
        val matOfPoint = MatOfPoint(*points.map { org.opencv.core.Point(it.x, it.y) }.toTypedArray())
        val convex = Imgproc.isContourConvex(matOfPoint)
        matOfPoint.release()
        return convex
    }

    private fun getQuadArea(points: List<Point>): Double {
        val mat = MatOfPoint2f(*points.map { org.opencv.core.Point(it.x, it.y) }.toTypedArray())
        val area = Imgproc.contourArea(mat)
        mat.release()
        return area
    }

    private fun getMaxCosinePoints(points: List<Point>): Double {
        if (points.size != 4) return 1.0
        var maxCosine = 0.0
        val cvPoints = points.map { org.opencv.core.Point(it.x, it.y) }
        for (i in 0..3) {
            val cosine = Math.abs(angle(cvPoints[(i + 1) % 4], cvPoints[(i + 3) % 4], cvPoints[i]))
            if (cosine > maxCosine) {
                maxCosine = cosine
            }
        }
        return maxCosine
    }

    private fun angle(pt1: org.opencv.core.Point, pt2: org.opencv.core.Point, pt0: org.opencv.core.Point): Double {
        val dx1 = pt1.x - pt0.x
        val dy1 = pt1.y - pt0.y
        val dx2 = pt2.x - pt0.x
        val dy2 = pt2.y - pt0.y
        return (dx1 * dx2 + dy1 * dy2) / Math.sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10)
    }

    private fun orderPoints(pts: List<Point>): List<Point> {
        if (pts.size < 4) return pts

        val sums = pts.map { it.x + it.y }
        val diffs = pts.map { it.y - it.x }

        val minSum = sums.minOrNull() ?: 0.0
        val maxSum = sums.maxOrNull() ?: 0.0
        val minDiff = diffs.minOrNull() ?: 0.0
        val maxDiff = diffs.maxOrNull() ?: 0.0

        val tl = pts[sums.indexOf(minSum)]
        val br = pts[sums.indexOf(maxSum)]
        val tr = pts[diffs.indexOf(minDiff)]
        val bl = pts[diffs.indexOf(maxDiff)]

        return listOf(tl, tr, br, bl)
    }
    
    fun getFallbackQuad(w: Double, h: Double): List<Point> {
        val paddingX = w * 0.05
        val paddingY = h * 0.05
        return listOf(
            Point(paddingX, paddingY),
            Point(w - paddingX, paddingY),
            Point(w - paddingX, h - paddingY),
            Point(paddingX, h - paddingY)
        )
    }
}
