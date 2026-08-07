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
            val maxDim = maxOf(channels[2].cols(), channels[2].rows())
            val scale = if (maxDim > 0) (320.0 / maxDim).coerceAtMost(0.4) else 0.2
            Imgproc.resize(channels[2], smallV, Size(), scale, scale, Imgproc.INTER_LINEAR)

            bgSmall = Mat()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            Imgproc.dilate(smallV, bgSmall, kernel)
            Imgproc.GaussianBlur(bgSmall, bgSmall, Size(21.0, 21.0), 0.0)

            bg = Mat()
            Imgproc.resize(bgSmall, bg, channels[2].size(), 0.0, 0.0, Imgproc.INTER_LINEAR)

            vFloat = Mat()
            bgFloat = Mat()
            channels[2].convertTo(vFloat, CvType.CV_32F)
            bg.convertTo(bgFloat, CvType.CV_32F)
            Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)
            Core.max(bgFloat, org.opencv.core.Scalar(110.0), bgFloat)

            div = Mat()
            Core.divide(vFloat, bgFloat, div)
            Core.multiply(div, org.opencv.core.Scalar(255.0), div)

            cleanV = Mat()
            div.convertTo(cleanV, CvType.CV_8U)

            // Whiten paper/card background slightly to eliminate gray shadow patches around text
            cleanV.convertTo(cleanV, -1, 1.15, -20.0)

            // Blend 80% clean shadow-removed background with 20% original V
            Core.addWeighted(channels[2], 0.2, cleanV, 0.8, 0.0, channels[2])

            // -----------------------------
            // 2. Adjust saturation & boost contrast gently
            // -----------------------------
            channels[1].convertTo(channels[1], -1, 0.95, 0.0)
            channels[2].convertTo(channels[2], -1, 1.05, 0.0)

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
        try {
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
                var cardResult: Mat? = null
                try {
                    cardResult = applyCardFilter(src)
                    cardResult.copyTo(outMat)
                } finally {
                    safeRelease(cardResult)
                }
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
                    
                    // Desaturate only deep black text regions (V < 60) so black text stays neutral black/gray without green/cyan tint
                    darkMask = Mat()
                    Imgproc.threshold(hsvChannels[2], darkMask, 60.0, 255.0, Imgproc.THRESH_BINARY_INV)
                    hsvChannels[1].setTo(org.opencv.core.Scalar(0.0), darkMask)

                    // Boost saturation and contrast gently for colorful regions
                    hsvChannels[1].convertTo(hsvChannels[1], -1, 1.12, 0.0)
                    hsvChannels[2].convertTo(hsvChannels[2], -1, 1.08, 0.0)
                    
                    Core.merge(hsvChannels, hsv)
                    
                    enhanced = Mat()
                    Imgproc.cvtColor(hsv, enhanced, Imgproc.COLOR_HSV2BGR)
                    
                    // Apply Unsharp Masking for crisp text
                    blurred = Mat()
                    Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 2.0)
                    Core.addWeighted(enhanced, 1.25, blurred, -0.25, 0.0, outMat)
                    
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
                    val maxDim = maxOf(darkTextGray.cols(), darkTextGray.rows())
                    val scale = if (maxDim > 0) (320.0 / maxDim).coerceAtMost(0.4) else 0.2
                    Imgproc.resize(darkTextGray, smallV, Size(), scale, scale, Imgproc.INTER_LINEAR)
                    
                    bgSmall = Mat()
                    kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
                    Imgproc.dilate(smallV, bgSmall, kernel)
                    Imgproc.GaussianBlur(bgSmall, bgSmall, Size(21.0, 21.0), 0.0)
                    
                    bg = Mat()
                    Imgproc.resize(bgSmall, bg, darkTextGray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                    
                    vFloat = Mat()
                    bgFloat = Mat()
                    darkTextGray.convertTo(vFloat, CvType.CV_32F)
                    bg.convertTo(bgFloat, CvType.CV_32F)
                    Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)
                    Core.max(bgFloat, org.opencv.core.Scalar(110.0), bgFloat)
                    
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
        } catch (e: Exception) {
            safeRelease(outMat)
            val fallback = Mat()
            try {
                if (!src.empty()) {
                    Imgproc.cvtColor(src, fallback, Imgproc.COLOR_BGR2RGBA)
                }
            } catch (t: Throwable) {
                // Ignore fallback error
            }
            return fallback
        }
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
            val maxDim = maxOf(gray.cols(), gray.rows())
            val scale = if (maxDim > 0) (320.0 / maxDim).coerceAtMost(0.4) else 0.2
            Imgproc.resize(gray, smallV, Size(), scale, scale, Imgproc.INTER_AREA)

            bgSmall = Mat()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(21.0, 21.0))
            Imgproc.dilate(smallV, bgSmall, kernel)
            Imgproc.GaussianBlur(bgSmall, bgSmall, Size(31.0, 31.0), 0.0)

            bg = Mat()
            Imgproc.resize(bgSmall, bg, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)

            grayFloat = Mat()
            bgFloat = Mat()
            gray.convertTo(grayFloat, CvType.CV_32F)
            bg.convertTo(bgFloat, CvType.CV_32F)

            Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)
            Core.max(bgFloat, org.opencv.core.Scalar(95.0), bgFloat)

            div = Mat()
            Core.divide(grayFloat, bgFloat, div)
            Core.multiply(div, org.opencv.core.Scalar(255.0), div)

            div.convertTo(result, CvType.CV_8U)

            // Whiten light paper background to eliminate gray shadow patches around text
            Imgproc.threshold(result, result, 215.0, 255.0, Imgproc.THRESH_TRUNC)
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
        var smallV: Mat? = null
        var bgSmall: Mat? = null
        var kernel: Mat? = null
        var bgIllum: Mat? = null
        var vFloat: Mat? = null
        var bgFloat: Mat? = null
        var div: Mat? = null
        var vOut: Mat? = null
        var brightMask: Mat? = null
        var paperMask: Mat? = null
        var mergedHsv: Mat? = null
        val outBGR = Mat()
        
        try {
            hsv = Mat()
            Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
            channels = ArrayList()
            Core.split(hsv, channels)
            
            originalV = channels[2]

            // 1. Calculate mean saturation in non-dark regions (V > 70) to detect if document is colored
            brightMask = Mat()
            Imgproc.threshold(originalV, brightMask, 70.0, 255.0, Imgproc.THRESH_BINARY)
            val meanSatScalar = Core.mean(channels[1], brightMask)
            val meanSat = meanSatScalar.`val`[0]
            val isColoredDocument = meanSat > 14.0
            
            smallV = Mat()
            val maxDim = maxOf(originalV.cols(), originalV.rows())
            val scale = if (maxDim > 0) (320.0 / maxDim).coerceAtMost(0.4) else 0.2
            Imgproc.resize(originalV, smallV, Size(), scale, scale, Imgproc.INTER_AREA)

            bgSmall = Mat()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(21.0, 21.0))
            Imgproc.dilate(smallV, bgSmall, kernel)
            Imgproc.GaussianBlur(bgSmall, bgSmall, Size(31.0, 31.0), 0.0)

            bgIllum = Mat()
            Imgproc.resize(bgSmall, bgIllum, originalV.size(), 0.0, 0.0, Imgproc.INTER_CUBIC)
            
            vFloat = Mat()
            originalV.convertTo(vFloat, CvType.CV_32F)
            
            bgFloat = Mat()
            bgIllum.convertTo(bgFloat, CvType.CV_32F)
            Core.add(bgFloat, org.opencv.core.Scalar(1.0), bgFloat)
            Core.max(bgFloat, org.opencv.core.Scalar(95.0), bgFloat)
            
            div = Mat()
            Core.divide(vFloat, bgFloat, div)
            Core.multiply(div, org.opencv.core.Scalar(255.0), div)
            
            vOut = Mat()
            div.convertTo(vOut, CvType.CV_8U)
            
            if (isColoredDocument) {
                // Colored card/document: Preserve original color background without forced whitening/desaturation
                vOut.convertTo(vOut, -1, 1.05, 0.0)
            } else {
                // White paper document: Whiten light paper background to eliminate residual gray shadow patches
                Imgproc.threshold(vOut, vOut, 215.0, 255.0, Imgproc.THRESH_TRUNC)
                Core.normalize(vOut, vOut, 0.0, 255.0, Core.NORM_MINMAX)

                // Desaturate paper background (V >= 210) so residual paper texture / shadow spots become clean white
                paperMask = Mat()
                Imgproc.threshold(vOut, paperMask, 210.0, 255.0, Imgproc.THRESH_BINARY)
                channels[1].setTo(org.opencv.core.Scalar(0.0), paperMask)
            }

            channels[2] = vOut
            
            mergedHsv = Mat()
            Core.merge(channels, mergedHsv)
            
            Imgproc.cvtColor(mergedHsv, outBGR, Imgproc.COLOR_HSV2BGR)
        } catch (e: Exception) {
            outBGR.release()
            throw e
        } finally {
            brightMask?.release()
            paperMask?.release()
            hsv?.release()
            originalV?.release()
            smallV?.release()
            bgSmall?.release()
            channels?.forEach { 
                if (it != originalV) {
                    it.release()
                }
            }
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
