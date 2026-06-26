package com.safescan.scanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class EdgeDetectionEngine {

    // IMPROVEMENT: Added object Pool to reuse Mats and prevent memory churn
    object Pool {
        private val mats = mutableListOf<Mat>()

        fun acquire(): Mat {
            synchronized(mats) {
                if (mats.isNotEmpty()) {
                    return mats.removeAt(mats.size - 1).apply {
                        empty()
                    }
                }
            }
            return Mat()
        }

        fun release(mat: Mat) {
            synchronized(mats) {
                if (mats.size < 15) {
                    mats.add(mat)
                } else {
                    mat.release()
                }
            }
        }
    }

    // IMPROVEMENT: Added detectEdgesSafe for a robust try/catch wrapping around OpenCV calls
    fun detectEdgesSafe(bitmap: Bitmap): List<Point> {
        return try {
            detectEdges(bitmap) ?: getFallbackQuad(bitmap.width.toDouble(), bitmap.height.toDouble())
        } catch (e: Exception) {
            getFallbackQuad(bitmap.width.toDouble(), bitmap.height.toDouble())
        }
    }

    fun detectEdges(bitmap: Bitmap): List<Point>? {
        // IMPROVEMENT: Using Mat Pool to avoid garbage collection pressure
        val src = Pool.acquire()
        val gray = Pool.acquire()
        val blurred = Pool.acquire()
        val edges = Pool.acquire()
        val hierarchy = Pool.acquire()

        try {
            Utils.bitmapToMat(bitmap, src)

            // Convert to grayscale
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
            
            // Bilateral filter for edge-preserving smoothing
            Imgproc.bilateralFilter(gray, blurred, 9, 75.0, 75.0)

            // Dynamic thresholding (Otsu)
            Imgproc.threshold(blurred, blurred, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            
            // Morphology closing to fill gaps
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(blurred, blurred, Imgproc.MORPH_CLOSE, kernel)

            // Canny edge detection
            Imgproc.Canny(blurred, edges, 50.0, 150.0)

            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            contours.sortByDescending { Imgproc.contourArea(it) }

            for (contour in contours) {
                val contour2f = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(contour2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

                if (approx.total() == 4L) {
                    val points = approx.toList()
                    contour2f.release()
                    approx.release()
                    // Release remaining contours
                    for (c in contours) {
                        c.release()
                    }
                    return orderPoints(points)
                }
                contour2f.release()
                approx.release()
            }
            
            // Release contours on failure
            for (c in contours) {
                c.release()
            }
            
            // IMPROVEMENT: Return null if no robust quad found to allow showing Snackbar / Toast error
            return null

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            // IMPROVEMENT: Release Mats back to the Pool
            Pool.release(src)
            Pool.release(gray)
            Pool.release(blurred)
            Pool.release(edges)
            Pool.release(hierarchy)
        }
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
