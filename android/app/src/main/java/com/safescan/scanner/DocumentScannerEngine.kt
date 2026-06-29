package com.safescan.scanner

import android.graphics.Bitmap
import com.safescan.core.AppResult
import com.safescan.android.scanner.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

enum class ScannerEngineType {
    LOCAL_ML,
    MLKIT
}

interface MLScannerEngine {
    suspend fun detectCorners(bitmap: Bitmap): List<Point>?
}

open class DocumentScannerEngine(private val mlEngine: MLScannerEngine? = null) {
    var engineType: ScannerEngineType = ScannerEngineType.MLKIT

    open suspend fun scanDocument(bitmap: Bitmap): AppResult<Bitmap> = withContext(Dispatchers.Default) {
        try {
            var corners: List<Point>? = null

            if (engineType == ScannerEngineType.LOCAL_ML && mlEngine != null) {
                corners = mlEngine.detectCorners(bitmap)
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
                
                val srcPoints = org.opencv.core.Point(tl.x, tl.y)
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

                return@withContext AppResult.Success(outBitmap)
            }

            return@withContext AppResult.Success(bitmap)
        } catch (e: Exception) {
            return@withContext AppResult.Error(e.message ?: "Document scanning failed", e)
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

