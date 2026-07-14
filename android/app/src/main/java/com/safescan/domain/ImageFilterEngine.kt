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
                var gray: Mat? = null
                var cleanGray: Mat? = null
                var blurred: Mat? = null
                try {
                    gray = Mat()
                    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                    cleanGray = removeShadowsGray(gray)
                    
                    // Boost contrast significantly before thresholding
                    cleanGray.convertTo(cleanGray, -1, 1.5, -20.0)
                    
                    Imgproc.threshold(cleanGray, outMat, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                    
                    // Apply slight sharpening to the black and white result to make fonts crisp
                    blurred = Mat()
                    Imgproc.GaussianBlur(outMat, blurred, Size(0.0, 0.0), 2.0)
                    Core.addWeighted(outMat, 1.5, blurred, -0.5, 0.0, outMat)
                    
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_GRAY2RGBA)
                } finally {
                    gray?.release()
                    cleanGray?.release()
                    blurred?.release()
                }
            }
            FilterType.CARD -> {
                var gray: Mat? = null
                var clahe: org.opencv.imgproc.CLAHE? = null
                var claheMat: Mat? = null
                var b: Mat? = null
                try {
                    gray = Mat()
                    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                    
                    clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
                    claheMat = Mat()
                    clahe.apply(gray, claheMat)
                    
                    b = Mat()
                    Imgproc.GaussianBlur(claheMat, b, Size(0.0, 0.0), 3.0)
                    Core.addWeighted(claheMat, 1.5, b, -0.5, 0.0, outMat)
                    
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_GRAY2RGBA)
                } finally {
                    gray?.release()
                    claheMat?.release()
                    b?.release()
                    clahe?.release()
                }
            }
            FilterType.MAGIC_COLOR -> {
                var cleanColor: Mat? = null
                var hsv: Mat? = null
                var hsvChannels: ArrayList<Mat>? = null
                var enhanced: Mat? = null
                var blurred: Mat? = null
                try {
                    cleanColor = removeShadowsColor(src)
                    
                    hsv = Mat()
                    Imgproc.cvtColor(cleanColor, hsv, Imgproc.COLOR_BGR2HSV)
                    hsvChannels = ArrayList()
                    Core.split(hsv, hsvChannels)
                    
                    // Boost saturation and contrast
                    hsvChannels[1].convertTo(hsvChannels[1], -1, 1.5, 0.0)
                    hsvChannels[2].convertTo(hsvChannels[2], -1, 1.2, -10.0)
                    
                    Core.merge(hsvChannels, hsv)
                    
                    enhanced = Mat()
                    Imgproc.cvtColor(hsv, enhanced, Imgproc.COLOR_HSV2BGR)
                    
                    // Apply Unsharp Masking for crisp text
                    blurred = Mat()
                    Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 3.0)
                    Core.addWeighted(enhanced, 1.5, blurred, -0.5, 0.0, outMat)
                    
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_BGR2RGBA)
                } finally {
                    cleanColor?.release()
                    hsv?.release()
                    hsvChannels?.forEach { it.release() }
                    enhanced?.release()
                    blurred?.release()
                }
            }
            FilterType.PAPER -> {
                var cleanColor: Mat? = null
                var hsv: Mat? = null
                var hsvChannels: ArrayList<Mat>? = null
                var enhanced: Mat? = null
                var blurred: Mat? = null
                try {
                    cleanColor = removeShadowsColor(src)
                    
                    hsv = Mat()
                    Imgproc.cvtColor(cleanColor, hsv, Imgproc.COLOR_BGR2HSV)
                    hsvChannels = ArrayList()
                    Core.split(hsv, hsvChannels)
                    
                    // Increase saturation slightly, and contrast on V channel
                    hsvChannels[1].convertTo(hsvChannels[1], -1, 1.2, 0.0) 
                    hsvChannels[2].convertTo(hsvChannels[2], -1, 1.2, -10.0)
                    
                    Core.merge(hsvChannels, hsv)
                    
                    enhanced = Mat()
                    Imgproc.cvtColor(hsv, enhanced, Imgproc.COLOR_HSV2BGR)
                    
                    // Apply Unsharp Masking for crisp text
                    blurred = Mat()
                    Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 2.5)
                    Core.addWeighted(enhanced, 1.5, blurred, -0.5, 0.0, outMat)
                    
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_BGR2RGBA)
                } finally {
                    cleanColor?.release()
                    hsv?.release()
                    hsvChannels?.forEach { it.release() }
                    enhanced?.release()
                    blurred?.release()
                }
            }
            FilterType.COLOR -> {
                Imgproc.cvtColor(src, outMat, Imgproc.COLOR_BGR2RGBA)
            }
        }
        return outMat
    }

    private fun removeShadowsGray(gray: Mat): Mat {
        var dilated: Mat? = null
        var kernel: Mat? = null
        var bgIllum: Mat? = null
        var grayFloat: Mat? = null
        var bgFloat: Mat? = null
        var div: Mat? = null
        val result = Mat()
        try {
            dilated = Mat()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            Imgproc.dilate(gray, dilated, kernel)
            
            bgIllum = Mat()
            Imgproc.GaussianBlur(dilated, bgIllum, Size(21.0, 21.0), 0.0)
            
            grayFloat = Mat()
            bgFloat = Mat()
            gray.convertTo(grayFloat, CvType.CV_32F)
            bgIllum.convertTo(bgFloat, CvType.CV_32F)
            
            Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)
            
            div = Mat()
            Core.divide(grayFloat, bgFloat, div)
            Core.multiply(div, org.opencv.core.Scalar(255.0), div)
            
            Core.normalize(div, div, 0.0, 255.0, Core.NORM_MINMAX)
            
            div.convertTo(result, CvType.CV_8U)
        } catch (e: Exception) {
            result.release()
            throw e
        } finally {
            dilated?.release()
            kernel?.release()
            bgIllum?.release()
            grayFloat?.release()
            bgFloat?.release()
            div?.release()
        }
        return result
    }

    private fun removeShadowsColor(src: Mat): Mat {
        var hsv: Mat? = null
        var channels: ArrayList<Mat>? = null
        var originalV: Mat? = null
        var dilated: Mat? = null
        var kernel: Mat? = null
        var bgIllum: Mat? = null
        var vFloat: Mat? = null
        var bgFloat: Mat? = null
        var div: Mat? = null
        var vOut: Mat? = null
        var mergedHsv: Mat? = null
        val outBGR = Mat()
        
        try {
            hsv = Mat()
            Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
            channels = ArrayList()
            Core.split(hsv, channels)
            
            originalV = channels[2]
            
            dilated = Mat()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            Imgproc.dilate(originalV, dilated, kernel)
            
            bgIllum = Mat()
            Imgproc.GaussianBlur(dilated, bgIllum, Size(21.0, 21.0), 0.0)
            
            vFloat = Mat()
            originalV.convertTo(vFloat, CvType.CV_32F)
            
            bgFloat = Mat()
            bgIllum.convertTo(bgFloat, CvType.CV_32F)
            Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)
            
            div = Mat()
            Core.divide(vFloat, bgFloat, div)
            Core.multiply(div, org.opencv.core.Scalar(255.0), div)
            
            Core.normalize(div, div, 0.0, 255.0, Core.NORM_MINMAX)
            
            vOut = Mat()
            div.convertTo(vOut, CvType.CV_8U)
            
            channels[2] = vOut
            
            mergedHsv = Mat()
            Core.merge(channels, mergedHsv)
            
            Imgproc.cvtColor(mergedHsv, outBGR, Imgproc.COLOR_HSV2BGR)
        } catch (e: Exception) {
            outBGR.release()
            throw e
        } finally {
            hsv?.release()
            originalV?.release()
            channels?.forEach { 
                if (it != originalV) {
                    it.release()
                }
            }
            dilated?.release()
            kernel?.release()
            bgIllum?.release()
            vFloat?.release()
            bgFloat?.release()
            div?.release()
            vOut?.release()
            mergedHsv?.release()
        }
        
        return outBGR
    }
}
