package com.safescan.android.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.safescan.android.ml.local.LocalMLEngine
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

enum class ScannerEngineType {
    LOCAL_ML,
    MLKIT
}

class DocumentScanner(
    private val localMLEngine: LocalMLEngine = LocalMLEngine()
) {
    var currentEngine: ScannerEngineType = ScannerEngineType.MLKIT

    fun detectDocument(bitmap: Bitmap): Quadrilateral? {
        return when (currentEngine) {
            ScannerEngineType.LOCAL_ML -> localMLEngine.detectCorners(bitmap)
            ScannerEngineType.MLKIT -> {
                // Return fallback quad representing 90% bounding box in pure Kotlin
                val w = bitmap.width.toDouble()
                val h = bitmap.height.toDouble()
                val paddingX = w * 0.05
                val paddingY = h * 0.05
                Quadrilateral(
                    Point(paddingX, paddingY),
                    Point(w - paddingX, paddingY),
                    Point(w - paddingX, h - paddingY),
                    Point(paddingX, h - paddingY)
                )
            }
        }
    }

    fun cropAndTransform(bitmap: Bitmap, quad: Quadrilateral): Bitmap {
        val tl = quad.topLeft
        val tr = quad.topRight
        val br = quad.bottomRight
        val bl = quad.bottomLeft

        val widthA = sqrt((br.x - bl.x).pow(2) + (br.y - bl.y).pow(2))
        val widthB = sqrt((tr.x - tl.x).pow(2) + (tr.y - tl.y).pow(2))
        val maxWidth = max(widthA, widthB).toInt().coerceAtLeast(1)

        val heightA = sqrt((tr.x - br.x).pow(2) + (tr.y - br.y).pow(2))
        val heightB = sqrt((tl.x - bl.x).pow(2) + (tl.y - bl.y).pow(2))
        val maxHeight = max(heightA, heightB).toInt().coerceAtLeast(1)

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

        return resultBitmap
    }
}
