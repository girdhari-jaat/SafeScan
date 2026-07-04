package com.safescan.scanner

import android.graphics.Bitmap
import com.safescan.android.scanner.Point
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
        
        val resizeRatio = 500.0 / Math.max(src.width(), src.height())
        val resized = Mat()
        if (resizeRatio < 1.0) {
            Imgproc.resize(src, resized, Size(src.width() * resizeRatio, src.height() * resizeRatio))
        } else {
            src.copyTo(resized)
        }

        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        
        var bestQuad: MatOfPoint2f? = null
        var maxArea = 0.0

        for (threshold in 20..160 step 20) {
            val edges = Mat()
            Imgproc.Canny(gray, edges, threshold.toDouble(), (threshold * 2).toDouble())
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edges, edges, kernel)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            for (contour in contours) {
                val contour2f = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(contour2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

                if (approx.total() == 4L) {
                    val area = Imgproc.contourArea(approx)
                    if (area > maxArea && area > (resized.width() * resized.height() * 0.05)) {
                        if (isConvex(approx) && getMaxCosine(approx) < 0.35) {
                            maxArea = area
                            bestQuad = approx
                        }
                    }
                }
            }
        }

        return bestQuad?.let { quad ->
            val points = quad.toArray().map { 
                Point(it.x / resizeRatio, it.y / resizeRatio) 
            }
            orderPoints(points)
        }
    }

    private fun getMaxCosine(approx: MatOfPoint2f): Double {
        var maxCosine = 0.0
        val points = approx.toArray()
        for (i in 2..4) {
            val cosine = Math.abs(angle(points[i % 4], points[i - 2], points[i - 1]))
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

    private fun isConvex(approx: MatOfPoint2f): Boolean {
        return Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))
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
