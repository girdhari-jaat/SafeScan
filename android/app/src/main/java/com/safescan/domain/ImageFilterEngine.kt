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
                var smoothed: Mat? = null
                var thresholded: Mat? = null
                try {
                    // 1. Direct Conversion to Grayscale (No destructive color masks)
                    gray = Mat()
                    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                    
                    // 2. High-Performance Shadow Removal via downscaled background division
                    cleanGray = removeShadowsGray(gray)
                    
                    // 3. Smooth out background textures and grain while preserving text edges
                    smoothed = Mat()
                    Imgproc.bilateralFilter(cleanGray, smoothed, 7, 35.0, 35.0)
                    
                    // Also apply a very gentle Gaussian blur to smooth high-frequency noise/lines
                    Imgproc.GaussianBlur(smoothed, smoothed, Size(3.0, 3.0), 0.0)
                    
                    // 4. Balanced Adaptive Thresholding
                    val maxDim = Math.max(smoothed.cols(), smoothed.rows())
                    var blockSize = maxDim / 35
                    if (blockSize % 2 == 0) {
                        blockSize += 1
                    }
                    if (blockSize < 25) {
                        blockSize = 25
                    }
                    
                    thresholded = Mat()
                    Imgproc.adaptiveThreshold(
                        smoothed,
                        thresholded,
                        255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY,
                        blockSize,
                        9.5 // Increased from 7.5 to make text thinner, cleaner and less aggressive
                    )
                    
                    // 5. Soft-blend binary thresholded with smoothed grayscale to anti-alias font edges
                    // and make the filter look extremely professional, soft, and premium instead of harsh.
                    Core.addWeighted(thresholded, 0.85, smoothed, 0.15, 0.0, outMat)
                    
                    // Convert to final RGBA output cleanly
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_GRAY2RGBA)
                } finally {
                    gray?.release()
                    cleanGray?.release()
                    smoothed?.release()
                    thresholded?.release()
                }
            }
            FilterType.CARD -> {
                var shadowRemoved: Mat? = null
                var denoised: Mat? = null
                var lab: Mat? = null
                var mergedLab: Mat? = null
                var enhancedBgr: Mat? = null
                var sharpened: Mat? = null
                var kernel: Mat? = null
                val channels = ArrayList<Mat>()
                var clahe: org.opencv.imgproc.CLAHE? = null

                try {
                    // STEP 1: Continuous lighting normalization & shadow removal (natural background cleanup)
                    shadowRemoved = removeShadowsColor(src)

                    // STEP 2: Denoise via edge-preserving Bilateral Filter to smooth background textures
                    denoised = Mat()
                    Imgproc.bilateralFilter(shadowRemoved, denoised, 5, 40.0, 40.0)

                    // STEP 3: CLAHE on L (Lightness) channel to make text stand out without affecting colors
                    lab = Mat()
                    Imgproc.cvtColor(denoised, lab, Imgproc.COLOR_BGR2Lab)
                    Core.split(lab, channels) // channels[0]=L, [1]=A, [2]=B

                    clahe = Imgproc.createCLAHE(1.8, Size(8.0, 8.0))
                    clahe.apply(channels[0], channels[0])

                    mergedLab = Mat()
                    Core.merge(channels, mergedLab)
                    
                    enhancedBgr = Mat()
                    Imgproc.cvtColor(mergedLab, enhancedBgr, Imgproc.COLOR_Lab2BGR)

                    // STEP 4: Soft professional sharpening filter to make fonts crisp
                    kernel = Mat(3, 3, CvType.CV_32F)
                    kernel.put(0, 0, floatArrayOf(
                        0f, -0.4f, 0f,
                        -0.4f, 2.6f, -0.4f,
                        0f, -0.4f, 0f
                    ))
                    sharpened = Mat()
                    Imgproc.filter2D(enhancedBgr, sharpened, -1, kernel)

                    // Convert to final RGBA output
                    Imgproc.cvtColor(sharpened, outMat, Imgproc.COLOR_BGR2RGBA)

                } catch (e: Exception) {
                    e.printStackTrace()
                    Imgproc.cvtColor(src, outMat, Imgproc.COLOR_BGR2RGBA)
                } finally {
                    shadowRemoved?.release()
                    denoised?.release()
                    lab?.release()
                    mergedLab?.release()
                    enhancedBgr?.release()
                    sharpened?.release()
                    kernel?.release()
                    channels.forEach { it.release() }
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
                var hsv: Mat? = null
                var hsvChannels: ArrayList<Mat>? = null
                var v: Mat? = null
                var smallV: Mat? = null
                var bgSmall: Mat? = null
                var kernel: Mat? = null
                var bg: Mat? = null
                var vFloat: Mat? = null
                var bgFloat: Mat? = null
                var div: Mat? = null
                var cleanV: Mat? = null
                var enhanced: Mat? = null
                var blurred: Mat? = null
                
                try {
                    hsv = Mat()
                    Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
                    hsvChannels = ArrayList()
                    Core.split(hsv, hsvChannels)
                    
                    v = hsvChannels[2]
                    
                    // 1. Adaptive method for Shadow removal (Fast background division)
                    smallV = Mat()
                    val scale = 0.2
                    Imgproc.resize(v, smallV, Size(), scale, scale, Imgproc.INTER_LINEAR)
                    
                    bgSmall = Mat()
                    kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
                    Imgproc.dilate(smallV, bgSmall, kernel)
                    Imgproc.GaussianBlur(bgSmall, bgSmall, Size(21.0, 21.0), 0.0)
                    
                    bg = Mat()
                    Imgproc.resize(bgSmall, bg, v.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                    
                    vFloat = Mat()
                    bgFloat = Mat()
                    v.convertTo(vFloat, CvType.CV_32F)
                    bg.convertTo(bgFloat, CvType.CV_32F)
                    Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)
                    
                    div = Mat()
                    Core.divide(vFloat, bgFloat, div)
                    Core.multiply(div, org.opencv.core.Scalar(255.0), div)
                    
                    cleanV = Mat()
                    div.convertTo(cleanV, CvType.CV_8U)
                    
                    // 2. Paper ko zyada safed aur background ko clean karo
                    Imgproc.threshold(cleanV, cleanV, 230.0, 255.0, Imgproc.THRESH_TRUNC)
                    Core.normalize(cleanV, cleanV, 0.0, 255.0, Core.NORM_MINMAX)
                    
                    cleanV.copyTo(hsvChannels[2])
                    
                    // 3. Saturation thodi kam rakho (Reduce to 40%)
                    hsvChannels[1].convertTo(hsvChannels[1], -1, 0.4, 0.0)
                    
                    Core.merge(hsvChannels, hsv)
                    
                    enhanced = Mat()
                    Imgproc.cvtColor(hsv, enhanced, Imgproc.COLOR_HSV2BGR)
                    
                    // 4. Text ko crisp/sharp karo
                    blurred = Mat()
                    Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 2.5)
                    Core.addWeighted(enhanced, 1.5, blurred, -0.5, 0.0, outMat)
                    
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_BGR2RGBA)
                } finally {
                    hsv?.release()
                    hsvChannels?.forEach { it.release() }
                    smallV?.release()
                    bgSmall?.release()
                    kernel?.release()
                    bg?.release()
                    vFloat?.release()
                    bgFloat?.release()
                    div?.release()
                    cleanV?.release()
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
        var smallV: Mat? = null
        var bgSmall: Mat? = null
        var kernel: Mat? = null
        var bg: Mat? = null
        var grayFloat: Mat? = null
        var bgFloat: Mat? = null
        var div: Mat? = null
        val result = Mat()
        try {
            smallV = Mat()
            val scale = 0.2
            Imgproc.resize(gray, smallV, Size(), scale, scale, Imgproc.INTER_LINEAR)

            bgSmall = Mat()
            // Using a larger morphological closing/dilation at downscaled size to eliminate text
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            Imgproc.dilate(smallV, bgSmall, kernel)
            Imgproc.GaussianBlur(bgSmall, bgSmall, Size(25.0, 25.0), 0.0)

            bg = Mat()
            Imgproc.resize(bgSmall, bg, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)

            grayFloat = Mat()
            bgFloat = Mat()
            gray.convertTo(grayFloat, CvType.CV_32F)
            bg.convertTo(bgFloat, CvType.CV_32F)

            Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)

            div = Mat()
            Core.divide(grayFloat, bgFloat, div)
            Core.multiply(div, org.opencv.core.Scalar(255.0), div)

            div.convertTo(result, CvType.CV_8U)

            // Clamp light pixels to white to eliminate background noise and compression artifacts
            Imgproc.threshold(result, result, 225.0, 255.0, Imgproc.THRESH_TRUNC)
            Core.normalize(result, result, 0.0, 255.0, Core.NORM_MINMAX)
        } catch (e: Exception) {
            result.release()
            throw e
        } finally {
            smallV?.release()
            bgSmall?.release()
            kernel?.release()
            bg?.release()
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
