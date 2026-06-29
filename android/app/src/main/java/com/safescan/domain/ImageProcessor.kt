package com.safescan.domain

import android.graphics.Bitmap
import com.safescan.data.EditorState
import com.safescan.data.FilterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageProcessor {

    suspend fun apply(bitmap: Bitmap, state: EditorState): Bitmap = withContext(Dispatchers.Default) {
        try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)

            // Convert ARGB to BGR for proper processing
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            // Apply Brightness & Contrast
            // alpha = contrast (1.0 = normal), beta = brightness (0 = normal)
            // But state.brightness is translate (-100 to 100), state.contrast is scale (0.5 to 2.0)
            val alpha = state.contrast.toDouble()
            val beta = state.brightness.toDouble() * 255.0 / 100.0 // approximate translation
            src.convertTo(src, -1, alpha, beta)

            // Apply Sharpness
            if (state.sharpness > 0f) {
                val blurred = Mat()
                Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 3.0)
                // unsharp masking
                Core.addWeighted(src, 1.0 + state.sharpness.toDouble(), blurred, -state.sharpness.toDouble(), 0.0, src)
            }

            // Apply Filter
            val outMat = Mat()
            when (state.filter) {
                FilterType.GRAYSCALE -> {
                    Imgproc.cvtColor(src, outMat, Imgproc.COLOR_BGR2GRAY)
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_GRAY2RGBA)
                }
                FilterType.BLACK_WHITE -> {
                    val gray = Mat()
                    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                    // Otsu's binarization + Gaussian blur for better results, but simple threshold works too
                    Imgproc.threshold(gray, outMat, 128.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_GRAY2RGBA)
                }
                FilterType.COLOR -> {
                    Imgproc.cvtColor(src, outMat, Imgproc.COLOR_BGR2RGBA)
                }
            }

            val resultBitmap = Bitmap.createBitmap(outMat.cols(), outMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outMat, resultBitmap)
            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }

    suspend fun autoEnhance(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)
            
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            // Auto-level / contrast stretching
            val channels = ArrayList<Mat>()
            Core.split(src, channels)
            
            for (i in channels.indices) {
                Core.normalize(channels[i], channels[i], 0.0, 255.0, Core.NORM_MINMAX)
            }
            Core.merge(channels, src)

            val outMat = Mat()
            Imgproc.cvtColor(src, outMat, Imgproc.COLOR_BGR2RGBA)

            val resultBitmap = Bitmap.createBitmap(outMat.cols(), outMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outMat, resultBitmap)
            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }
}
