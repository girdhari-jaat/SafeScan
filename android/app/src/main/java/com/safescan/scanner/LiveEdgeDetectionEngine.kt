package com.safescan.scanner

import androidx.camera.core.ImageProxy
import com.safescan.android.scanner.Point
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class LiveEdgeDetectionEngine {
    
    // Pre-allocated Mats for performance to avoid GC overhead during live preview
    private val src = Mat()
    private val resized = Mat()
    private val gray = Mat()
    private val blurred = Mat()
    private val edges = Mat()
    private val hierarchy = Mat()
    private val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))

    fun process(imageProxy: ImageProxy, onResult: (List<Point>) -> Unit) {
        val bitmap = imageProxy.toBitmap()
        
        Utils.bitmapToMat(bitmap, src)
        
        // Fast downscale for live detection (much faster FPS)
        val resizeRatio = 400.0 / Math.max(src.width(), src.height())
        if (resizeRatio < 1.0) {
            Imgproc.resize(src, resized, Size(src.width() * resizeRatio, src.height() * resizeRatio))
        } else {
            src.copyTo(resized)
        }
        
        // Convert to grayscale
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
        
        // Median blur is better for removing salt-and-pepper noise while preserving edges
        Imgproc.medianBlur(gray, blurred, 5)
        
        // Enhance contrast slightly using equalization could be heavy, so we rely on adaptive threshold or Canny
        Imgproc.Canny(blurred, edges, 40.0, 120.0) 
        
        // Morphological closing to connect fragmented edges
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
        
        val contours = ArrayList<MatOfPoint>()
        // RETR_EXTERNAL is faster and we only care about the outermost document contour
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        contours.sortByDescending { Imgproc.contourArea(it) }
        var foundCorners: List<Point>? = null
        
        val maxArea = resized.width() * resized.height()
        val minArea = maxArea * 0.15 // at least 15% of the frame
        
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minArea) break // since they are sorted, we can stop early
            
            val contour2f = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            val peri = Imgproc.arcLength(contour2f, true)
            // Stricter approximation to ensure quadrilateral
            Imgproc.approxPolyDP(contour2f, approx, 0.03 * peri, true)
            
            if (approx.total() == 4L) {
                if (isConvex(approx) && getMaxCosine(approx) < 0.35) { // Stricter angle check
                    val points = approx.toArray().toList()
                    foundCorners = orderPoints(points.map { Point(it.x / resizeRatio, it.y / resizeRatio) })
                    approx.release()
                    contour2f.release()
                    break
                }
            }
            approx.release()
            contour2f.release()
        }
        
        // Release contours
        for (contour in contours) {
            contour.release()
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
