package com.safescan.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.safescan.core.AppResult
import com.safescan.android.scanner.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

                // High-performance perspective warping using Android native Matrix poly-to-poly
                val matrix = Matrix()
                val srcPoints = floatArrayOf(
                    tl.x.toFloat(), tl.y.toFloat(),
                    tr.x.toFloat(), tr.y.toFloat(),
                    br.x.toFloat(), br.y.toFloat(),
                    bl.x.toFloat(), bl.y.toFloat()
                )
                val dstPoints = floatArrayOf(
                    0f, 0f,
                    maxWidth.toFloat() - 1, 0f,
                    maxWidth.toFloat() - 1, maxHeight.toFloat() - 1,
                    0f, maxHeight.toFloat() - 1
                )

                matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

                val resultBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(resultBitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(bitmap, matrix, paint)

                return@withContext AppResult.Success(resultBitmap)
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
