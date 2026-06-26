package com.safescan.domain

import android.graphics.Bitmap
import com.safescan.data.EditorState
import com.safescan.data.FilterType
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.core.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageProcessor {
    // IMPROVEMENT: Wrap OpenCV conversions in try-catch-finally to eliminate memory leaks and crashes
    suspend fun apply(bitmap: Bitmap, state: EditorState): Bitmap = withContext(Dispatchers.Default) {
        val src = Mat()
        val dest = Mat()
        var blurred: Mat? = null
        try {
            Utils.bitmapToMat(bitmap, src)
            src.copyTo(dest)

            // Brightness and Contrast
            dest.convertTo(dest, -1, state.contrast.toDouble(), state.brightness.toDouble())

            // Sharpness
            if (state.sharpness > 0f) {
                blurred = Mat()
                Imgproc.GaussianBlur(dest, blurred, Size(0.0, 0.0), 3.0)
                Core.addWeighted(dest, 1.0 + state.sharpness / 5.0, blurred, -state.sharpness / 5.0, 0.0, dest)
            }

            // Filters
            when (state.filter) {
                FilterType.GRAYSCALE -> {
                    Imgproc.cvtColor(dest, dest, Imgproc.COLOR_RGBA2GRAY)
                    Imgproc.cvtColor(dest, dest, Imgproc.COLOR_GRAY2RGBA)
                }
                FilterType.BLACK_WHITE -> {
                    Imgproc.cvtColor(dest, dest, Imgproc.COLOR_RGBA2GRAY)
                    Imgproc.threshold(dest, dest, 128.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                    Imgproc.cvtColor(dest, dest, Imgproc.COLOR_GRAY2RGBA)
                }
                FilterType.COLOR -> {
                    // Keep existing channels
                }
            }

            val resultBitmap = Bitmap.createBitmap(dest.cols(), dest.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dest, resultBitmap)
            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        } finally {
            src.release()
            dest.release()
            blurred?.release()
        }
    }

    // IMPROVEMENT: Added try-catch-finally for robust AutoEnhance
    suspend fun autoEnhance(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val src = Mat()
        val lab = Mat()
        val channels = ArrayList<Mat>()
        try {
            Utils.bitmapToMat(bitmap, src)

            Imgproc.cvtColor(src, lab, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab)
            
            Core.split(lab, channels)

            if (channels.isNotEmpty()) {
                val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                clahe.apply(channels[0], channels[0])
                Core.merge(channels, lab)
            }

            Imgproc.cvtColor(lab, lab, Imgproc.COLOR_Lab2RGB)
            Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2RGBA)

            val resultBitmap = Bitmap.createBitmap(lab.cols(), lab.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(lab, resultBitmap)
            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        } finally {
            src.release()
            lab.release()
            for (c in channels) {
                c.release()
            }
        }
    }
}
