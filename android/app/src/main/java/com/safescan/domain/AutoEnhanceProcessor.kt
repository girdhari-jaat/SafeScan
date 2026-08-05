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

            // If it's a B&W / handwriting document, remove chromatic color noise by zeroing out A & B (setting to neutral 128)
            if (isMonochromeDoc) {
                labChannels[1].setTo(Scalar(128.0)) // A channel neutral
                labChannels[2].setTo(Scalar(128.0)) // B channel neutral
            }

            // ----------------------------------------------------
            // 5. Dynamic Parameters
            // ----------------------------------------------------
            val megapixels =
                (srcBgr.cols().toDouble() * srcBgr.rows().toDouble()) /
                        1_000_000.0

            val scaleFactor =
                sqrt(megapixels / 2.0)
                    .coerceAtLeast(0.5)

            var kernelSize =
                round(15.0 * scaleFactor).toInt()

            if (kernelSize % 2 == 0) kernelSize++

            kernelSize = kernelSize.coerceIn(11, 41)

            var blurSize =
                round(kernelSize * 1.4).toInt()

            if (blurSize % 2 == 0) blurSize++

            blurSize = blurSize.coerceIn(15, 51)

            val downscaleFactor = when {
                megapixels > 6.0 -> 0.25
                megapixels > 1.5 -> 0.4
                else -> 1.0
            }

            val targetL =
                if (downscaleFactor < 1.0) {
                    processL = Mat()
                    Imgproc.resize(
                        lChannel,
                        processL,
                        Size(),
                        downscaleFactor,
                        downscaleFactor,
                        Imgproc.INTER_AREA
                    )
                    processL
                } else {
                    lChannel
                }

            var scaledKernel =
                round(kernelSize * downscaleFactor).toInt()

            if (scaledKernel % 2 == 0) scaledKernel++

            scaledKernel =
                scaledKernel.coerceAtLeast(3)

            var scaledBlur =
                round(blurSize * downscaleFactor).toInt()

            if (scaledBlur % 2 == 0) scaledBlur++

            scaledBlur =
                scaledBlur.coerceAtLeast(5)

            // ----------------------------------------------------
            // 6. Shadow Removal / Background Illumination
            // ----------------------------------------------------
            kernel =
                Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    Size(
                        scaledKernel.toDouble(),
                        scaledKernel.toDouble()
                    )
                )

            dilated = Mat()

            Imgproc.dilate(
                targetL,
                dilated,
                kernel
            )

            bgIllumSmall = Mat()

            Imgproc.medianBlur(
                dilated,
                bgIllumSmall,
                scaledBlur
            )

            bgIllum = Mat()

            if (downscaleFactor < 1.0) {
                Imgproc.resize(
                    bgIllumSmall,
                    bgIllum,
                    lChannel.size(),
                    0.0,
                    0.0,
                    Imgproc.INTER_LINEAR
                )
            } else {
                bgIllumSmall.copyTo(bgIllum)
            }

            // ----------------------------------------------------
            // 7. Illumination Normalization
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

            val meanBg =
                Core.mean(bgIllum)
                    .`val`[0]
                    .coerceIn(110.0, 220.0)

            Core.add(
                bgFloat,
                Scalar(1.0),
                bgFloat
            )

            Core.max(
                bgFloat,
                Scalar(110.0),
                bgFloat
            )

            if (isMonochromeDoc) {
                // For B&W / Handwriting paper:
                // Normalize against target paper brightness (250.0) to remove background lighting gradients cleanly
                Core.divide(
                    lFloat,
                    bgFloat,
                    lFloat,
                    250.0
                )

                lFloat.convertTo(
                    lChannel,
                    CvType.CV_8U
                )

                // Contrast stretch for paper background whitening while keeping thin handwriting strokes deep
                // Shift paper highlights (>= 195) to pure white (255)
                lChannel.convertTo(
                    lChannel,
                    -1,
                    1.22,
                    -25.0
                )
            } else {
                // For Color documents / Cards:
                Core.divide(
                    lFloat,
                    bgFloat,
                    lFloat,
                    meanBg * 1.05
                )

                lFloat.convertTo(
                    lChannel,
                    CvType.CV_8U
                )

                // Light paper highlight boost for color documents to reduce gray background tint
                lChannel.convertTo(
                    lChannel,
                    -1,
                    1.12,
                    -12.0
                )

                if (meanBg < 180.0) {
                    clahe =
                        Imgproc.createCLAHE(
                            if (megapixels > 8) 1.8 else 2.0,
                            Size(8.0, 8.0)
                        )

                    clahe.apply(
                        lChannel,
                        lChannel
                    )
                }
            }

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

