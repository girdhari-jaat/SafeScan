package com.safescan.scanner

import android.content.Context
import android.graphics.Bitmap
import com.safescan.domain.model.Point
import com.safescan.domain.model.Quadrilateral
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class DocumentScanner(
    private val tfLiteEngine: TFLiteEngine,
    private val context: Context
) {
    fun detectDocument(bitmap: Bitmap, isLive: Boolean = false): Quadrilateral? {
        return tfLiteEngine.detectCorners(bitmap, isLive)
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

        if (mode == "DOCUMENT") {
            // DOCUMENT try A4 first, fallback to 4:3
            val targetRatio = if (isA4) 1.4142f else 1.3333f
            if (maxHeight > maxWidth) {
                maxHeight = (maxWidth * targetRatio).toInt()
            } else {
                maxWidth = (maxHeight * targetRatio).toInt()
            }
        } else if (mode == "CARD" || mode == "GRID") {
            // CARD/GRID use ID-1 Card aspect ratio (1.5857) in landscape
            val targetRatio = 1.5857f
            if (maxWidth > maxHeight) {
                maxHeight = (maxWidth / targetRatio).toInt()
            } else {
                maxWidth = (maxHeight / targetRatio).toInt()
            }
        }

        var src: Mat? = null
        var ptsSrc: MatOfPoint2f? = null
        var ptsDst: MatOfPoint2f? = null
        var perspectiveTransform: Mat? = null
        var outMat: Mat? = null
        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)

            ptsSrc = MatOfPoint2f(
                org.opencv.core.Point(tl.x, tl.y),
                org.opencv.core.Point(tr.x, tr.y),
                org.opencv.core.Point(br.x, br.y),
                org.opencv.core.Point(bl.x, bl.y)
            )

            ptsDst = MatOfPoint2f(
                org.opencv.core.Point(0.0, 0.0),
                org.opencv.core.Point(maxWidth.toDouble() - 1.0, 0.0),
                org.opencv.core.Point(maxWidth.toDouble() - 1.0, maxHeight.toDouble() - 1.0),
                org.opencv.core.Point(0.0, maxHeight.toDouble() - 1.0)
            )

            perspectiveTransform = Imgproc.getPerspectiveTransform(ptsSrc, ptsDst)
            outMat = Mat()
            Imgproc.warpPerspective(src, outMat, perspectiveTransform, Size(maxWidth.toDouble(), maxHeight.toDouble()))

            val resultBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outMat, resultBitmap)
            return resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to basic Matrix warp if OpenCV fails
            val matrix = android.graphics.Matrix()
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
            val canvas = android.graphics.Canvas(resultBitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bitmap, matrix, paint)
            return resultBitmap
        } finally {
            src?.release()
            ptsSrc?.release()
            ptsDst?.release()
            perspectiveTransform?.release()
            outMat?.release()
        }
    }
}
