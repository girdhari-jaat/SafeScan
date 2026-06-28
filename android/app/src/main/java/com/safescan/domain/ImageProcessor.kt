package com.safescan.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.safescan.data.EditorState
import com.safescan.data.FilterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageProcessor {

    suspend fun apply(bitmap: Bitmap, state: EditorState): Bitmap = withContext(Dispatchers.Default) {
        try {
            // Apply brightness and contrast using ColorMatrix
            val scale = state.contrast
            val translate = state.brightness
            val colorMatrix = ColorMatrix(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))

            val workingBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(workingBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix)
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)

            // Sharpness (3x3 Laplacian Convolution Kernel)
            val sharpened = if (state.sharpness > 0f) {
                val temp = sharpenBitmap(workingBitmap, state.sharpness)
                workingBitmap.recycle()
                temp
            } else {
                workingBitmap
            }

            // Grayscale / Binarization Filters
            val filtered = when (state.filter) {
                FilterType.GRAYSCALE -> {
                    val gray = Bitmap.createBitmap(sharpened.width, sharpened.height, Bitmap.Config.ARGB_8888)
                    val grayCanvas = Canvas(gray)
                    val grayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    }
                    grayCanvas.drawBitmap(sharpened, 0f, 0f, grayPaint)
                    if (sharpened != workingBitmap) {
                        sharpened.recycle()
                    } else {
                        workingBitmap.recycle()
                    }
                    gray
                }
                FilterType.BLACK_WHITE -> {
                    val bw = thresholdBitmap(sharpened, 128)
                    if (sharpened != workingBitmap) {
                        sharpened.recycle()
                    } else {
                        workingBitmap.recycle()
                    }
                    bw
                }
                FilterType.COLOR -> {
                    sharpened
                }
            }

            filtered
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }

    suspend fun removeShadows(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // Local adaptive thresholding-like illumination correction
            // Divide image by its own blurred version (approximate)
            // For simplicity in Kotlin, we'll use a basic box blur approximation or local mean
            val blurred = IntArray(width * height)
            val radius = 25
            
            for (y in 0 until height) {
                var sumR = 0; var sumG = 0; var sumB = 0
                for (x in 0 until width) {
                    val color = pixels[y * width + x]
                    sumR += (color shr 16) and 0xFF
                    sumG += (color shr 8) and 0xFF
                    sumB += color and 0xFF
                    
                    if (x >= radius) {
                        val oldColor = pixels[y * width + x - radius]
                        sumR -= (oldColor shr 16) and 0xFF
                        sumG -= (oldColor shr 8) and 0xFF
                        sumB -= oldColor and 0xFF
                    }
                    
                    val count = if (x < radius) x + 1 else radius
                    val avgR = sumR / count
                    val avgG = sumG / count
                    val avgB = sumB / count
                    
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    val a = (color shr 24) and 0xFF
                    
                    // Simple Division Shading Correction: (Original / Local Mean) * Target Mean
                    val targetMean = 200
                    val newR = (r.toDouble() / avgR.coerceAtLeast(1) * targetMean).toInt().coerceIn(0, 255)
                    val newG = (g.toDouble() / avgG.coerceAtLeast(1) * targetMean).toInt().coerceIn(0, 255)
                    val newB = (b.toDouble() / avgB.coerceAtLeast(1) * targetMean).toInt().coerceIn(0, 255)
                    
                    pixels[y * width + x] = (a shl 24) or (newR shl 16) or (newG shl 8) or newB
                }
            }

            result.setPixels(pixels, 0, width, 0, 0, width, height)
            result
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }

    suspend fun autoEnhance(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            var minR = 255; var maxR = 0
            var minG = 255; var maxG = 0
            var minB = 255; var maxB = 0

            // Step 1: Find minimum and maximum for each channel
            for (i in pixels.indices) {
                val color = pixels[i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
            }

            // Avoid division by zero
            val rangeR = (maxR - minR).coerceAtLeast(1)
            val rangeG = (maxG - minG).coerceAtLeast(1)
            val rangeB = (maxB - minB).coerceAtLeast(1)

            // Step 2: Scale/Stretch color channels to full dynamic range [0, 255]
            for (i in pixels.indices) {
                val color = pixels[i]
                val a = (color shr 24) and 0xFF
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val newR = ((r - minR) * 255 / rangeR).coerceIn(0, 255)
                val newG = ((g - minG) * 255 / rangeG).coerceIn(0, 255)
                val newB = ((b - minB) * 255 / rangeB).coerceIn(0, 255)

                pixels[i] = (a shl 24) or (newR shl 16) or (newG shl 8) or newB
            }

            result.setPixels(pixels, 0, width, 0, 0, width, height)
            result
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }

    private fun sharpenBitmap(src: Bitmap, amount: Float): Bitmap {
        val width = src.width
        val height = src.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val factor = amount * 0.5f
        val center = 1f + 4f * factor
        val edge = -factor

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x

                var rSum = 0f
                var gSum = 0f
                var bSum = 0f

                // Center
                val cColor = pixels[idx]
                rSum += ((cColor shr 16) and 0xFF) * center
                gSum += ((cColor shr 8) and 0xFF) * center
                bSum += (cColor and 0xFF) * center

                // 4-Neighbors
                val nColor1 = pixels[idx - width]  // top
                val nColor2 = pixels[idx - 1]      // left
                val nColor3 = pixels[idx + 1]      // right
                val nColor4 = pixels[idx + width]  // bottom

                val rNeighbors = ((nColor1 shr 16) and 0xFF) + ((nColor2 shr 16) and 0xFF) + ((nColor3 shr 16) and 0xFF) + ((nColor4 shr 16) and 0xFF)
                val gNeighbors = ((nColor1 shr 8) and 0xFF) + ((nColor2 shr 8) and 0xFF) + ((nColor3 shr 8) and 0xFF) + ((nColor4 shr 8) and 0xFF)
                val bNeighbors = (nColor1 and 0xFF) + (nColor2 and 0xFF) + (nColor3 and 0xFF) + (nColor4 and 0xFF)

                rSum += rNeighbors * edge
                gSum += gNeighbors * edge
                bSum += bNeighbors * edge

                val r = rSum.coerceIn(0f, 255f).toInt()
                val g = gSum.coerceIn(0f, 255f).toInt()
                val b = bSum.coerceIn(0f, 255f).toInt()

                outPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // Copy border pixels
        for (x in 0 until width) {
            outPixels[x] = pixels[x]
            outPixels[(height - 1) * width + x] = pixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            outPixels[y * width] = pixels[y * width]
            outPixels[y * width + (width - 1)] = pixels[y * width + (width - 1)]
        }

        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun thresholdBitmap(src: Bitmap, threshold: Int): Bitmap {
        val width = src.width
        val height = src.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val gray = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            val binaryColor = if (gray >= threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            pixels[i] = binaryColor
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
}
