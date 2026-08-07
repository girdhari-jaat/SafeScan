package com.safescan.scanner

import android.graphics.Bitmap
import android.util.Log
import com.safescan.core.AppResult
import com.safescan.domain.model.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.CvType
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

data class ScannedDocument(
    val bitmap: Bitmap,
    val corners: List<Point>
)

open class DocumentScannerEngine(private val mlEngine: MLScannerEngine? = null) {
    var engineType: ScannerEngineType = ScannerEngineType.OPENCV

    open suspend fun scanDocument(bitmap: Bitmap, flatCrop: Boolean = false): AppResult<ScannedDocument> = withContext(Dispatchers.Default) {
        try {
            var corners: List<Point>? = null

            // 1. Try Local ML (TFLite) first if enabled/available
            if (engineType == ScannerEngineType.LOCAL_ML && mlEngine != null) {
                try {
                    corners = mlEngine.detectCorners(bitmap)
                } catch (e: Throwable) {
                    Log.e("DocumentScannerEngine", "Local ML detection failed, will fallback to OpenCV", e)
                }
            }

            // 2. If ML detection failed or was not used, fall back to OpenCV corner detection
            if (corners == null || corners.size != 4) {
                try {
                    corners = detectCornersOpenCV(bitmap)
                } catch (e: Throwable) {
                    Log.e("DocumentScannerEngine", "OpenCV detection fallback failed", e)
                }
            }

            // 3. If both ML and OpenCV failed to find a valid 4-corner quad, return a CORNERS_NOT_FOUND error
            if (corners == null || corners.size != 4) {
                return@withContext AppResult.Error("CORNERS_NOT_FOUND")
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

                var srcMat: Mat? = null
                var dstMat: Mat? = null
                var srcMatOfPoint2f: MatOfPoint2f? = null
                var dstMatOfPoint2f: MatOfPoint2f? = null
                var transformMatrix: Mat? = null
                try {
                    srcMat = Mat()
                    Utils.bitmapToMat(bitmap, srcMat)
                    
                    srcMatOfPoint2f = MatOfPoint2f(
                        org.opencv.core.Point(tl.x, tl.y),
                        org.opencv.core.Point(tr.x, tr.y),
                        org.opencv.core.Point(br.x, br.y),
                        org.opencv.core.Point(bl.x, bl.y)
                    )

                    dstMatOfPoint2f = MatOfPoint2f(
                        org.opencv.core.Point(0.0, 0.0),
                        org.opencv.core.Point(maxWidth.toDouble() - 1.0, 0.0),
                        org.opencv.core.Point(maxWidth.toDouble() - 1.0, maxHeight.toDouble() - 1.0),
                        org.opencv.core.Point(0.0, maxHeight.toDouble() - 1.0)
                    )

                    Log.d("DocumentScannerEngine", "getPerspectiveTransform: srcMatOfPoint2f type = ${srcMatOfPoint2f.type()}, dstMatOfPoint2f type = ${dstMatOfPoint2f.type()}")
                    transformMatrix = Imgproc.getPerspectiveTransform(srcMatOfPoint2f, dstMatOfPoint2f)

                    dstMat = Mat()
                    Log.d("DocumentScannerEngine", "warpPerspective: srcMat type = ${srcMat.type()}, transformMatrix type = ${transformMatrix.type()}")
                    Imgproc.warpPerspective(srcMat, dstMat, transformMatrix, Size(maxWidth.toDouble(), maxHeight.toDouble()), Imgproc.INTER_CUBIC)

                    val outBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
                    val finalDstMat = if (dstMat.type() == CvType.CV_8UC1) {
                        val temp = Mat()
                        Imgproc.cvtColor(dstMat, temp, Imgproc.COLOR_GRAY2RGBA)
                        temp
                    } else {
                        dstMat
                    }
                    Utils.matToBitmap(finalDstMat, outBitmap)
                    if (finalDstMat != dstMat) {
                        finalDstMat.release()
                    }
                    
                    return@withContext AppResult.Success(ScannedDocument(outBitmap, orderedCorners))
                } finally {
                    srcMat?.release()
                    dstMat?.release()
                    srcMatOfPoint2f?.release()
                    dstMatOfPoint2f?.release()
                    transformMatrix?.release()
                }
            }
            // If it's exactly 4 points but fallback logic above was weird (should not happen), 
            // return original with fallback quad or similar.
            return@withContext AppResult.Success(ScannedDocument(bitmap, getFallbackQuad(bitmap.width.toDouble(), bitmap.height.toDouble())))
        } catch (e: Exception) {
            return@withContext AppResult.Error(e.message ?: "Document scanning failed", e)
        }
    }
    
