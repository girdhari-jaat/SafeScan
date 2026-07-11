package com.safescan.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.safescan.domain.model.Point
import com.safescan.domain.model.Quadrilateral
import com.safescan.scanner.ml.LocalMLEngine
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class DocumentScanner(
    private val localMLEngine: LocalMLEngine,
    private val context: Context
) {
    fun detectDocument(bitmap: Bitmap): Quadrilateral? {
        return localMLEngine.detectCorners(bitmap)
    }

    fun cropAndTransform(bitmap: Bitmap, quad: Quadrilateral, mode: String = "DOCUMENT"): Bitmap {
        val tl = quad.topLeft
        val tr = quad.topRight
        val br = quad.bottomRight
        val bl = quad.bottomLeft

        val widthA = sqrt((br.x - bl.x).pow(2) + (br.y - bl.y).pow(2))
        val widthB = sqrt((tr.x - tl.x).pow(2) + (tr.y - tl.y).pow(2))
        var maxWidth = max(widthA, widthB).toInt().coerceAtLeast(1)

        val heightA = sqrt((tr.x - br.x).pow(2) + (tr.y - br.y).pow(2))
        val heightB = sqrt((tl.x - bl.x).pow(2) + (tl.y - bl.y).pow(2))
        var maxHeight = max(heightA, heightB).toInt().coerceAtLeast(1)

        val isA4 = CameraHardwareConfig.isA4Supported(context)
        val isCnic = CameraHardwareConfig.isCnicSupported(context)

        if (mode == "DOCUMENT" || mode == "CARD") {
            // DOCUMENT/CARD try A4 first, fallback to 4:3
            val targetRatio = if (isA4) 1.4142f else 1.3333f
            if (maxHeight > maxWidth) {
                maxHeight = (maxWidth * targetRatio).toInt()
            } else {
                maxWidth = (maxHeight * targetRatio).toInt()
            }
        } else if (mode == "GRID") {
            // GRID tries CNIC first, fallback to 3:4 portrait
            if (isCnic) {
                val targetRatio = 1.5857f
                if (maxWidth > maxHeight) {
                    maxHeight = (maxWidth / targetRatio).toInt()
                } else {
                    maxWidth = (maxHeight / targetRatio).toInt()
                }
            } else {
                // Fallback to 3:4 portrait
                val targetRatio = 1.3333f // height / width = 4 / 3
                if (maxHeight > maxWidth) {
                    maxHeight = (maxWidth * targetRatio).toInt()
                } else {
                    maxWidth = (maxHeight * targetRatio).toInt()
                }
            }
        }

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
