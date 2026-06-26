package com.safescan.scanner

import android.graphics.Bitmap
import com.safescan.core.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

enum class ScannerEngineType {
    OPENCV,
    LOCAL_ML,
    MLKIT
}

interface MLScannerEngine {
    suspend fun detectCorners(bitmap: Bitmap): List<Point>?
}

open class DocumentScannerEngine(private val mlEngine: MLScannerEngine? = null) {
    var engineType: ScannerEngineType = ScannerEngineType.OPENCV

    suspend fun scanDocument(bitmap: Bitmap): AppResult<Bitmap> = withContext(Dispatchers.Default) {
        val src = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val dst = Mat()

        try {
            Utils.bitmapToMat(bitmap, src)

            var corners: List<Point>? = null

            if (engineType == ScannerEngineType.LOCAL_ML && mlEngine != null) {
                corners = mlEngine.detectCorners(bitmap)
            }

            if (corners == null) {
                // OpenCV Fallback or Primary Engine
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
                Imgproc.Canny(blurred, edges, 75.0, 200.0)

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
                        corners = approx.toList()
                        break
                    }
                }
            }

            if (corners != null && corners.size == 4) {
                val orderedCorners = orderPoints(corners)

                val tl = orderedCorners[0]
                val tr = orderedCorners[1]
                val br = orderedCorners[2]
                val bl = orderedCorners[3]

                val widthA = sqrt((br.x - bl.x).pow(2) + (br.y - bl.y).pow(2))
                val widthB = sqrt((tr.x - tl.x).pow(2) + (tr.y - tl.y).pow(2))
                val maxWidth = max(widthA, widthB).toInt()

                val heightA = sqrt((tr.x - br.x).pow(2) + (tr.y - br.y).pow(2))
                val heightB = sqrt((tl.x - bl.x).pow(2) + (tl.y - bl.y).pow(2))
                val maxHeight = max(heightA, heightB).toInt()

                if (maxWidth > 0 && maxHeight > 0) {
                    val srcPoints = MatOfPoint2f(*orderedCorners.toTypedArray())
                    val dstPoints = MatOfPoint2f(
                        Point(0.0, 0.0),
                        Point(maxWidth.toDouble() - 1, 0.0),
                        Point(maxWidth.toDouble() - 1, maxHeight.toDouble() - 1),
                        Point(0.0, maxHeight.toDouble() - 1)
                    )

                    val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
                    Imgproc.warpPerspective(
                        src,
                        dst,
                        transform,
                        Size(maxWidth.toDouble(), maxHeight.toDouble())
                    )

                    val resultBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(dst, resultBitmap)

                    transform.release()
                    srcPoints.release()
                    dstPoints.release()

                    return@withContext AppResult.Success(resultBitmap)
                }
            }

            return@withContext AppResult.Success(bitmap)
        } catch (e: Exception) {
            return@withContext AppResult.Error(e.message ?: "Document scanning failed", e)
        } finally {
            src.release()
            gray.release()
            blurred.release()
            edges.release()
            hierarchy.release()
            dst.release()
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
}
