package com.safescan.domain

import android.graphics.Bitmap
import com.safescan.data.EditorState
import com.safescan.data.FilterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint
import com.safescan.domain.model.Quadrilateral

object ImageProcessor {

    suspend fun cropDocument(bitmap: Bitmap, quad: Quadrilateral): Bitmap = withContext(Dispatchers.Default) {
        var src: Mat? = null
        var ptsSrc: MatOfPoint2f? = null
        var ptsDst: MatOfPoint2f? = null
        var perspectiveTransform: Mat? = null
        var outMat: Mat? = null
        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)

            val tl = quad.topLeft
            val tr = quad.topRight
            val br = quad.bottomRight
            val bl = quad.bottomLeft

            ptsSrc = MatOfPoint2f(
                CvPoint(tl.x, tl.y),
                CvPoint(tr.x, tr.y),
                CvPoint(br.x, br.y),
                CvPoint(bl.x, bl.y)
            )

            // Calculate width and height of the destination image
            val widthA = kotlin.math.sqrt((br.x - bl.x).let { it * it } + (br.y - bl.y).let { it * it })
            val widthB = kotlin.math.sqrt((tr.x - tl.x).let { it * it } + (tr.y - tl.y).let { it * it })
            val maxWidth = kotlin.math.max(widthA, widthB).toInt().coerceAtLeast(1)

            val heightA = kotlin.math.sqrt((tr.x - br.x).let { it * it } + (tr.y - br.y).let { it * it })
            val heightB = kotlin.math.sqrt((tl.x - bl.x).let { it * it } + (tl.y - bl.y).let { it * it })
            val maxHeight = kotlin.math.max(heightA, heightB).toInt().coerceAtLeast(1)

            ptsDst = MatOfPoint2f(
                CvPoint(0.0, 0.0),
                CvPoint(maxWidth.toDouble() - 1.0, 0.0),
                CvPoint(maxWidth.toDouble() - 1.0, maxHeight.toDouble() - 1.0),
                CvPoint(0.0, maxHeight.toDouble() - 1.0)
            )

            perspectiveTransform = Imgproc.getPerspectiveTransform(ptsSrc, ptsDst)
            outMat = Mat()
            Imgproc.warpPerspective(src, outMat, perspectiveTransform, Size(maxWidth.toDouble(), maxHeight.toDouble()))

