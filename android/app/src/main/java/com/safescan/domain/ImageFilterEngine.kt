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
        var clahe: org.opencv.imgproc.CLAHE? = null
        var blurred: Mat? = null
        var enhanced: Mat? = null

        try {
            if (src.empty()) return out

            // BGR -> HSV
            hsv = Mat()
            Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)

            channels = ArrayList()
            Core.split(hsv, channels)

            // -----------------------------
            // Reduce Green/Overall Saturation
            // -----------------------------
            // S channel = 65%
            channels[1].convertTo(
                channels[1],
                -1,
                0.65,
                0.0
            )

            // -----------------------------
            // Improve brightness consistency
            // -----------------------------
            clahe = Imgproc.createCLAHE(
                1.5,
                Size(8.0, 8.0)
            )

            clahe.apply(
                channels[2],
                channels[2]
            )

            // Merge HSV
            Core.merge(channels, hsv)

            // HSV -> BGR
            enhanced = Mat()
            Imgproc.cvtColor(
                hsv,
                enhanced,
                Imgproc.COLOR_HSV2BGR
            )

            // -----------------------------
            // Lightweight Unsharp Mask
            // -----------------------------
            blurred = Mat()
            Imgproc.GaussianBlur(
                enhanced,
                blurred,
                Size(0.0, 0.0),
                1.5
            )

            Core.addWeighted(
                enhanced,
                1.25,
                blurred,
                -0.25,
                0.0,
                out
            )

            // Final RGBA
            Imgproc.cvtColor(
                out,
                out,
                Imgproc.COLOR_BGR2RGBA
            )

        } catch (e: Exception) {

            if (!src.empty()) {
                Imgproc.cvtColor(
                    src,
                    out,
                    Imgproc.COLOR_BGR2RGBA
                )
            }

        } finally {

            hsv?.release()

            channels?.forEach {
                it.release()
            }

            blurred?.release()
            enhanced?.release()

            try {
                clahe?.collectGarbage()
            } catch (_: Exception) {
            }
        }

        return out
    }

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
                    gray = Mat()
                    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                    
                    cleanGray = removeShadowsGray(gray)
                    
                    smoothed = Mat()
                    Imgproc.bilateralFilter(cleanGray, smoothed, 7, 35.0, 35.0)
                    Imgproc.GaussianBlur(smoothed, smoothed, Size(3.0, 3.0), 0.0)
                    
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
                        9.5
                    )
                    
                    Core.addWeighted(thresholded, 0.85, smoothed, 0.15, 0.0, outMat)
                    Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_GRAY2RGBA)
                } finally {
                    safeRelease(gray)
                    safeRelease(cleanGray)
                    safeRelease(smoothed)
                    safeRelease(thresholded)
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
