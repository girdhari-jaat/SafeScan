package com.safescan.scanner

import android.graphics.Bitmap
import android.util.Log
import com.safescan.domain.model.Point
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.RotatedRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

data class QuadCandidate(
    val points: List<Point>,
    var bestScore: Double,
    var votes: Int = 1
)

class EdgeDetectionEngine {

    companion object {
        private const val TAG = "EdgeDetectionEngine"
        private const val MIN_AREA_RATIO = 0.015
        private const val MAX_AREA_RATIO = 0.995
        private const val MIN_ASPECT_RATIO = 0.4
        private const val MAX_ASPECT_RATIO = 3.0
        private const val MAX_RESIZE_DIM = 1200.0
        private const val MAX_CONTOURS_TO_PROCESS = 30
        private const val MIN_CONFIDENCE_SCORE = 0.12
        private const val CENTER_BIAS_WEIGHT = 0.08
    }

    @Synchronized
    fun detectEdgesSafe(bitmap: Bitmap, mode: com.safescan.data.ScannerMode? = null): List<Point> {
        return detectEdges(bitmap, mode) ?: getFallbackQuad(bitmap.width.toDouble(), bitmap.height.toDouble())
    }

    @Synchronized
    fun detectEdges(bitmap: Bitmap, mode: com.safescan.data.ScannerMode? = null): List<Point>? {
        if (bitmap.isRecycled) return null

        Log.d(TAG, "Starting Offline v4.4 edge detection")
        var src: Mat? = null
        var resized: Mat? = null
        var gray: Mat? = null
        var claheMat: Mat? = null
        var blurred: Mat? = null
        var shadowFree: Mat? = null

        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)

            val resizeRatio = MAX_RESIZE_DIM / Math.max(src.width(), src.height())
            resized = Mat()
            val scaleFactor = if (resizeRatio < 1.0) {
                Imgproc.resize(src, resized, Size(src.width() * resizeRatio, src.height() * resizeRatio), 0.0, 0.0, Imgproc.INTER_CUBIC)
                resizeRatio
            } else {
                src.copyTo(resized)
                1.0
            }

