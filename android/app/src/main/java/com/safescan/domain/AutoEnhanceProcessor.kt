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
            // 3. LAB Conversion
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
            // 4. Dynamic Parameters
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
            // 5. Shadow Removal
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
            // 6. Illumination Normalization
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

            Core.divide(
                lFloat,
                bgFloat,
                lFloat,
                meanBg
            )

            lFloat.convertTo(
                lChannel,
                CvType.CV_8U
            )

            // ----------------------------------------------------
            // 7. CLAHE
            // ----------------------------------------------------
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
            // 8. Adaptive Sharpen
            // ----------------------------------------------------
            if (blurScore < 150.0) {
                val (alpha, beta) =
                    when {
                        blurScore < 60.0 ->
                            Pair(1.60, -0.60)

                        blurScore < 100.0 ->
                            Pair(1.40, -0.40)

                        else ->
                            Pair(1.20, -0.20)
                    }

                blurredSrc = Mat()

                Imgproc.GaussianBlur(
                    srcBgr,
                    blurredSrc,
                    Size(),
                    2.0
                )

                Core.addWeighted(
                    srcBgr,
                    alpha,
                    blurredSrc,
                    beta,
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

            safeRelease(srcRgba)
            safeRelease(srcBgr)
            safeRelease(grayMat)
            safeRelease(laplacianMat)
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

            try {
                clahe?.collectGarbage()
            } catch (_: Exception) {
            }
        }
    }
}
