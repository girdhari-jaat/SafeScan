package com.safescan.domain

import com.safescan.data.FilterType
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageFilterEngine {

    private fun safeRelease(mat: Mat?) {
        if (mat != null) {
            try {
                if (!mat.empty()) {
                    // Non-empty mat
                }
                mat.release()
            } catch (e: Throwable) {
                // Ignore release errors safely
            }
        }
    }

    private fun safeCollectGarbage(clahe: org.opencv.imgproc.CLAHE?) {
        if (clahe != null) {
            try {
                clahe.collectGarbage()
            } catch (e: Throwable) {
                // Ignore if method not supported
            }
        }
    }

    fun applyCardFilter(src: Mat): Mat {
        val out = Mat()

        var hsv: Mat? = null
        var channels: MutableList<Mat>? = null
        var smallV: Mat? = null
        var bgSmall: Mat? = null
        var kernel: Mat? = null
        var bg: Mat? = null
        var vFloat: Mat? = null
        var bgFloat: Mat? = null
        var div: Mat? = null
        var cleanV: Mat? = null
        var clahe: org.opencv.imgproc.CLAHE? = null
        var enhanced: Mat? = null
        var blurred: Mat? = null

        try {
            if (src.empty()) return out

            // BGR -> HSV
            hsv = Mat()
            Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)

            channels = ArrayList()
            Core.split(hsv, channels)

            // -----------------------------
            // 1. Light background shadow whitening
            // -----------------------------
            smallV = Mat()
            Imgproc.resize(channels[2], smallV, Size(), 0.2, 0.2, Imgproc.INTER_LINEAR)

            bgSmall = Mat()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 11.0))
            Imgproc.dilate(smallV, bgSmall, kernel)
            Imgproc.GaussianBlur(bgSmall, bgSmall, Size(21.0, 21.0), 0.0)

            bg = Mat()
            Imgproc.resize(bgSmall, bg, channels[2].size(), 0.0, 0.0, Imgproc.INTER_LINEAR)

            vFloat = Mat()
            bgFloat = Mat()
            channels[2].convertTo(vFloat, CvType.CV_32F)
            bg.convertTo(bgFloat, CvType.CV_32F)
            Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)

            div = Mat()
            Core.divide(vFloat, bgFloat, div)
            Core.multiply(div, org.opencv.core.Scalar(255.0), div)

            cleanV = Mat()
            div.convertTo(cleanV, CvType.CV_8U)

            // Blend 60% shadow-flattened background with 40% original background
            Core.addWeighted(channels[2], 0.4, cleanV, 0.6, 0.0, channels[2])

            // -----------------------------
            // 2. Adjust saturation & boost contrast slightly
            // -----------------------------
            channels[1].convertTo(channels[1], -1, 0.85, 0.0)
            channels[2].convertTo(channels[2], -1, 1.12, -4.0)

            // Improve brightness consistency
            clahe = Imgproc.createCLAHE(1.5, Size(8.0, 8.0))
            clahe.apply(channels[2], channels[2])

            // Merge HSV
            Core.merge(channels, hsv)

            // HSV -> BGR
            enhanced = Mat()
            Imgproc.cvtColor(hsv, enhanced, Imgproc.COLOR_HSV2BGR)

            // Lightweight Unsharp Mask
            blurred = Mat()
            Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 1.5)
            Core.addWeighted(enhanced, 1.3, blurred, -0.3, 0.0, out)

            // Final RGBA
            Imgproc.cvtColor(out, out, Imgproc.COLOR_BGR2RGBA)

        } catch (e: Exception) {
            if (!src.empty()) {
                Imgproc.cvtColor(src, out, Imgproc.COLOR_BGR2RGBA)
            }
        } finally {
            safeRelease(hsv)
            channels?.forEach { safeRelease(it) }
            safeRelease(smallV)
            safeRelease(bgSmall)
            safeRelease(kernel)
            safeRelease(bg)
            safeRelease(vFloat)
            safeRelease(bgFloat)
            safeRelease(div)
            safeRelease(cleanV)
            safeRelease(enhanced)
            safeRelease(blurred)
            safeCollectGarbage(clahe)
        }

        return out
    }

    fun applyFilter(src: Mat, filterType: FilterType): Mat {
        val outMat = Mat()
        when (filterType) {
            FilterType.GRAYSCALE -> {
                var gray: Mat? = null
                var sharpGray: Mat? = null
                var kernel: Mat? = null
                try {
                    gray = Mat()
                    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                    
                    // Fast 3x3 sharpening kernel on single-channel Grayscale for ultra-fast text sharpening
                    kernel = Mat(3, 3, CvType.CV_32F)
                    val kernelData = floatArrayOf(
                        0f, -0.2f, 0f,
                        -0.2f, 1.8f, -0.2f,
                        0f, -0.2f, 0f
                    )
                    kernel.put(0, 0, kernelData)
                    
                    sharpGray = Mat()
                    Imgproc.filter2D(gray, sharpGray, -1, kernel)
                    
                    Imgproc.cvtColor(sharpGray, outMat, Imgproc.COLOR_GRAY2RGBA)
                } finally {
                    safeRelease(gray)
                    safeRelease(sharpGray)
                    safeRelease(kernel)
                }
            }
            FilterType.BLACK_WHITE -> {
                var bgrChannels: ArrayList<Mat>? = null
                var minBG: Mat? = null
                var minBGR: Mat? = null
                var standardGray: Mat? = null
                var darkTextGray: Mat? = null
                var cleanGray: Mat? = null
                var blurred: Mat? = null
                var sharp: Mat? = null
                var bwResult: Mat? = null
                try {
                    // Extract channels to preserve light-colored fonts (blue, red, green pen/stamps)
                    bgrChannels = ArrayList()
                    Core.split(src, bgrChannels)
                    
                    minBG = Mat()
                    Core.min(bgrChannels[0], bgrChannels[1], minBG)
                    minBGR = Mat()
                    Core.min(minBG, bgrChannels[2], minBGR)
                    
                    standardGray = Mat()
                    Imgproc.cvtColor(src, standardGray, Imgproc.COLOR_BGR2GRAY)
                    
                    // Blend minBGR with standard Gray so all ink colors (blue/red/pencil) become dark ink
                    darkTextGray = Mat()
                    Core.addWeighted(standardGray, 0.4, minBGR, 0.6, 0.0, darkTextGray)
                    
                    // 1. Remove shadows and uneven background lighting
                    cleanGray = removeShadowsGray(darkTextGray)
                    
                    // 2. Unsharp Masking for sharpening blurred text & fine edges
                    blurred = Mat()
                    Imgproc.GaussianBlur(cleanGray, blurred, Size(0.0, 0.0), 2.5)
                    sharp = Mat()
                    Core.addWeighted(cleanGray, 1.4, blurred, -0.4, 0.0, sharp)
                    
                    // 3. Smooth Contrast Stretching (Adobe Scan / CamScanner Style B&W)
                    // Maps background (> 200) to pure 255 paper white, and text (< 120) to deep dark black/gray
                    bwResult = Mat()
                    sharp.convertTo(bwResult, -1, 1.75, -110.0)
                    
                    // 4. Output RGBA
                    Imgproc.cvtColor(bwResult, outMat, Imgproc.COLOR_GRAY2RGBA)
                } finally {
                    bgrChannels?.forEach { safeRelease(it) }
                    safeRelease(minBG)
                    safeRelease(minBGR)
                    safeRelease(standardGray)
                    safeRelease(darkTextGray)
                    safeRelease(cleanGray)
                    safeRelease(blurred)
                    safeRelease(sharp)
                    safeRelease(bwResult)
                }
            }
            FilterType.CARD -> {
                val cardResult = applyCardFilter(src)
                cardResult.copyTo(outMat)
                safeRelease(cardResult)
            }
            FilterType.MAGIC_COLOR -> {
                var cleanColor: Mat? = null
                var hsv: Mat? = null
                var hsvChannels: ArrayList<Mat>? = null
                var darkMask: Mat? = null
                var enhanced: Mat? = null
                var blurred: Mat? = null
                try {
                    cleanColor = removeShadowsColor(src)
                    
                    hsv = Mat()
                    Imgproc.cvtColor(cleanColor, hsv, Imgproc.COLOR_BGR2HSV)
                    hsvChannels = ArrayList()
                    Core.split(hsv, hsvChannels)
                    
                    // Desaturate dark text regions (V < 95) so black text stays neutral black/gray without green/cyan tint
                    darkMask = Mat()
                    Imgproc.threshold(hsvChannels[2], darkMask, 95.0, 255.0, Imgproc.THRESH_BINARY_INV)
                    hsvChannels[1].setTo(org.opencv.core.Scalar(0.0), darkMask)

                    // Boost saturation and contrast gently for colorful regions
                    hsvChannels[1].convertTo(hsvChannels[1], -1, 1.2, 0.0)
                    hsvChannels[2].convertTo(hsvChannels[2], -1, 1.15, -5.0)
                    
                    Core.merge(hsvChannels, hsv)
                    
                    enhanced = Mat()
                    Imgproc.cvtColor(hsv, enhanced, Imgproc.COLOR_HSV2BGR)
                    
                    // Apply Unsharp Masking for crisp text
                    blurred = Mat()
                    Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 3.0)
                    Core.addWeighted(enhanced, 1.4, blurred, -0.4, 0.0, outMat)
                    
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_BGR2RGBA)
                } finally {
                    safeRelease(cleanColor)
                    safeRelease(hsv)
                    hsvChannels?.forEach { safeRelease(it) }
                    safeRelease(darkMask)
                    safeRelease(enhanced)
                    safeRelease(blurred)
                }
            }
            FilterType.PAPER -> {
                var bgrChannels: ArrayList<Mat>? = null
                var minBG: Mat? = null
                var minBGR: Mat? = null
                var standardGray: Mat? = null
                var darkTextGray: Mat? = null
                var smallV: Mat? = null
                var bgSmall: Mat? = null
                var kernel: Mat? = null
                var bg: Mat? = null
                var vFloat: Mat? = null
                var bgFloat: Mat? = null
                var div: Mat? = null
                var cleanV: Mat? = null
                var hsv: Mat? = null
                var hsvChannels: ArrayList<Mat>? = null
                var enhanced: Mat? = null
                var blurred: Mat? = null
                
                try {
                    // Extract dark text channel (combining minBGR with Grayscale) so blue/red pen ink is preserved as dark text
                    bgrChannels = ArrayList()
                    Core.split(src, bgrChannels)
                    
                    minBG = Mat()
                    Core.min(bgrChannels[0], bgrChannels[1], minBG)
                    minBGR = Mat()
                    Core.min(minBG, bgrChannels[2], minBGR)
                    
                    standardGray = Mat()
                    Imgproc.cvtColor(src, standardGray, Imgproc.COLOR_BGR2GRAY)
                    
                    darkTextGray = Mat()
                    Core.addWeighted(standardGray, 0.3, minBGR, 0.7, 0.0, darkTextGray)
                    
                    // 1. Adaptive method for Shadow removal on darkTextGray
                    smallV = Mat()
                    val scale = 0.2
                    Imgproc.resize(darkTextGray, smallV, Size(), scale, scale, Imgproc.INTER_LINEAR)
                    
                    bgSmall = Mat()
                    kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
                    Imgproc.dilate(smallV, bgSmall, kernel)
                    Imgproc.GaussianBlur(bgSmall, bgSmall, Size(21.0, 21.0), 0.0)
                    
                    bg = Mat()
                    Imgproc.resize(bgSmall, bg, darkTextGray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                    
                    vFloat = Mat()
                    bgFloat = Mat()
                    darkTextGray.convertTo(vFloat, CvType.CV_32F)
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
                    
                    // 3. Convert clean BGR back to HSV to preserve blue/colored handwriting ink saturation
                    hsv = Mat()
                    Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
                    hsvChannels = ArrayList()
                    Core.split(hsv, hsvChannels)
                    
                    cleanV.copyTo(hsvChannels[2])
                    
                    // Keep saturation natural for colored ink (blue pen) so it stays visible and clear
                    hsvChannels[1].convertTo(hsvChannels[1], -1, 1.05, 0.0)
                    
                    Core.merge(hsvChannels, hsv)
                    
                    enhanced = Mat()
                    Imgproc.cvtColor(hsv, enhanced, Imgproc.COLOR_HSV2BGR)
                    
                    // 4. Text ko crisp/sharp karo
                    blurred = Mat()
                    Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 2.5)
                    Core.addWeighted(enhanced, 1.4, blurred, -0.4, 0.0, outMat)
                    
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_BGR2RGBA)
                } finally {
                    bgrChannels?.forEach { safeRelease(it) }
                    safeRelease(minBG)
                    safeRelease(minBGR)
                    safeRelease(standardGray)
                    safeRelease(darkTextGray)
                    safeRelease(smallV)
                    safeRelease(bgSmall)
                    safeRelease(kernel)
                    safeRelease(bg)
                    safeRelease(vFloat)
                    safeRelease(bgFloat)
                    safeRelease(div)
                    safeRelease(cleanV)
                    safeRelease(hsv)
                    hsvChannels?.forEach { safeRelease(it) }
                    safeRelease(enhanced)
                    safeRelease(blurred)
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
