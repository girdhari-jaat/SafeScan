package com.safescan.domain

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import kotlin.math.round
import kotlin.math.sqrt

object AutoEnhanceProcessor {

    private fun safeRelease(mat: Mat?) {
        try {
            mat?.release()
        } catch (_: Exception) {
        }
    }

    suspend fun autoEnhance(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled) return@withContext bitmap

        var srcRgba: Mat? = null
        var srcBgr: Mat? = null
        var grayMat: Mat? = null
        var laplacianMat: Mat? = null
        var hsvMat: Mat? = null
        val hsvChannels = ArrayList<Mat>(3)
        var labMat: Mat? = null
        var processL: Mat? = null
        var dilated: Mat? = null
        var kernel: Mat? = null
        var bgIllumSmall: Mat? = null
        var bgIllum: Mat? = null
        var lFloat: Mat? = null
        var bgFloat: Mat? = null
        var blurredSrc: Mat? = null
        var outRgba: Mat? = null
        var clahe: CLAHE? = null

        var meanStdDevMean: MatOfDouble? = null
        var meanStdDevStd: MatOfDouble? = null
        var satMeanDev: MatOfDouble? = null
        var satStdDev: MatOfDouble? = null

        val labChannels = ArrayList<Mat>(3)

        try {
            // ----------------------------------------------------
            // 1. Bitmap -> BGR
            // ----------------------------------------------------
            srcRgba = Mat()
            srcBgr = Mat()

            Utils.bitmapToMat(bitmap, srcRgba)
            Imgproc.cvtColor(srcRgba, srcBgr, Imgproc.COLOR_RGBA2BGR)

            safeRelease(srcRgba)
            srcRgba = null

            // ----------------------------------------------------
            // 1.5. Gray World White Balance Correction (Fix Warm/Yellow Lighting)
            // ----------------------------------------------------
            val bgrChannels = ArrayList<Mat>(3)
            Core.split(srcBgr, bgrChannels)
            val meanB = Core.mean(bgrChannels[0]).`val`[0]
            val meanG = Core.mean(bgrChannels[1]).`val`[0]
            val meanR = Core.mean(bgrChannels[2]).`val`[0]
            val meanGray = (meanB + meanG + meanR) / 3.0

            if (meanB > 2.0 && meanG > 2.0 && meanR > 2.0) {
                val gainB = (meanGray / meanB).coerceIn(0.65, 1.50)
                val gainG = (meanGray / meanG).coerceIn(0.65, 1.50)
                val gainR = (meanGray / meanR).coerceIn(0.65, 1.50)

                bgrChannels[0].convertTo(bgrChannels[0], -1, gainB, 0.0)
                bgrChannels[1].convertTo(bgrChannels[1], -1, gainG, 0.0)
                bgrChannels[2].convertTo(bgrChannels[2], -1, gainR, 0.0)
                Core.merge(bgrChannels, srcBgr)
            }
            bgrChannels.forEach(::safeRelease)
            bgrChannels.clear()

            // ----------------------------------------------------
            // 2. Blur Detection
            // ----------------------------------------------------
            grayMat = Mat()
            laplacianMat = Mat()
            meanStdDevMean = MatOfDouble()
            meanStdDevStd = MatOfDouble()

            Imgproc.cvtColor(
                srcBgr,
                grayMat,
                Imgproc.COLOR_BGR2GRAY
            )

            Imgproc.Laplacian(
                grayMat,
                laplacianMat,
                CvType.CV_64F
            )

            Core.meanStdDev(
                laplacianMat,
                meanStdDevMean,
                meanStdDevStd
            )

            val stdDev = meanStdDevStd.get(0, 0)[0]
            val blurScore = stdDev * stdDev

            // ----------------------------------------------------
            // 3. Color Saturation & Variance Analysis (B&W vs Color)
            // ----------------------------------------------------
            hsvMat = Mat()
            Imgproc.cvtColor(srcBgr, hsvMat, Imgproc.COLOR_BGR2HSV)
            Core.split(hsvMat, hsvChannels)
            val sChannel = hsvChannels[1]

            satMeanDev = MatOfDouble()
            satStdDev = MatOfDouble()
            Core.meanStdDev(sChannel, satMeanDev, satStdDev)

            val meanSat = satMeanDev.get(0, 0)[0] // 0..255 scale in OpenCV
            val stdSat = satStdDev.get(0, 0)[0]
            val varSat = stdSat * stdSat

            // B&W / Handwriting Paper check: Low saturation mean and low variance
            val isMonochromeDoc = (meanSat < 28.0 && varSat < 200.0) || (meanSat < 18.0)

            // ----------------------------------------------------
            // 4. LAB Conversion
            // ----------------------------------------------------
            labMat = Mat()

            Imgproc.cvtColor(
                srcBgr,
                labMat,
                Imgproc.COLOR_BGR2Lab
            )

            Core.split(
                labMat,
                labChannels
            )

            val lChannel = labChannels[0]

            // ----------------------------------------------------
            // 5. Smooth Illumination Map for Shadow Removal
            // ----------------------------------------------------
            // Fixed small size for consistent, fast, and smooth background estimation
            val maxDim = maxOf(lChannel.cols(), lChannel.rows())
            val scale = if (maxDim > 0) (320.0 / maxDim).coerceAtMost(0.4) else 0.2

            processL = Mat()
            Imgproc.resize(
                lChannel,
                processL,
                Size(),
                scale,
                scale,
                Imgproc.INTER_AREA
            )

            // Dilation to erase text strokes and leave background paper lighting
            kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(21.0, 21.0)
            )

