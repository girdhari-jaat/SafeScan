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

            // Apply Shadow Removal if requested via settings (we check a hypothetical setting here or just implement it)
            // Since we don't have a direct toggle in EditorState, we can add it or just use it in autoEnhance.
            // For now, let's just make it available as a helper.
            
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
                    Imgproc.threshold(gray, outMat, 128.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_GRAY2RGBA)
                }
                FilterType.BLACK_WHITE_2 -> {
                    val gray = Mat()
                    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                    // Adaptive threshold for cleaner photocopy-like scans - Increased block size and C for cleaner white background
                    Imgproc.adaptiveThreshold(gray, outMat, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 15.0)
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_GRAY2RGBA)
                }
                FilterType.MAGIC_COLOR -> {
                    // Implement whiteboard/magic color enhance inspired by OSS-DocumentScanner
                    // 1. Difference of Gaussians (DoG)
                    val blurred1 = Mat()
                    val blurred2 = Mat()
                    Imgproc.GaussianBlur(src, blurred1, Size(15.0, 15.0), 10.0)
                    Imgproc.GaussianBlur(src, blurred2, Size(15.0, 15.0), 2.0)
                    
                    val dog = Mat()
                    Core.subtract(blurred1, blurred2, dog)
                    
                    // 2. Negate
                    Core.bitwise_not(dog, dog)
                    
                    // 3. Contrast stretch (approximate by auto-leveling each channel)
                    val channels = ArrayList<Mat>()
                    Core.split(dog, channels)
                    for (i in channels.indices) {
                        Core.normalize(channels[i], channels[i], 0.0, 255.0, Core.NORM_MINMAX)
                    }
                    Core.merge(channels, outMat)
                    
                    // 4. Color balance (increase saturation and recover true colors slightly)
                    val hsv = Mat()
                    Imgproc.cvtColor(outMat, hsv, Imgproc.COLOR_BGR2HSV)
                    val hsvChannels = ArrayList<Mat>()
                    Core.split(hsv, hsvChannels)
                    hsvChannels[1].convertTo(hsvChannels[1], -1, 1.5, 0.0) // Boost saturation
                    Core.merge(hsvChannels, hsv)
                    
                    Imgproc.cvtColor(hsv, outMat, Imgproc.COLOR_HSV2BGR)
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_BGR2RGBA)
                }
                FilterType.PHOTO -> {
                    // Increase saturation for "Vibrant" look
                    val hsv = Mat()
                    Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
                    val channels = mutableListOf<Mat>()
                    Core.split(hsv, channels)
                    
                    // Scale saturation channel
                    channels[1].convertTo(channels[1], -1, 1.2, 0.0)
                    
                    Core.merge(channels, hsv)
                    Imgproc.cvtColor(hsv, outMat, Imgproc.COLOR_HSV2BGR)
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_BGR2RGBA)
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

            // 1. Shadow Removal Logic (Akylas inspired)
            val lab = Mat()
            Imgproc.cvtColor(src, lab, Imgproc.COLOR_BGR2Lab)
            val labChannels = ArrayList<Mat>()
            Core.split(lab, labChannels)
            
            val lChannel = labChannels[0]
            val dilated = Mat()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            Imgproc.dilate(lChannel, dilated, kernel)
            val bgIllum = Mat()
            Imgproc.medianBlur(dilated, bgIllum, 21)
            
            // Divide L by background illumination to flatten the shadows
            val diff = Mat()
            Core.absdiff(lChannel, bgIllum, diff)
            Core.subtract(Mat.ones(lChannel.size(), lChannel.type()).apply { setTo(org.opencv.core.Scalar(255.0)) }, diff, lChannel)
            
            Core.merge(labChannels, lab)
            Imgproc.cvtColor(lab, src, Imgproc.COLOR_Lab2BGR)

            // 2. Auto-level / contrast stretching
            val bgrChannels = ArrayList<Mat>()
            Core.split(src, bgrChannels)
            for (i in bgrChannels.indices) {
                Core.normalize(bgrChannels[i], bgrChannels[i], 0.0, 255.0, Core.NORM_MINMAX)
            }
            Core.merge(bgrChannels, src)

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
