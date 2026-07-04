package com.safescan.scanner

import androidx.camera.core.ImageProxy
import com.safescan.android.scanner.Point
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class LiveEdgeDetectionEngine {
    fun process(imageProxy: ImageProxy, onResult: (List<Point>) -> Unit) {
        val bitmap = imageProxy.toBitmap()
        
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        // Fast downscale for live detection (much faster FPS)
        val resizeRatio = 300.0 / Math.max(src.width(), src.height())
        val resized = Mat()
        if (resizeRatio < 1.0) {
            Imgproc.resize(src, resized, Size(src.width() * resizeRatio, src.height() * resizeRatio))
        } else {
            src.copyTo(resized)
        }

        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0) // Lower thresholds for live preview to catch more edges
        
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        contours.sortByDescending { Imgproc.contourArea(it) }

        var foundCorners: List<Point>? = null
        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            val peri = Imgproc.arcLength(contour2f, true)
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

            if (approx.total() == 4L) {
                val area = Imgproc.contourArea(approx)
                if (area > (resized.width() * resized.height() * 0.1)) {
                    if (isConvex(approx) && getMaxCosine(approx) < 0.4) {
                        val points = approx.toArray().toList()
                        foundCorners = orderPoints(points.map { Point(it.x / resizeRatio, it.y / resizeRatio) })
                        break
                    }
                }
            }
        }
        
        if (foundCorners != null) {
            onResult(foundCorners)
        }
        
        imageProxy.close()
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
}