            dilated = Mat()
            Imgproc.dilate(
                processL,
                dilated,
                kernel
            )

            // Heavy Gaussian blur to create a perfectly smooth illumination gradient without splotches or halos
            bgIllumSmall = Mat()
            Imgproc.GaussianBlur(
                dilated,
                bgIllumSmall,
                Size(31.0, 31.0),
                0.0
            )

            bgIllum = Mat()
            Imgproc.resize(
                bgIllumSmall,
                bgIllum,
                lChannel.size(),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR
            )

            // ----------------------------------------------------
            // 6. Illumination Normalization & Division
            // ----------------------------------------------------
            lFloat = Mat()
            bgFloat = Mat()

            lChannel.convertTo(
                lFloat,
                CvType.CV_32F
            )

            bgIllum.convertTo(
                bgFloat,
                CvType.CV_32F
            )

            // Prevent division by zero and clamp dark shadow floor
            Core.add(
                bgFloat,
                Scalar(1.0),
                bgFloat
            )

            Core.max(
                bgFloat,
                Scalar(95.0),
                bgFloat
            )

            // Divide lightness by background illumination map
            Core.divide(
                lFloat,
                bgFloat,
                lFloat,
                255.0
            )

            lFloat.convertTo(
                lChannel,
                CvType.CV_8U
            )

            // ----------------------------------------------------
            // 7. Paper Background Whitening & Ink Contrast Curve
            // ----------------------------------------------------
            // Truncate paper highlights (>= 215) so background becomes pure white
            Imgproc.threshold(
                lChannel,
                lChannel,
                215.0,
                255.0,
                Imgproc.THRESH_TRUNC
            )

            // Stretch lightness range to [0..255]
            Core.normalize(
                lChannel,
                lChannel,
                0.0,
                255.0,
                Core.NORM_MINMAX
            )

            // ----------------------------------------------------
            // 7.5. Selective Paper Background Desaturation
            // ----------------------------------------------------
            // For paper background regions (L >= 210), set A and B channels to neutral 128
            // so residual color casts, paper texture, and shadow spots become clean white.
            // Handwriting ink (L < 210) retains vibrant blue/red/black ink colors.
            val paperMask = Mat()
            Imgproc.threshold(
                lChannel,
                paperMask,
                210.0,
                255.0,
                Imgproc.THRESH_BINARY
            )

            labChannels[1].setTo(Scalar(128.0), paperMask)
            labChannels[2].setTo(Scalar(128.0), paperMask)
            safeRelease(paperMask)

            Core.merge(
                labChannels,
                labMat
            )

            Imgproc.cvtColor(
                labMat,
                srcBgr,
                Imgproc.COLOR_Lab2BGR
            )

            // ----------------------------------------------------
            // 8. Adaptive Sharpen (Fine handwriting strokes & text enhancement)
            // ----------------------------------------------------
            val sharpAlpha = if (isMonochromeDoc) {
                when {
                    blurScore < 60.0 -> 1.70
                    blurScore < 120.0 -> 1.50
                    else -> 1.30
                }
            } else {
                when {
                    blurScore < 60.0 -> 1.60
                    blurScore < 100.0 -> 1.40
                    else -> 1.20
                }
            }

            val sharpBeta = 1.0 - sharpAlpha

            if (blurScore < 180.0 || isMonochromeDoc) {
                blurredSrc = Mat()

                Imgproc.GaussianBlur(
                    srcBgr,
                    blurredSrc,
                    Size(),
                    2.0
                )

                Core.addWeighted(
                    srcBgr,
                    sharpAlpha,
                    blurredSrc,
                    sharpBeta,
                    0.0,
                    srcBgr
                )
            }

            // ----------------------------------------------------
            // 9. Output
            // ----------------------------------------------------
            outRgba = Mat()

            Imgproc.cvtColor(
                srcBgr,
                outRgba,
                Imgproc.COLOR_BGR2RGBA
            )

            val result =
                Bitmap.createBitmap(
                    outRgba.cols(),
                    outRgba.rows(),
                    Bitmap.Config.ARGB_8888
                )

            Utils.matToBitmap(
                outRgba,
                result
            )

            result

        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        } finally {
            labChannels.forEach(::safeRelease)
            labChannels.clear()

            hsvChannels.forEach(::safeRelease)
            hsvChannels.clear()

            safeRelease(srcRgba)
            safeRelease(srcBgr)
            safeRelease(grayMat)
            safeRelease(laplacianMat)
            safeRelease(hsvMat)
            safeRelease(labMat)
            safeRelease(processL)
            safeRelease(dilated)
            safeRelease(kernel)
            safeRelease(bgIllumSmall)
            safeRelease(bgIllum)
            safeRelease(lFloat)
            safeRelease(bgFloat)
            safeRelease(blurredSrc)
            safeRelease(outRgba)

            meanStdDevMean?.release()
            meanStdDevStd?.release()
            satMeanDev?.release()
            satStdDev?.release()

            try {
                clahe?.collectGarbage()
            } catch (_: Exception) {
            }
        }
    }
}

