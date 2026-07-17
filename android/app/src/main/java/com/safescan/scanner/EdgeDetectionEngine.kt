package com.safescan.scanner

import android.graphics.Bitmap
import android.util.Log
import com.safescan.domain.model.Point
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class EdgeDetectionEngine {

    companion object {
        private const val TAG = "EdgeDetectionEngine"
    }

    @Synchronized
    fun detectEdgesSafe(bitmap: Bitmap, mode: com.safescan.data.ScannerMode? = null): List<Point> {
        return detectEdges(bitmap, mode) ?: getFallbackQuad(bitmap.width.toDouble(), bitmap.height.toDouble())
    }

    @Synchronized
    fun detectEdges(bitmap: Bitmap, mode: com.safescan.data.ScannerMode? = null): List<Point>? {
        if (bitmap.isRecycled) {
            Log.e(TAG, "detectEdges: Bitmap is already recycled")
            return null
        }
        
        Log.d(TAG, "Starting advanced offline edge detection. Mode: $mode")
        var src: Mat? = null
        var resized: Mat? = null
        var gray: Mat? = null
        var claheMat: Mat? = null
        var filtered: Mat? = null
        
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
            
            // 1. Contrast Limited Adaptive Histogram Equalization (CLAHE) for illumination balancing
            claheMat = Mat()
            val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
            clahe.apply(gray, claheMat)
            clahe.release()
            
            // 2. Bilateral Filter for edge-preserving smoothing (removes document content/text noise)
            filtered = Mat()
            Imgproc.bilateralFilter(claheMat, filtered, 9, 75.0, 75.0)
            
            val imageArea = (resized.width() * resized.height()).toDouble()
            val minArea = imageArea * 0.08   // Relaxed area lower bound (8%)
            val maxArea = imageArea * 0.99   // Relaxed area upper bound (99%)
            
            var bestPoints: List<Point>? = null
            var bestScore = -Double.MAX_VALUE

            // We use a hybrid approach trying 4 different preprocessing methods to ensure we capture boundaries
            // under any background contrast or shadow scenario (Adaptive thresholds + Canny thresholds)
            val totalPreprocessingMethods = 4

            for (methodIdx in 0 until totalPreprocessingMethods) {
                var thresh: Mat? = null
                var hierarchy: Mat? = null
                val contours = ArrayList<MatOfPoint>()
                
                try {
                    thresh = preprocess(filtered, methodIdx)
                    hierarchy = Mat()
                    Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    
                    Log.d(TAG, "Method $methodIdx: Found ${contours.size} contours")

                    for (contour in contours) {
                        val area = Imgproc.contourArea(contour)
                        if (area < minArea || area > maxArea) continue
                        
                        // 1. Try finding document contour with our 4-to-6 point quadrilateral extractor
                        val quadCandidate = findDocumentContour(contour, imageArea)
                        if (quadCandidate != null) {
                            val score = scoreQuadrilateral(quadCandidate, imageArea)
                            if (score > bestScore) {
                                bestScore = score
                                val originalPoints = quadCandidate.map { Point(it.x / scaleFactor, it.y / scaleFactor) }
                                bestPoints = orderPoints(originalPoints)
                                Log.d(TAG, "New best quad from contour with score: ${String.format("%.3f", score)} (Area Ratio: ${String.format("%.3f", area / imageArea)})")
                            }
                        }

                        // 2. Fallback extreme points check with our relaxed validation
                        val extremePointsResized = getExtremePoints(contour).map { Point(it.x, it.y) }
                        if (isValidQuadrilateral(extremePointsResized, imageArea)) {
                            val score = scoreQuadrilateral(extremePointsResized, imageArea)
                            if (score > bestScore) {
                                bestScore = score
                                val originalPoints = extremePointsResized.map { Point(it.x / scaleFactor, it.y / scaleFactor) }
                                bestPoints = orderPoints(originalPoints)
                                Log.d(TAG, "New best quad from extreme points with score: ${String.format("%.3f", score)}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing contours with method $methodIdx", e)
                } finally {
                    thresh?.release()
                    hierarchy?.release()
                    contours.forEach { it.release() }
                }
            }

            // Automatic identification logic for document type
            if (bestPoints != null && bestPoints.size == 4) {
                val tl = bestPoints[0]
                val tr = bestPoints[1]
                val br = bestPoints[2]
                val bl = bestPoints[3]

                val widthA = Math.hypot(br.x - bl.x, br.y - bl.y)
                val widthB = Math.hypot(tr.x - tl.x, tr.y - tl.y)
                val avgWidth = (widthA + widthB) / 2.0

                val heightA = Math.hypot(tr.x - br.x, tr.y - br.y)
                val heightB = Math.hypot(tl.x - bl.x, tl.y - bl.y)
                val avgHeight = (heightA + heightB) / 2.0

                val aspectRatio = if (avgHeight > 0) {
                    Math.max(avgWidth / avgHeight, avgHeight / avgWidth)
                } else {
                    1.0
                }

                val identifiedType = if (aspectRatio in 1.45..1.75) {
                    "CARD"
                } else {
                    "DOCUMENT"
                }
                
                com.safescan.core.DiagnosticsLogger.log("🔍 [Offline Auto-Detect] Identified type: $identifiedType (Aspect Ratio: ${String.format("%.3f", aspectRatio)})")
            }

            return bestPoints
        } catch (e: Exception) {
            Log.e(TAG, "Error in detectEdges JNI pipeline", e)
            return null
        } finally {
            src?.release()
            resized?.release()
            gray?.release()
            claheMat?.release()
            filtered?.release()
        }
    }

    /**
     * Noise reduction and thresholding pipeline that combines adaptive thresholding and canny.
     */
    private fun preprocess(gray: Mat, methodIndex: Int): Mat {
        val thresh = Mat()
        when (methodIndex) {
            0 -> {
                // Adaptive Threshold (Gaussian) - handles shadows, glares, and bad lighting perfectly
                Imgproc.adaptiveThreshold(
                    gray,
                    thresh,
                    255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY_INV,
                    11,
                    2.0
                )
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
                Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_CLOSE, kernel)
                kernel.release()
            }
            1 -> {
                // Adaptive Threshold (Mean) - broader window size to capture softer boundaries
                Imgproc.adaptiveThreshold(
                    gray,
                    thresh,
                    255.0,
                    Imgproc.ADAPTIVE_THRESH_MEAN_C,
                    Imgproc.THRESH_BINARY_INV,
                    15,
                    3.0
                )
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
                Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_CLOSE, kernel)
                kernel.release()
            }
            2 -> {
                // Robust Canny for high contrast clean bounds
                Imgproc.Canny(gray, thresh, 40.0, 100.0)
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
                Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_CLOSE, kernel)
                kernel.release()
            }
            else -> {
                // Strong contrast Canny to reject fine textures
                Imgproc.Canny(gray, thresh, 80.0, 200.0)
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
                Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_CLOSE, kernel)
                kernel.release()
            }
        }
        return thresh
    }

    /**
     * Approximates contours into polygons and returns the largest valid quadrilateral
     * if the contour yields between 4 and 6 vertices.
     */
    private fun findDocumentContour(contour: MatOfPoint, imageArea: Double): List<Point>? {
        var contour2f: MatOfPoint2f? = null
        try {
            contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            
            var bestQuad: List<Point>? = null
            var bestScore = -Double.MAX_VALUE

            // Try different approximation tolerances to adapt to folds or curves
            for (epsFactor in listOf(0.015, 0.02, 0.03, 0.04)) {
                val approx = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(contour2f, approx, epsFactor * peri, true)
                    val totalPoints = approx.total()
                    
                    if (totalPoints in 4L..6L) {
                        val approxPoints = approx.toArray().map { Point(it.x, it.y) }
                        val quadCandidate = findLargestQuadrilateral(approxPoints)
                        
                        if (quadCandidate != null && isValidQuadrilateral(quadCandidate, imageArea)) {
                            val score = scoreQuadrilateral(quadCandidate, imageArea)
                            if (score > bestScore) {
                                bestScore = score
                                bestQuad = quadCandidate
                            }
                        }
                    }
                } finally {
                    approx.release()
                }
            }
            return bestQuad
        } finally {
            contour2f?.release()
        }
    }

    /**
     * Extracts the largest convex 4-point quadrilateral from a list of 4 to 6 vertices.
     */
    private fun findLargestQuadrilateral(points: List<Point>): List<Point>? {
        if (points.size < 4) return null
        if (points.size == 4) {
            val ordered = orderPoints(points)
            if (isConvexPoints(ordered)) {
                return ordered
            }
            return null
        }

        var bestQuad: List<Point>? = null
        var maxArea = -1.0
        val n = points.size

        // Generate combinations of choosing 4 vertices from 5 or 6 vertices (at most 15 combinations)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                for (k in j + 1 until n) {
                    for (l in k + 1 until n) {
                        val candidate = listOf(points[i], points[j], points[k], points[l])
                        val ordered = orderPoints(candidate)
                        if (isConvexPoints(ordered)) {
                            val area = getQuadArea(ordered)
                            if (area > maxArea) {
                                maxArea = area
                                bestQuad = ordered
                            }
                        }
                    }
                }
            }
        }
        return bestQuad
    }

    /**
     * Validates candidate quadrilateral using relaxed Area and Aspect Ratio checks
     */
    private fun isValidQuadrilateral(points: List<Point>, imageArea: Double): Boolean {
        if (points.size != 4) return false
        val area = getQuadArea(points)
        val normArea = area / imageArea
        
        // Relaxed Area constraints: must cover at least 8% of the viewport and at most 99%
        if (normArea < 0.08 || normArea > 0.99) {
            return false
        }

        // Relaxed Aspect Ratio constraints: aspect ratio should be reasonable for a standard sheet or card
        val aspect = getQuadAspectRatio(points)
        if (aspect < 0.35 || aspect > 2.5) {
            return false
        }

        return true
    }

    /**
     * Scores a valid quadrilateral. Balances size with orthogonal angles and shape aspect.
     */
    private fun scoreQuadrilateral(points: List<Point>, imageArea: Double): Double {
        val area = getQuadArea(points)
        val normArea = area / imageArea
        
        // Soft Aspect ratio penalty for extremely skewed frames
        val aspect = getQuadAspectRatio(points)
        val aspectPenalty = if (aspect > 2.2) (aspect - 2.2) * 0.3 else 0.0
        
        // Soft cosine penalty (closer to 90-degree corners is better, but not strictly required)
        val maxCosine = getMaxCosinePoints(points) 
        
        // Returns dynamic score based on size, geometry stability, and rectangular nature
        return (normArea * 1.0) - (maxCosine * 0.3) - aspectPenalty
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
        if (pts.size != 4) return pts

        val cx = pts.map { it.x }.average()
        val cy = pts.map { it.y }.average()

        val sorted = pts.sortedBy { Math.atan2(it.y - cy, it.x - cx) }

        var minIdx = 0
        var minSum = Double.MAX_VALUE
        for (i in 0 until 4) {
            val sum = sorted[i].x + sorted[i].y
            if (sum < minSum) {
                minSum = sum
                minIdx = i
            }
        }

        return listOf(
            sorted[minIdx],
            sorted[(minIdx + 1) % 4],
            sorted[(minIdx + 2) % 4],
            sorted[(minIdx + 3) % 4]
        )
    }

    private fun getQuadAspectRatio(points: List<Point>): Double {
        if (points.size != 4) return 1.0
        val tl = points[0]
        val tr = points[1]
        val br = points[2]
        val bl = points[3]

        val widthA = Math.hypot(br.x - bl.x, br.y - bl.y)
        val widthB = Math.hypot(tr.x - tl.x, tr.y - tl.y)
        val avgWidth = (widthA + widthB) / 2.0

        val heightA = Math.hypot(tr.x - br.x, tr.y - br.y)
        val heightB = Math.hypot(tl.x - bl.x, tl.y - bl.y)
        val avgHeight = (heightA + heightB) / 2.0

        return if (avgHeight > 0) Math.max(avgWidth / avgHeight, avgHeight / avgWidth) else 1.0
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
