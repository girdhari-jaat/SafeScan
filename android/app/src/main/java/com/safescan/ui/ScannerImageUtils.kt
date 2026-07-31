package com.safescan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.safescan.core.ScannerDebugLogger
import com.safescan.data.ScannerMode
import com.safescan.domain.model.Point
import com.safescan.utils.PageConfig

object ScannerImageUtils {

    fun mapPointsToPreviewView(
        points: List<Point>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        rotationDegrees: Int,
        viewWidth: Float,
        viewHeight: Float
    ): List<PointF> {
        if (viewWidth == 0f || viewHeight == 0f) return emptyList()

        val rotatedWidth: Float
        val rotatedHeight: Float
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            rotatedWidth = bitmapHeight.toFloat()
            rotatedHeight = bitmapWidth.toFloat()
        } else {
            rotatedWidth = bitmapWidth.toFloat()
            rotatedHeight = bitmapHeight.toFloat()
        }

        val frameRatio = rotatedWidth / rotatedHeight
        val viewRatio = viewWidth / viewHeight

        val scale: Float
        val dx: Float
        val dy: Float

        if (frameRatio > viewRatio) {
            scale = viewWidth / rotatedWidth
            dx = 0f
            dy = (viewHeight - rotatedHeight * scale) / 2f
        } else {
            scale = viewHeight / rotatedHeight
            dx = (viewWidth - rotatedWidth * scale) / 2f
            dy = 0f
        }

        Log.d("LiveEdgeDetection", "mapPointsToPreviewView: dx=$dx dy=$dy scale=$scale viewW=$viewWidth viewH=$viewHeight rot=$rotationDegrees")

        return points.map { pt ->
            val normX = pt.x.toFloat() / bitmapWidth
            val normY = pt.y.toFloat() / bitmapHeight

            val rotatedX: Float
            val rotatedY: Float
            when (rotationDegrees) {
                90 -> {
                    rotatedX = 1f - normY
                    rotatedY = normX
                }
                180 -> {
                    rotatedX = 1f - normX
                    rotatedY = 1f - normY
                }
                270 -> {
                    rotatedX = normY
                    rotatedY = 1f - normX
                }
                else -> {
                    rotatedX = normX
                    rotatedY = normY
                }
            }

            val screenX = (rotatedX * rotatedWidth * scale) + dx
            val screenY = (rotatedY * rotatedHeight * scale) + dy

            PointF(screenX, screenY)
        }
    }

    fun getOverlayHoleRect(context: Context, mode: ScannerMode, pw: Float, ph: Float): RectF {
        val finalRatio = PageConfig.getOnscreenLayoutRatio(context, mode)

        val maxWidth = pw * 0.90f
        val maxHeight = ph * 0.85f

        var rectWidth = maxWidth
        var rectHeight = rectWidth / finalRatio

        if (rectHeight > maxHeight) {
            rectHeight = maxHeight
            rectWidth = rectHeight * finalRatio
        }

        val rectLeft = (pw - rectWidth) / 2f
        val rectTop = (ph - rectHeight) / 2f
        return RectF(rectLeft, rectTop, rectLeft + rectWidth, rectTop + rectHeight)
    }

    fun getExifRotation(context: Context, uri: Uri): Int {
        var rotation = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exifInterface = ExifInterface(inputStream)
                val orientation = exifInterface.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                rotation = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) {
            Log.e("ScannerImageUtils", "Failed to read EXIF rotation for uri: $uri", e)
        }
        return rotation
    }

    fun autoRotateForMode(bitmap: Bitmap, mode: ScannerMode): Bitmap {
        val isLandscape = bitmap.width > bitmap.height
        val needsRotation = when (mode) {
            ScannerMode.DOCUMENT -> isLandscape // DOCUMENT should be portrait
            ScannerMode.CARD -> !isLandscape // CARD should be landscape
        }
        return if (needsRotation) {
            val angle = when (mode) {
                ScannerMode.DOCUMENT -> 90f
                ScannerMode.CARD -> -90f
            }
            ScannerDebugLogger.logAutoRotation(angle)
            val matrix = Matrix().apply { postRotate(angle) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            rotated
        } else {
            ScannerDebugLogger.logAutoRotation(0f)
            bitmap
        }
    }
}
