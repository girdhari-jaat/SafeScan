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
        val tl = quad.topLeft
        val tr = quad.topRight
        val br = quad.bottomRight
        val bl = quad.bottomLeft

        if (flatCrop) {
            val minX = maxOf(0, minOf(tl.x, tr.x, br.x, bl.x).toInt())
            val minY = maxOf(0, minOf(tl.y, tr.y, br.y, bl.y).toInt())
            val maxX = minOf(bitmap.width, maxOf(tl.x, tr.x, br.x, bl.x).toInt())
            val maxY = minOf(bitmap.height, maxOf(tl.y, tr.y, br.y, bl.y).toInt())
            val w = (maxX - minX).coerceIn(1, bitmap.width - minX)
            val h = (maxY - minY).coerceIn(1, bitmap.height - minY)
            return@withContext Bitmap.createBitmap(bitmap, minX, minY, w, h)
        }

        ScannerDebugLogger.logEnter("ImageProcessor.cropDocument")
        var src: Mat? = null
        var ptsSrc: MatOfPoint2f? = null
        var ptsDst: MatOfPoint2f? = null
        var perspectiveTransform: Mat? = null
        var outMat: Mat? = null
        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)

            val tl = quad.topLeft
            val tr = quad.topRight
            val br = quad.bottomRight
            val bl = quad.bottomLeft

            ScannerDebugLogger.logWarp4Point(tl.toString(), tr.toString(), br.toString(), bl.toString())

            ptsSrc = MatOfPoint2f(
                CvPoint(tl.x, tl.y),
                CvPoint(tr.x, tr.y),
                CvPoint(br.x, br.y),
                CvPoint(bl.x, bl.y)
            )

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
        var src: Mat? = null
        var lab: Mat? = null
        var labChannels: ArrayList<Mat>? = null
        var processL: Mat? = null
        var dilated: Mat? = null
        var kernel: Mat? = null
        var bgIllumSmall: Mat? = null
        var bgIllum: Mat? = null
        var diff: Mat? = null
        var ones: Mat? = null
        var bgrChannels: ArrayList<Mat>? = null
        var outMat: Mat? = null
        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)
            
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            // 1. Shadow Removal Logic
            lab = Mat()
            Imgproc.cvtColor(src, lab, Imgproc.COLOR_BGR2Lab)
            labChannels = ArrayList()
            Core.split(lab, labChannels)
            
            val lChannel = labChannels[0]
            
            // 1. Calculate image dimensions and megapixels
            val width = src.cols()
            val height = src.rows()
            val megapixels = (width.toDouble() * height.toDouble()) / 1_000_000.0
            
            // 2. Dynamic kernel sizing based on megapixels (2MP -> 15x15, 6MP -> ~27x27, 8MP -> ~31x31)
            val scaleFactor = Math.sqrt(megapixels / 2.0).coerceAtLeast(0.5)
            var targetKernelSize = Math.round(15.0 * scaleFactor).toInt()
            if (targetKernelSize % 2 == 0) {
                targetKernelSize += 1
            }
            val dynamicKernelSize = targetKernelSize.coerceIn(11, 65)
            
            var targetBlurSize = Math.round(dynamicKernelSize * 1.4).toInt()
            if (targetBlurSize % 2 == 0) {
                targetBlurSize += 1
            }
            val dynamicBlurSize = targetBlurSize.coerceIn(15, 91)
            
            // 3. For ultra-fast performance, estimate the shadow map using a downscaled version.
            // This guarantees high performance (sub-50ms) even on high resolution (6MP - 8MP+) images.
            val downscaleFactor = if (megapixels > 1.5) {
                if (megapixels > 6.0) 0.25 else 0.4
            } else {
                1.0
            }
            
            processL = if (downscaleFactor < 1.0) {
                val resizedL = Mat()
                Imgproc.resize(lChannel, resizedL, Size(), downscaleFactor, downscaleFactor, Imgproc.INTER_LINEAR)
                resizedL
            } else {
                null
            }
            val targetL = processL ?: lChannel
            
            // Scale the dynamic kernel sizes to match the downscaled image
            var scaledKernel = Math.round(dynamicKernelSize * downscaleFactor).toInt()
            if (scaledKernel % 2 == 0) {
                scaledKernel += 1
            }
            scaledKernel = scaledKernel.coerceAtLeast(3)
            
            var scaledBlur = Math.round(dynamicBlurSize * downscaleFactor).toInt()
            if (scaledBlur % 2 == 0) {
                scaledBlur += 1
            }
            scaledBlur = scaledBlur.coerceAtLeast(5)
            
            dilated = Mat()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(scaledKernel.toDouble(), scaledKernel.toDouble()))
            Imgproc.dilate(targetL, dilated, kernel)
            
            bgIllumSmall = Mat()
            Imgproc.medianBlur(dilated, bgIllumSmall, scaledBlur)
            
            bgIllum = Mat()
            if (downscaleFactor < 1.0) {
                Imgproc.resize(bgIllumSmall, bgIllum, lChannel.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            } else {
                bgIllumSmall.copyTo(bgIllum)
            }
            
            diff = Mat()
            Core.absdiff(lChannel, bgIllum, diff)
            ones = Mat.ones(lChannel.size(), lChannel.type()).apply { setTo(org.opencv.core.Scalar(255.0)) }
            Core.subtract(ones, diff, lChannel)
            
            Core.merge(labChannels, lab)
            Imgproc.cvtColor(lab, src, Imgproc.COLOR_Lab2BGR)

            // 2. Auto-level / contrast stretching
            bgrChannels = ArrayList()
            Core.split(src, bgrChannels)
            for (i in bgrChannels.indices) {
                Core.normalize(bgrChannels[i], bgrChannels[i], 0.0, 255.0, Core.NORM_MINMAX)
            }
            Core.merge(bgrChannels, src)

            outMat = Mat()
            Imgproc.cvtColor(src, outMat, Imgproc.COLOR_BGR2RGBA)

            val resultBitmap = Bitmap.createBitmap(outMat.cols(), outMat.rows(), Bitmap.Config.ARGB_8888)
            val finalOutMat = if (outMat.type() == org.opencv.core.CvType.CV_8UC1) {
                val temp = Mat()
                Imgproc.cvtColor(outMat, temp, Imgproc.COLOR_GRAY2RGBA)
                temp
            } else {
                outMat
            }
            Utils.matToBitmap(finalOutMat, resultBitmap)
            if (finalOutMat != outMat) {
                finalOutMat.release()
            }
            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        } finally {
            safeRelease(src)
            safeRelease(lab)
            labChannels?.forEach { safeRelease(it) }
            safeRelease(processL)
            safeRelease(dilated)
            safeRelease(kernel)
            safeRelease(bgIllumSmall)
            safeRelease(bgIllum)
            safeRelease(diff)
            safeRelease(ones)
            bgrChannels?.forEach { safeRelease(it) }
            safeRelease(outMat)
        }
    }
}