    private fun detectCornersOpenCV(bitmap: Bitmap): List<Point>? {
        var src: Mat? = null
        var resized: Mat? = null
        var gray: Mat? = null
        var edges: Mat? = null
        var kernel: Mat? = null
        val contours = ArrayList<MatOfPoint>()
        var hierarchy: Mat? = null
        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)
            
            // Fast downscale for edge detection
            val resizeRatio = 500.0 / Math.max(src.width(), src.height())
            resized = Mat()
            if (resizeRatio < 1.0) {
                Imgproc.resize(src, resized, Size(src.width() * resizeRatio, src.height() * resizeRatio))
            } else {
                src.copyTo(resized)
            }
            
            gray = Mat()
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            
            edges = Mat()
            Imgproc.Canny(gray, edges, 75.0, 200.0)
            
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edges, edges, kernel)
            
            Log.d("DocumentScannerEngine", "findContours: edges type = ${edges.type()}")
            hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            
            contours.sortByDescending { Imgproc.contourArea(it) }
            var foundCorners: List<Point>? = null
            
            for (contour in contours) {
                var contour2f: MatOfPoint2f? = null
                var approx: MatOfPoint2f? = null
                try {
                    contour2f = MatOfPoint2f()
                    contour.convertTo(contour2f, CvType.CV_32F)
                    approx = MatOfPoint2f()
                    val peri = Imgproc.arcLength(contour2f, true)
                    Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)
                    
                    if (approx.total() == 4L) {
                        val area = Imgproc.contourArea(approx)
                        if (area > (resized.width() * resized.height() * 0.1)) {
                            if (isConvex(approx) && getMaxCosine(approx) < 0.3) {
                                val floatBuff = FloatArray(8)
                                approx.get(0, 0, floatBuff)
                                val ptsList = listOf(
                                    Point(floatBuff[0].toDouble() / resizeRatio, floatBuff[1].toDouble() / resizeRatio),
                                    Point(floatBuff[2].toDouble() / resizeRatio, floatBuff[3].toDouble() / resizeRatio),
                                    Point(floatBuff[4].toDouble() / resizeRatio, floatBuff[5].toDouble() / resizeRatio),
                                    Point(floatBuff[6].toDouble() / resizeRatio, floatBuff[7].toDouble() / resizeRatio)
                                )
                                foundCorners = orderPoints(ptsList)
                                break
                            }
                        }
                    }
                } finally {
                    approx?.release()
                    contour2f?.release()
                }
            }
            
            return foundCorners
        } finally {
            contours.forEach { it.release() }
            src?.release()
            resized?.release()
            gray?.release()
            edges?.release()
            hierarchy?.release()
            kernel?.release()
        }
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

    private fun getMaxCosine(approx: MatOfPoint2f): Double {
        var maxCosine = 0.0
        val floatBuff = FloatArray(8)
        approx.get(0, 0, floatBuff)
        val p0 = org.opencv.core.Point(floatBuff[0].toDouble(), floatBuff[1].toDouble())
        val p1 = org.opencv.core.Point(floatBuff[2].toDouble(), floatBuff[3].toDouble())
        val p2 = org.opencv.core.Point(floatBuff[4].toDouble(), floatBuff[5].toDouble())
        val p3 = org.opencv.core.Point(floatBuff[6].toDouble(), floatBuff[7].toDouble())
        val pts = arrayOf(p0, p1, p2, p3)
        for (i in 2..4) {
            val cosine = Math.abs(angle(pts[i % 4], pts[i - 2], pts[i - 1]))
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
        val mat = MatOfPoint()
        try {
            approx.convertTo(mat, CvType.CV_32S)
            return Imgproc.isContourConvex(mat)
        } finally {
            mat.release()
        }
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