            val resultBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outMat, resultBitmap)
            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        } finally {
            src?.release()
            ptsSrc?.release()
            ptsDst?.release()
            perspectiveTransform?.release()
            outMat?.release()
        }
    }

    suspend fun apply(bitmap: Bitmap, state: EditorState): Bitmap = withContext(Dispatchers.Default) {
        var src: Mat? = null
        var outMat: Mat? = null
        var blurred: Mat? = null
        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)

            // Convert ARGB to BGR for proper processing
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            // Apply Brightness & Contrast
            val alpha = state.contrast.toDouble()
            val beta = state.brightness.toDouble() * 255.0 / 100.0 // approximate translation
            src.convertTo(src, -1, alpha, beta)

            // Apply Sharpness
            if (state.sharpness > 0f) {
                blurred = Mat()
                Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 3.0)
                // unsharp masking
                Core.addWeighted(src, 1.0 + state.sharpness.toDouble(), blurred, -state.sharpness.toDouble(), 0.0, src)
            }

            // Apply Saturation if it's not 0
            if (state.saturation != 0f) {
                val satFactor = 1.0 + (state.saturation / 100.0)
                val hsv = Mat()
                Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
                val channels = ArrayList<Mat>()
                Core.split(hsv, channels)
                channels[1].convertTo(channels[1], -1, satFactor, 0.0)
                Core.merge(channels, hsv)
                Imgproc.cvtColor(hsv, src, Imgproc.COLOR_HSV2BGR)
                hsv.release()
                for (ch in channels) {
                    ch.release()
                }
            }

            // Apply Filter
            outMat = Mat()
            when (state.filter) {
                FilterType.GRAYSCALE -> {
                    Imgproc.cvtColor(src, outMat, Imgproc.COLOR_BGR2GRAY)
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_GRAY2RGBA)
                }
                FilterType.BLACK_WHITE -> {
                    val gray = Mat()
                    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                    val cleanGray = removeShadowsGray(gray)
                    
                    // Boost contrast significantly before thresholding
                    cleanGray.convertTo(cleanGray, -1, 1.5, -20.0)
                    
                    Imgproc.threshold(cleanGray, outMat, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                    
                    // Apply slight sharpening to the black and white result to make fonts crisp
                    val blurred = Mat()
                    Imgproc.GaussianBlur(outMat, blurred, Size(0.0, 0.0), 2.0)
                    Core.addWeighted(outMat, 1.5, blurred, -0.5, 0.0, outMat)
                    
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_GRAY2RGBA)
                    gray.release()
                    cleanGray.release()
                    blurred.release()
                }
                FilterType.CARD -> {
                    val gray = Mat()
                    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                    
                    val clahe = Imgproc.createCLAHE(3.0, org.opencv.core.Size(8.0, 8.0))
                    val claheMat = Mat()
                    clahe.apply(gray, claheMat)
                    
                    val b = Mat()
                    Imgproc.GaussianBlur(claheMat, b, org.opencv.core.Size(0.0, 0.0), 3.0)
                    Core.addWeighted(claheMat, 1.5, b, -0.5, 0.0, outMat)
                    
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_GRAY2RGBA)
                    
                    gray.release()
                    claheMat.release()
                    b.release()
                    clahe.collectGarbage() // Just in case, or let GC handle
                }
                FilterType.MAGIC_COLOR -> {
                    val cleanColor = removeShadowsColor(src)
                    
                    val hsv = Mat()
                    Imgproc.cvtColor(cleanColor, hsv, Imgproc.COLOR_BGR2HSV)
                    val hsvChannels = ArrayList<Mat>()
                    Core.split(hsv, hsvChannels)
                    
                    // Boost saturation and contrast
                    hsvChannels[1].convertTo(hsvChannels[1], -1, 1.5, 0.0)
                    hsvChannels[2].convertTo(hsvChannels[2], -1, 1.2, -10.0)
                    
                    Core.merge(hsvChannels, hsv)
                    
                    val enhanced = Mat()
                    Imgproc.cvtColor(hsv, enhanced, Imgproc.COLOR_HSV2BGR)
                    
                    // Apply Unsharp Masking for crisp text
                    val blurred = Mat()
                    Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 3.0)
                    Core.addWeighted(enhanced, 1.5, blurred, -0.5, 0.0, outMat)
                    
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_BGR2RGBA)
                    
                    cleanColor.release()
                    hsv.release()
                    for (ch in hsvChannels) {
                        ch.release()
                    }
                    enhanced.release()
                    blurred.release()
                }
                FilterType.PHOTO -> {
                    val hsv = Mat()
                    Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
                    val channels = mutableListOf<Mat>()
                    Core.split(hsv, channels)
                    
                    channels[1].convertTo(channels[1], -1, 1.2, 0.0)
                    
                    Core.merge(channels, hsv)
                    Imgproc.cvtColor(hsv, outMat, Imgproc.COLOR_HSV2BGR)
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_BGR2RGBA)
                    
                    hsv.release()
                    for (ch in channels) {
                        ch.release()
                    }
                }
                FilterType.AUTO -> {
                    val cleanColor = removeShadowsColor(src)
                    
                    val hsv = Mat()
                    Imgproc.cvtColor(cleanColor, hsv, Imgproc.COLOR_BGR2HSV)
                    val hsvChannels = ArrayList<Mat>()
                    Core.split(hsv, hsvChannels)
                    
                    hsvChannels[1].convertTo(hsvChannels[1], -1, 1.2, 0.0) 
                    hsvChannels[2].convertTo(hsvChannels[2], -1, 1.15, -15.0)

                    Core.merge(hsvChannels, hsv)
                    
                    val enhanced = Mat()
                    Imgproc.cvtColor(hsv, enhanced, Imgproc.COLOR_HSV2BGR)
                    
                    // Apply Unsharp Masking for crisp text
                    val blurred = Mat()
                    Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 2.5)
                    Core.addWeighted(enhanced, 1.4, blurred, -0.4, 0.0, outMat)
                    
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_BGR2RGBA)
                    
                    cleanColor.release()
                    hsv.release()
                    for (ch in hsvChannels) {
                        ch.release()
                    }
                    enhanced.release()
                    blurred.release()
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
        } finally {
            src?.release()
            outMat?.release()
            blurred?.release()
        }
    }

    suspend fun autoEnhance(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        var src: Mat? = null
        var lab: Mat? = null
        var labChannels: ArrayList<Mat>? = null
        var dilated: Mat? = null
        var kernel: Mat? = null
        var bgIllum: Mat? = null
        var diff: Mat? = null
        var ones: Mat? = null
        var bgrChannels: ArrayList<Mat>? = null
        var outMat: Mat? = null
        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)
            
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            // 1. Shadow Removal Logic
            lab = Mat()
            Imgproc.cvtColor(src, lab, Imgproc.COLOR_BGR2Lab)
            labChannels = ArrayList()
            Core.split(lab, labChannels)
            
            val lChannel = labChannels[0]
            dilated = Mat()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            Imgproc.dilate(lChannel, dilated, kernel)
            bgIllum = Mat()
            Imgproc.medianBlur(dilated, bgIllum, 21)
            
            diff = Mat()
            Core.absdiff(lChannel, bgIllum, diff)
            ones = Mat.ones(lChannel.size(), lChannel.type()).apply { setTo(org.opencv.core.Scalar(255.0)) }
            Core.subtract(ones, diff, lChannel)
            
            Core.merge(labChannels, lab)
            Imgproc.cvtColor(lab, src, Imgproc.COLOR_Lab2BGR)

            // 2. Auto-level / contrast stretching
            bgrChannels = ArrayList()
            Core.split(src, bgrChannels)
            for (i in bgrChannels.indices) {
                Core.normalize(bgrChannels[i], bgrChannels[i], 0.0, 255.0, Core.NORM_MINMAX)
            }
            Core.merge(bgrChannels, src)

            outMat = Mat()
            Imgproc.cvtColor(src, outMat, Imgproc.COLOR_BGR2RGBA)

            val resultBitmap = Bitmap.createBitmap(outMat.cols(), outMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outMat, resultBitmap)
            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        } finally {
            src?.release()
            lab?.release()
            labChannels?.forEach { it.release() }
            dilated?.release()
            kernel?.release()
            bgIllum?.release()
            diff?.release()
            ones?.release()
            bgrChannels?.forEach { it.release() }
            outMat?.release()
        }
    }

    private fun removeShadowsGray(gray: Mat): Mat {
        val dilated = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.dilate(gray, dilated, kernel)
        
        val bgIllum = Mat()
        Imgproc.GaussianBlur(dilated, bgIllum, Size(31.0, 31.0), 0.0)
        
        val grayFloat = Mat()
        val bgFloat = Mat()
        gray.convertTo(grayFloat, CvType.CV_32F)
        bgIllum.convertTo(bgFloat, CvType.CV_32F)
        
        Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)
        
        val div = Mat()
        Core.divide(grayFloat, bgFloat, div)
        Core.multiply(div, org.opencv.core.Scalar(255.0), div)
        
        val result = Mat()
        div.convertTo(result, CvType.CV_8U)

        dilated.release()
        kernel.release()
        bgIllum.release()
        grayFloat.release()
        bgFloat.release()
        div.release()

        return result
    }

    private fun removeShadowsColor(src: Mat): Mat {
        val hsv = Mat()
        Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
        val channels = ArrayList<Mat>()
        Core.split(hsv, channels)
        
        val vChannel = channels[2]
        
        val dilated = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.dilate(vChannel, dilated, kernel)
        
        val bgIllum = Mat()
        Imgproc.GaussianBlur(dilated, bgIllum, Size(31.0, 31.0), 0.0)
        
        val srcFloat = Mat()
        src.convertTo(srcFloat, CvType.CV_32F)
        
        val bgFloat = Mat()
        bgIllum.convertTo(bgFloat, CvType.CV_32F)
        Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)
        
        val bgChannels = ArrayList<Mat>(listOf(bgFloat, bgFloat, bgFloat))
        val bgFloat3 = Mat()
        Core.merge(bgChannels, bgFloat3)
        
        val div = Mat()
        Core.divide(srcFloat, bgFloat3, div)
        Core.multiply(div, org.opencv.core.Scalar(255.0, 255.0, 255.0), div)
        
        val outBGR = Mat()
        div.convertTo(outBGR, CvType.CV_8U)

        hsv.release()
        for (ch in channels) {
            ch.release()
        }
        dilated.release()
        kernel.release()
        bgIllum.release()
        srcFloat.release()
        bgFloat.release()
        bgFloat3.release()
        div.release()
        
        return outBGR
    }
}
