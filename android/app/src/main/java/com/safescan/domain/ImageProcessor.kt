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
import com.safescan.core.ScannerDebugLogger
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.imgproc.CLAHE
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

object ImageProcessor {

    private fun safeRelease(mat: Mat?) {
        if (mat != null) {
            try {
                mat.release()
            } catch (e: Throwable) {
                // Ignore safe release errors
            }
        }
    }

    suspend fun cropDocument(bitmap: Bitmap, quad: Quadrilateral, flatCrop: Boolean = false): Bitmap = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled) return@withContext bitmap

        ScannerDebugLogger.logEnter("ImageProcessor.cropDocument")
        var src: Mat? = null
        var ptsSrc: MatOfPoint2f? = null
        var ptsDst: MatOfPoint2f? = null
        var perspectiveTransform: Mat? = null
        var outMat: Mat? = null
        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            val bw = bitmap.width.toDouble()
            val bh = bitmap.height.toDouble()
            val tl = CvPoint(quad.topLeft.x.coerceIn(0.0, bw), quad.topLeft.y.coerceIn(0.0, bh))
            val tr = CvPoint(quad.topRight.x.coerceIn(0.0, bw), quad.topRight.y.coerceIn(0.0, bh))
            val br = CvPoint(quad.bottomRight.x.coerceIn(0.0, bw), quad.bottomRight.y.coerceIn(0.0, bh))
            val bl = CvPoint(quad.bottomLeft.x.coerceIn(0.0, bw), quad.bottomLeft.y.coerceIn(0.0, bh))

            ScannerDebugLogger.logWarp4Point(tl.toString(), tr.toString(), br.toString(), bl.toString())

            ptsSrc = MatOfPoint2f(tl, tr, br, bl)

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

            android.util.Log.d("ImageProcessor", "getPerspectiveTransform: ptsSrc type = ${ptsSrc.type()}, ptsDst type = ${ptsDst.type()}")
            perspectiveTransform = Imgproc.getPerspectiveTransform(ptsSrc, ptsDst)
            outMat = Mat()
            android.util.Log.d("ImageProcessor", "warpPerspective: src type = ${src.type()}, perspectiveTransform type = ${perspectiveTransform.type()}")
            Imgproc.warpPerspective(src, outMat, perspectiveTransform, Size(maxWidth.toDouble(), maxHeight.toDouble()))

            ScannerDebugLogger.logWarpMatrix(maxWidth, maxHeight)

            val resultBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
            val finalOutMat = if (outMat.type() == org.opencv.core.CvType.CV_8UC1) {
                val temp = Mat()
                Imgproc.cvtColor(outMat, temp, Imgproc.COLOR_GRAY2RGBA)
                temp
            } else if (outMat.channels() == 3) {
                val temp = Mat()
                Imgproc.cvtColor(outMat, temp, Imgproc.COLOR_BGR2RGBA)
                temp
            } else {
                outMat
            }
            Utils.matToBitmap(finalOutMat, resultBitmap)
            if (finalOutMat != outMat) {
                finalOutMat.release()
            }
            ScannerDebugLogger.logExit("ImageProcessor.cropDocument")
            resultBitmap
        } catch (e: Exception) {
            ScannerDebugLogger.logError("Crop", "Failed to crop document in ImageProcessor", e)
            ScannerDebugLogger.logExit("ImageProcessor.cropDocument")
            bitmap
        } finally {
            safeRelease(src)
            safeRelease(ptsSrc)
            safeRelease(ptsDst)
            safeRelease(perspectiveTransform)
            safeRelease(outMat)
        }
    }

    suspend fun apply(bitmap: Bitmap, state: EditorState): Bitmap = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled) return@withContext bitmap
        ScannerDebugLogger.logEnter("ImageProcessor.apply")
        val startTime = System.currentTimeMillis()
        var src: Mat? = null
        var outMat: Mat? = null
        var blurred: Mat? = null
        var dst: Mat? = null
        var hsv: Mat? = null
        var channels: ArrayList<Mat>? = null
        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)

            // Convert ARGB to BGR for proper processing
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            // Apply Brightness & Contrast
            val alpha = state.contrast.toDouble().coerceIn(0.5, 3.0)
            val brightnessOffset = (state.brightness.toDouble() * 255.0 / 100.0).coerceIn(-100.0, 100.0)
            val beta = 127.5 * (1.0 - alpha) + brightnessOffset

            dst = Mat()
            src.convertTo(dst, -1, alpha, beta)
            dst.copyTo(src)

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
                hsv = Mat()
                Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
                channels = ArrayList()
                Core.split(hsv, channels)
                channels[1].convertTo(channels[1], -1, satFactor, 0.0)
                Core.merge(channels, hsv)
                Imgproc.cvtColor(hsv, src, Imgproc.COLOR_HSV2BGR)
            }

            // Apply Filter
            outMat = ImageFilterEngine.applyFilter(src, state.filter)

            val resultBitmap = Bitmap.createBitmap(outMat.cols(), outMat.rows(), Bitmap.Config.ARGB_8888)
            val finalOutMat = if (outMat.type() == org.opencv.core.CvType.CV_8UC1) {
                val temp = Mat()
                Imgproc.cvtColor(outMat, temp, Imgproc.COLOR_GRAY2RGBA)
                temp
            } else if (outMat.channels() == 3) {
                val temp = Mat()
                Imgproc.cvtColor(outMat, temp, Imgproc.COLOR_BGR2RGBA)
                temp
            } else {
                outMat
            }
            Utils.matToBitmap(finalOutMat, resultBitmap)
            if (finalOutMat != outMat) {
                finalOutMat.release()
            }
            
            val duration = System.currentTimeMillis() - startTime
            ScannerDebugLogger.logFilter(state.filter.name, duration)
            ScannerDebugLogger.logExit("ImageProcessor.apply")
            resultBitmap
        } catch (e: Exception) {
            ScannerDebugLogger.logError("Filter", "Failed to apply filters in ImageProcessor", e)
            ScannerDebugLogger.logExit("ImageProcessor.apply")
            bitmap
        } finally {
            safeRelease(src)
            safeRelease(outMat)
            safeRelease(blurred)
            safeRelease(dst)
            safeRelease(hsv)
            channels?.forEach { safeRelease(it) }
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

        fun safeRelease(mat: Mat?) {
            try {
                mat?.release()
            } catch (_: Exception) {
            }
        }

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

                clahe!!.apply(
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

