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
    fun detectEdgesSafe(bitmap: Bitmap, mode: com.safescan.data.ScannerMode? = null): List<Point> {
        return detectEdges(bitmap, mode) ?: getFallbackQuad(bitmap.width.toDouble(), bitmap.height.toDouble())
    }

    @Synchronized
    fun detectEdges(bitmap: Bitmap, mode: com.safescan.data.ScannerMode? = null): List<Point>? {
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
            
            // 1. CLAHE (Contrast Limited Adaptive Histogram Equalization) for contrast equalization under shadows/bad lighting
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(gray, gray)
            clahe.release()
            
            // 2. Bilateral filter for excellent edge-preserving smoothing (clears internal text & textures)
            val filtered = Mat()
            Imgproc.bilateralFilter(gray, filtered, 9, 75.0, 75.0)
            filtered.copyTo(gray)
            filtered.release()
            
            val imageArea = (resized.width() * resized.height()).toDouble()
            val minArea = imageArea * 0.10   // 10%
            val maxArea = imageArea * 0.95   // 95%
            
            var bestPoints: List<Point>? = null
            var bestScore = -Double.MAX_VALUE

            // Define thresholds and morphology kernel size for each mode
            val thresholds = when (mode) {
                com.safescan.data.ScannerMode.CARD, com.safescan.data.ScannerMode.GRID -> listOf(40.0, 60.0, 80.0, 100.0)
                com.safescan.data.ScannerMode.DOCUMENT -> listOf(30.0, 50.0, 70.0, 90.0)
                null -> listOf(35.0, 55.0, 75.0, 95.0)
            }

            val morphSize = when (mode) {
                com.safescan.data.ScannerMode.CARD, com.safescan.data.ScannerMode.GRID -> Size(7.0, 7.0)
                com.safescan.data.ScannerMode.DOCUMENT -> Size(5.0, 5.0)
                null -> Size(5.0, 5.0)
            }

            for (threshold in thresholds) {
                var edges: Mat? = null
                var kernel: Mat? = null
                val contours = ArrayList<MatOfPoint>()
                var hierarchy: Mat? = null
                try {
                    edges = Mat()
                    Imgproc.Canny(gray, edges, threshold, threshold * 2.5)
                    
                    kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, morphSize)
                    Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

                    hierarchy = Mat()
                    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

                    for (contour in contours) {
                        val area = Imgproc.contourArea(contour)
                        if (area < minArea || area > maxArea) continue
                        
                        var contour2f: MatOfPoint2f? = null
                        try {
                            contour2f = MatOfPoint2f(*contour.toArray())
                            val peri = Imgproc.arcLength(contour2f, true)
                            
                            for (epsFactor in listOf(0.015, 0.02, 0.03, 0.04)) {
                                val approx = MatOfPoint2f()
                                try {
                                    Imgproc.approxPolyDP(contour2f, approx, epsFactor * peri, true)
                                    if (approx.total() == 4L) {
                                        val approxPointsResized = approx.toArray().map { Point(it.x, it.y) }
                                        
                                        if (isConvexPoints(approxPointsResized)) {
                                            val maxAngleError = getMaxCosinePoints(approxPointsResized)
                                            if (maxAngleError < 0.5) {
                                                val normArea = area / imageArea
                                                val quadArea = getQuadArea(approxPointsResized)
                                                val areaRatio = if (quadArea > 0) Math.min(area, quadArea) / Math.max(area, quadArea) else 0.0
                                                val aspect = getQuadAspectRatio(approxPointsResized)
                                                val aspectPenalty = if (aspect > 2.2) (aspect - 2.2) * 0.3 else 0.0
                                                
                                                val score = normArea * 0.6 + areaRatio * 0.4 - maxAngleError - aspectPenalty
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

                            // FALLBACK WITHIN CONTOUR
                            val extremePointsResized = getExtremePoints(contour).map { Point(it.x, it.y) }
                            if (extremePointsResized.size == 4 && isConvexPoints(extremePointsResized)) {
                                val maxAngleError = getMaxCosinePoints(extremePointsResized)
                                if (maxAngleError < 0.5) {
                                    val quadArea = getQuadArea(extremePointsResized)
                                    if (quadArea in minArea..maxArea) {
                                        val normArea = quadArea / imageArea
                                        val areaRatio = if (quadArea > 0) Math.min(area, quadArea) / Math.max(area, quadArea) else 0.0
                                        val aspect = getQuadAspectRatio(extremePointsResized)
                                        val aspectPenalty = if (aspect > 2.2) (aspect - 2.2) * 0.3 else 0.0
                                        
                                        val score = normArea * 0.6 + areaRatio * 0.4 - maxAngleError - aspectPenalty
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

            // Automatic identification logic for offline edge detection
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

                // If aspect ratio is close to standard Card (1.586, e.g., between 1.45 and 1.75)
                val identifiedType = if (aspectRatio in 1.45..1.75) {
                    "CARD"
                } else {
                    "DOCUMENT"
                }
                
                com.safescan.core.DiagnosticsLogger.log("🔍 [Offline Auto-Detect] Identified type: $identifiedType (Aspect Ratio: ${String.format("%.3f", aspectRatio)})")
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
