package com.safescan.scanner

import android.graphics.Bitmap
import com.safescan.core.AppResult
import com.safescan.domain.model.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

enum class ScannerEngineType {
    LOCAL_ML,
    OPENCV
}

interface MLScannerEngine {
    suspend fun detectCorners(bitmap: Bitmap): List<Point>?
}

open class DocumentScannerEngine(private val mlEngine: MLScannerEngine? = null) {
    var engineType: ScannerEngineType = ScannerEngineType.OPENCV

    open suspend fun scanDocument(bitmap: Bitmap): AppResult<Bitmap> = withContext(Dispatchers.Default) {
        try {
            var corners: List<Point>? = null

            if (engineType == ScannerEngineType.LOCAL_ML && mlEngine != null) {
                corners = mlEngine.detectCorners(bitmap)
            } else if (engineType == ScannerEngineType.OPENCV) {
                corners = detectCornersOpenCV(bitmap)
            }

            if (corners == null || corners.size != 4) {
                // Fallback to default full-screen-ish quad if no ML detection is available
                corners = getFallbackQuad(bitmap.width.toDouble(), bitmap.height.toDouble())
            }

            if (corners.size == 4) {
                val orderedCorners = orderPoints(corners)
                val tl = orderedCorners[0]
                val tr = orderedCorners[1]
                val br = orderedCorners[2]
                val bl = orderedCorners[3]

                val widthA = sqrt((br.x - bl.x).pow(2) + (br.y - bl.y).pow(2))
                val widthB = sqrt((tr.x - tl.x).pow(2) + (tr.y - tl.y).pow(2))
                val maxWidth = max(widthA, widthB).toInt().coerceAtLeast(1)

                val heightA = sqrt((tr.x - br.x).pow(2) + (tr.y - br.y).pow(2))
                val heightB = sqrt((tl.x - bl.x).pow(2) + (tl.y - bl.y).pow(2))
                val maxHeight = max(heightA, heightB).toInt().coerceAtLeast(1)

                val srcMat = Mat()
                Utils.bitmapToMat(bitmap, srcMat)
                
                val srcMatOfPoint2f = MatOfPoint2f(
                    org.opencv.core.Point(tl.x, tl.y),
                    org.opencv.core.Point(tr.x, tr.y),
                    org.opencv.core.Point(br.x, br.y),
                    org.opencv.core.Point(bl.x, bl.y)
                )

                val dstMatOfPoint2f = MatOfPoint2f(
                    org.opencv.core.Point(0.0, 0.0),
                    org.opencv.core.Point(maxWidth.toDouble() - 1, 0.0),
                    org.opencv.core.Point(maxWidth.toDouble() - 1, maxHeight.toDouble() - 1),
                    org.opencv.core.Point(0.0, maxHeight.toDouble() - 1)
                )

                val transformMatrix = Imgproc.getPerspectiveTransform(srcMatOfPoint2f, dstMatOfPoint2f)

                val dstMat = Mat()
                Imgproc.warpPerspective(srcMat, dstMat, transformMatrix, Size(maxWidth.toDouble(), maxHeight.toDouble()))

                val outBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(dstMat, outBitmap)
                
                srcMat.release()
                dstMat.release()
                srcMatOfPoint2f.release()
                dstMatOfPoint2f.release()
                transformMatrix.release()

                return@withContext AppResult.Success(outBitmap)
            }
            return@withContext AppResult.Success(bitmap)
        } catch (e: Exception) {
            return@withContext AppResult.Error(e.message ?: "Document scanning failed", e)
        }
    }
    
    private fun detectCornersOpenCV(bitmap: Bitmap): List<Point>? {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        
        // Fast downscale for edge detection
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
        
        val edges = Mat()
        Imgproc.Canny(gray, edges, 75.0, 200.0)
        
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
                    if (isConvex(approx) && getMaxCosine(approx) < 0.3) {
                        val points = approx.toArray().toList()
                        foundCorners = orderPoints(points.map { Point(it.x / resizeRatio, it.y / resizeRatio) })
                        approx.release()
                        contour2f.release()
                        break
                    }
                }
            }
            approx.release()
            contour2f.release()
        }
        
        for (contour in contours) {
            contour.release()
        }
        src.release()
        resized.release()
        gray.release()
        edges.release()
        hierarchy.release()
        kernel.release()
        
        return foundCorners
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

    private fun getFallbackQuad(w: Double, h: Double): List<Point> {
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