            gray = Mat()
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)

            val blurScore = getLaplacianVariance(gray)

            shadowFree = Mat()
            val clahe1 = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
            clahe1.apply(gray, shadowFree)
            val clahe2 = Imgproc.createCLAHE(2.0, Size(16.0, 16.0))
            clahe2.apply(shadowFree, shadowFree)
            clahe1.release()
            clahe2.release()

            claheMat = Mat()
            val clahe3 = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
            clahe3.apply(shadowFree, claheMat)
            clahe3.release()

            blurred = Mat()
            Imgproc.GaussianBlur(claheMat, blurred, Size(5.0, 5.0), 0.0)

            val imageArea = (resized.width() * resized.height()).toDouble()
            val minArea = imageArea * MIN_AREA_RATIO
            val maxArea = imageArea * MAX_AREA_RATIO
            val imageCenter = Point(resized.width() / 2.0, resized.height() / 2.0)

            val voteMap = HashMap<String, QuadCandidate>()

            for (methodIdx in 0 until 5) {
                var thresh: Mat? = null
                var hierarchy: Mat? = null
                val contours = ArrayList<MatOfPoint>()

                try {
                    thresh = preprocess(blurred, methodIdx, imageArea)
                    if (methodIdx >= 3) {
                        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                        Imgproc.dilate(thresh, thresh, k)
                        Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_CLOSE, k)
                        k.release()
                    }

                    hierarchy = Mat()
                    Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    contours.sortByDescending { Imgproc.contourArea(it) }
                    val contoursToProcess = if (contours.size > MAX_CONTOURS_TO_PROCESS) contours.subList(0, MAX_CONTOURS_TO_PROCESS) else contours

                    for (contour in contoursToProcess) {
                        val area = Imgproc.contourArea(contour)
                        if (area < minArea || area > maxArea) continue

                        var quadCandidate: List<Point>? = getApproxPolyDP(contour, imageArea)
                        if (quadCandidate == null) quadCandidate = getConvexHullQuad(contour, imageArea)
                        if (quadCandidate == null) quadCandidate = getMinAreaRectPoints(contour)
                        if (quadCandidate == null) quadCandidate = getExtremePoints(contour).map { Point(it.x, it.y) }

                        // ✅ FIXED VOTING LOGIC
                        if (quadCandidate != null && isValidQuadrilateral(quadCandidate, imageArea)) {
                            val score = scoreQuadrilateral(quadCandidate, imageArea, blurScore, imageCenter, resized.width(), resized.height())
                            val hash = getQuadHash(quadCandidate)

                            val existing = voteMap[hash] // Fetch by unique geometric hash key
                            if (existing == null) {
                                voteMap[hash] = QuadCandidate(quadCandidate, score, 1)
                            } else {
                                existing.votes++
                                if (score > existing.bestScore) {
                                    existing.bestScore = score
                                }
                            }
                        }
                    }
                } finally {
                    thresh?.release()
                    hierarchy?.release()
                    contours.forEach { it.release() }
                }
            }

            var bestPoints: List<Point>? = null
            var bestFinalScore = -Double.MAX_VALUE
            for ((_, candidate) in voteMap) {
                val voteBoost = 1.0 + (candidate.votes - 1) * 0.25
                val finalScore = candidate.bestScore * voteBoost
                if (finalScore > bestFinalScore) {
                    bestFinalScore = finalScore
                    bestPoints = candidate.points
                }
            }

            if (bestPoints == null || bestFinalScore < MIN_CONFIDENCE_SCORE) return null

            val originalPoints = bestPoints.map { Point(it.x / scaleFactor, it.y / scaleFactor) }
            return orderPoints(originalPoints)

        } finally {
            src?.release(); resized?.release(); gray?.release()
            claheMat?.release(); blurred?.release(); shadowFree?.release()
        }
    }

    private fun preprocess(gray: Mat, methodIndex: Int, imageArea: Double): Mat {
        val thresh = Mat()
        when (methodIndex) {
            0 -> { Imgproc.adaptiveThreshold(gray, thresh, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 11, 2.0); closeMorph(thresh) }
            1 -> { Imgproc.adaptiveThreshold(gray, thresh, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 15, 3.0); closeMorph(thresh) }
            2 -> {
                val t1 = Mat(); val t2 = Mat()
                Imgproc.threshold(gray, t1, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                Imgproc.threshold(gray, t2, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
                if (getLargestValidContourArea(t1, imageArea) > getLargestValidContourArea(t2, imageArea)) t1.copyTo(thresh) else t2.copyTo(thresh)
                t1.release(); t2.release(); closeMorph(thresh)
            }
            3 -> Imgproc.Canny(gray, thresh, 30.0, 90.0)
            else -> Imgproc.Canny(gray, thresh, 70.0, 180.0)
        }
        return thresh
    }

    private fun getLargestValidContourArea(thresh: Mat, imageArea: Double): Double {
        val contours = ArrayList<MatOfPoint>(); val hierarchy = Mat()
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        var maxArea = 0.0; val minArea = imageArea * MIN_AREA_RATIO
        for (c in contours) { val area = Imgproc.contourArea(c); if (area > minArea && area < imageArea * MAX_AREA_RATIO) maxArea = Math.max(maxArea, area) }
        hierarchy.release(); contours.forEach { it.release() }; return maxArea
    }

    private fun closeMorph(mat: Mat) { val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)); Imgproc.morphologyEx(mat, mat, Imgproc.MORPH_CLOSE, k); k.release() }
    
    private fun getApproxPolyDP(contour: MatOfPoint, imageArea: Double): List<Point>? { 
        var c2f: MatOfPoint2f? = null
        try { 
            c2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            for (eps in listOf(0.01, 0.015, 0.02, 0.03, 0.04)) { 
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, eps * peri, true)
                if (approx.total() in 4L..6L) { 
                    val pts = approx.toArray().map { Point(it.x, it.y) }
                    val quad = if (approx.total() == 4L) pts else findLargestQuadrilateral(pts)
                    if (quad != null && isValidQuadrilateral(quad, imageArea)) { 
                        approx.release()
                        return quad 
                    } 
                }
                approx.release()
            }
            return null 
        } finally { 
            c2f?.release() 
        } 
    }
    
    private fun getConvexHullQuad(contour: MatOfPoint, imageArea: Double): List<Point>? { 
        var m: MatOfPoint? = null
        var h: MatOfInt? = null
        try { 
            val pts = contour.toArray().map { org.opencv.core.Point(Math.round(it.x).toDouble(), Math.round(it.y).toDouble()) }.toTypedArray()
            if (pts.size < 4) return null
            m = MatOfPoint(*pts)
            h = MatOfInt()
            Imgproc.convexHull(m, h, false)
            val idx = h.toArray()
            if (idx.size < 4) return null
            val hull = idx.map { Point(pts[it].x, pts[it].y) }
            return if (hull.size == 4) hull else findLargestQuadrilateral(hull) 
        } finally { 
            m?.release()
            h?.release() 
        } 
    }
    
    private fun getMinAreaRectPoints(contour: MatOfPoint): List<Point>? { 
        var c2f: MatOfPoint2f? = null
        try { 
            c2f = MatOfPoint2f(*contour.toArray())
            val rr = Imgproc.minAreaRect(c2f)
            val v = arrayOfNulls<org.opencv.core.Point>(4)
            rr.points(v)
            return v.map { Point(it!!.x, it.y) }
        } catch (e: Exception) {
            return null
        } finally { 
            c2f?.release() 
        } 
    }
    
    private fun scoreQuadrilateral(points: List<Point>, imageArea: Double, blurScore: Double, imageCenter: Point, imgW: Double, imgH: Double): Double { val normArea = getQuadArea(points) / imageArea; val aspect = getQuadAspectRatio(points); val aspectPenalty = if (aspect > 2.5) (aspect - 2.5) * 0.7 else 0.0; val angleScore = (1.0 - getMaxCosinePoints(points)) * 0.25; val qc = Point(points.map { it.x }.average(), points.map { it.y }.average()); val dist = hypot(qc.x - imageCenter.x, qc.y - imageCenter.y); val maxDist = hypot(imgW / 2.0, imgH / 2.0); val centerScore = (1.0 - dist / maxDist) * CENTER_BIAS_WEIGHT; val blurFactor = Math.min(1.0, blurScore / 300.0); val areaWeight = 0.4 + (1.0 - blurFactor) * 0.1; return (normArea * areaWeight) + angleScore - aspectPenalty + centerScore }
    private fun getLaplacianVariance(gray: Mat): Double { var lap = Mat(); try { Imgproc.Laplacian(gray, lap, org.opencv.core.CvType.CV_64F); val m = Mat(); val s = Mat(); Core.meanStdDev(lap, m, s); val v = Math.pow(s.get(0,0)[0], 2.0); m.release(); s.release(); return v } finally { lap.release() } }
    private fun getQuadHash(points: List<Point>): String { return points.map { Point(Math.round(it.x / 8.0) * 8.0, Math.round(it.y / 8.0) * 8.0) }.sortedBy { it.x + it.y }.joinToString("|") { "${it.x},${it.y}" } }
    
    private fun findLargestQuadrilateral(points: List<Point>): List<Point>? { 
        if (points.size < 4) return null
        if (points.size == 4) return orderPoints(points)
        var best: List<Point>? = null
        var maxA = -1.0
        val n = points.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                for (k in j + 1 until n) {
                    for (l in k + 1 until n) {
                        val c = orderPoints(listOf(points[i], points[j], points[k], points[l]))
                        if (isConvexPoints(c)) {
                            val a = getQuadArea(c)
                            if (a > maxA) {
                                maxA = a
                                best = c
                            }
                        }
                    }
                }
            }
        }
        return best 
    }
    
    private fun isValidQuadrilateral(points: List<Point>, imageArea: Double): Boolean { if (points.size!= 4) return false; val normArea = getQuadArea(points) / imageArea; if (normArea < MIN_AREA_RATIO || normArea > MAX_AREA_RATIO) return false; val aspect = getQuadAspectRatio(points); if (aspect < MIN_ASPECT_RATIO || aspect > MAX_ASPECT_RATIO) return false; return true }
    private fun getExtremePoints(contour: MatOfPoint): List<Point> { val pts = contour.toArray().map { Point(it.x, it.y) }; val sums = pts.map { it.x + it.y }; val diffs = pts.map { it.y - it.x }; return listOf(pts[sums.indexOf(sums.minOrNull()!!)], pts[diffs.indexOf(diffs.minOrNull()!!)], pts[sums.indexOf(sums.maxOrNull()!!)], pts[diffs.indexOf(diffs.maxOrNull()!!)]) }
    private fun isConvexPoints(points: List<Point>): Boolean { if (points.size!= 4) return false; val m = MatOfPoint(*points.map { org.opencv.core.Point(it.x, it.y) }.toTypedArray()); val c = Imgproc.isContourConvex(m); m.release(); return c }
    private fun getQuadArea(points: List<Point>): Double { val m = MatOfPoint2f(*points.map { org.opencv.core.Point(it.x, it.y) }.toTypedArray()); val a = Imgproc.contourArea(m); m.release(); return a }
    private fun getMaxCosinePoints(points: List<Point>): Double { if (points.size!= 4) return 1.0; var max = 0.0; val pts = points.map { org.opencv.core.Point(it.x, it.y) }; for (i in 0..3) { max = Math.max(max, Math.abs(angle(pts[(i + 1) % 4], pts[(i + 3) % 4], pts[i]))) }; return max }
    private fun angle(p1: org.opencv.core.Point, p2: org.opencv.core.Point, p0: org.opencv.core.Point): Double { val dx1 = p1.x - p0.x; val dy1 = p1.y - p0.y; val dx2 = p2.x - p0.x; val dy2 = p2.y - p0.y; return (dx1 * dx2 + dy1 * dy2) / Math.sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10) }
    private fun orderPoints(pts: List<Point>): List<Point> { if (pts.size!= 4) return pts; val cx = pts.map { it.x }.average(); val cy = pts.map { it.y }.average(); val sorted = pts.sortedBy { Math.atan2(it.y - cy, it.x - cx) }; var minIdx = 0; var minSum = Double.MAX_VALUE; for (i in 0..3) if (sorted[i].x + sorted[i].y < minSum) { minSum = sorted[i].x + sorted[i].y; minIdx = i }; return listOf(sorted[minIdx], sorted[(minIdx + 1) % 4], sorted[(minIdx + 2) % 4], sorted[(minIdx + 3) % 4]) }
    private fun getQuadAspectRatio(points: List<Point>): Double { if (points.size!= 4) return 1.0; val tl=points[0]; val tr=points[1]; val br=points[2]; val bl=points[3]; val w = (hypot(br.x - bl.x, br.y - bl.y) + hypot(tr.x - tl.x, tr.y - tl.y)) / 2.0; val h = (hypot(tr.x - br.x, tr.y - br.y) + hypot(tl.x - bl.x, tl.y - bl.y)) / 2.0; return if (h > 0) Math.max(w / h, h / w) else 1.0 }
    fun getFallbackQuad(w: Double, h: Double): List<Point> { val px = w * 0.05; val py = h * 0.05; return listOf(Point(px, py), Point(w - px, py), Point(w - px, h - py), Point(px, h - py)) }
}
