package com.safescan.domain

import com.safescan.data.FilterType
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageFilterEngine {

    fun applyFilter(src: Mat, filterType: FilterType): Mat {
        val outMat = Mat()
        when (filterType) {
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
            FilterType.PAPER -> {
                val cleanColor = removeShadowsColor(src)
                
                val hsv = Mat()
                Imgproc.cvtColor(cleanColor, hsv, Imgproc.COLOR_BGR2HSV)
                val hsvChannels = ArrayList<Mat>()
                Core.split(hsv, hsvChannels)
                
                // Increase saturation slightly, and contrast on V channel
                hsvChannels[1].convertTo(hsvChannels[1], -1, 1.2, 0.0) 
                hsvChannels[2].convertTo(hsvChannels[2], -1, 1.2, -10.0)

                Core.merge(hsvChannels, hsv)
                
                val enhanced = Mat()
                Imgproc.cvtColor(hsv, enhanced, Imgproc.COLOR_HSV2BGR)
                
                // Apply Unsharp Masking for crisp text
                val blurred = Mat()
                Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 2.5)
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
            FilterType.COLOR -> {
                Imgproc.cvtColor(src, outMat, Imgproc.COLOR_BGR2RGBA)
            }
        }
        return outMat
    }

    private fun removeShadowsGray(gray: Mat): Mat {
        val dilated = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.dilate(gray, dilated, kernel)
        
        val bgIllum = Mat()
        Imgproc.GaussianBlur(dilated, bgIllum, Size(21.0, 21.0), 0.0)
        
        val grayFloat = Mat()
        val bgFloat = Mat()
        gray.convertTo(grayFloat, CvType.CV_32F)
        bgIllum.convertTo(bgFloat, CvType.CV_32F)
        
        Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)
        
        val div = Mat()
        Core.divide(grayFloat, bgFloat, div)
        Core.multiply(div, org.opencv.core.Scalar(255.0), div)
        
        Core.normalize(div, div, 0.0, 255.0, Core.NORM_MINMAX)
        
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
        Imgproc.GaussianBlur(dilated, bgIllum, Size(21.0, 21.0), 0.0)
        
        val vFloat = Mat()
        vChannel.convertTo(vFloat, CvType.CV_32F)
        
        val bgFloat = Mat()
        bgIllum.convertTo(bgFloat, CvType.CV_32F)
        Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)
        
        val div = Mat()
        Core.divide(vFloat, bgFloat, div)
        Core.multiply(div, org.opencv.core.Scalar(255.0), div)
        
        Core.normalize(div, div, 0.0, 255.0, Core.NORM_MINMAX)
        
        val vOut = Mat()
        div.convertTo(vOut, CvType.CV_8U)
        
        channels[2] = vOut
        
        val mergedHsv = Mat()
        Core.merge(channels, mergedHsv)
        
        val outBGR = Mat()
        Imgproc.cvtColor(mergedHsv, outBGR, Imgproc.COLOR_HSV2BGR)

        hsv.release()
        vChannel.release()
        for (ch in channels) {
            ch.release()
        }
        dilated.release()
        kernel.release()
        bgIllum.release()
        vFloat.release()
        bgFloat.release()
        div.release()
        mergedHsv.release()
        
        return outBGR
    }
}
