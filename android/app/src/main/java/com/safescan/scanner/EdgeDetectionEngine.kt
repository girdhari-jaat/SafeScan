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

    fun detectEdgesSafe(bitmap: Bitmap): List<Point> {
        return detectEdges(bitmap) ?: getFallbackQuad(bitmap.width.toDouble(), bitmap.height.toDouble())
    }

    fun detectEdges(bitmap: Bitmap): List<Point>? {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        
        // Working scale for fast and noise-free edge detection
        val resizeRatio = 600.0 / Math.max(src.width(), src.height())
        val resized = Mat()
        if (resizeRatio < 1.0) {
            Imgproc.resize(src, resized, Size(src.width() * resizeRatio, src.height() * resizeRatio))
        } else {
            src.copyTo(resized)
        }

        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
        
        // Bilateral filter or Gaussian blur to remove textures/noise while keeping sharp borders
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        
        val maxArea = resized.width() * resized.height()
        val minArea = maxArea * 0.12 // Document must occupy at least 12% of the screen
        
        var bestPoints: List<Point>? = null
        var bestArea = 0.0

        // Multi-scale Canny edge detection to capture varying lighting/contrast scenarios
        val thresholds = listOf(30, 60, 90, 120)
        for (threshold in thresholds) {
            if (bestPoints != null) break // Stop early if we found a perfect candidate
            
            val edges = Mat()
            Imgproc.Canny(gray, edges, threshold.toDouble(), (threshold * 3).toDouble())
            
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            // Sort contours by area descending
            contours.sortByDescending { Imgproc.contourArea(it) }

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea) break // Since they are sorted, we can stop early
                
                val contour2f = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(contour2f, true)
                val approx = MatOfPoint2f()
                
                // Try to approximate with different epsilon factors to get exactly 4 corners
                var approxSuccess = false
                for (epsFactor in listOf(0.015, 0.02, 0.03, 0.04)) {
                    Imgproc.approxPolyDP(contour2f, approx, epsFactor * peri, true)
                    if (approx.total() == 4L) {
                        val approxPoints = approx.toArray().map { Point(it.x / resizeRatio, it.y / resizeRatio) }
                        if (isConvexPoints(approxPoints) && getMaxCosinePoints(approxPoints) < 0.4) {
                            val scaledArea = area / (resizeRatio * resizeRatio)
                            if (scaledArea > bestArea) {
                                bestArea = scaledArea
                                bestPoints = orderPoints(approxPoints)
                                approxSuccess = true
                                break
                            }
                        }
                    }
                    approx.release()
                }

                // FALLBACK WITHIN CONTOUR: If approxPolyDP failed but we have a large robust contour,
                // find its 4 extreme points (Top-Left, Top-Right, Bottom-Right, Bottom-Left)
                if (!approxSuccess && area > minArea) {
                    val extremePoints = getExtremePoints(contour).map { Point(it.x / resizeRatio, it.y / resizeRatio) }
                    if (extremePoints.size == 4 && isConvexPoints(extremePoints) && getMaxCosinePoints(extremePoints) < 0.4) {
                        val quadArea = getQuadArea(extremePoints)
                        if (quadArea > bestArea) {
                            bestArea = quadArea
                            bestPoints = orderPoints(extremePoints)
                        }
                    }
                }

                approx.release()
                contour2f.release()
            }
            
            edges.release()
            kernel.release()
            for (contour in contours) {
                contour.release()
            }
            hierarchy.release()
        }

        src.release()
        resized.release()
        gray.release()

        return bestPoints
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
        var maxCosine = 0.0
        val cvPoints = points.map { org.opencv.core.Point(it.x, it.y) }
        for (i in 2..4) {
            val cosine = Math.abs(angle(cvPoints[i % 4], cvPoints[i - 2], cvPoints[i - 1]))
            maxCosine = Math.max(maxCosine, cosine)
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
        val sums = pts.map { it.x + it.y }
        val diffs = pts.map { it.y - it.x }

        val tl = pts[sums.indexOf(sums.minOrNull()!!)]
        val br = pts[sums.indexOf(sums.maxOrNull()!!)]
        val tr = pts[diffs.indexOf(diffs.minOrNull()!!)]
        val bl = pts[diffs.indexOf(diffs.maxOrNull()!!)]

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
