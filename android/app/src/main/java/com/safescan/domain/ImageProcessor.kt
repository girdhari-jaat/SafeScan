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

    suspend fun cropDocument(bitmap: Bitmap, quad: Quadrilateral): Bitmap = withContext(Dispatchers.Default) {
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

            perspectiveTransform = Imgproc.getPerspectiveTransform(ptsSrc, ptsDst)
            outMat = Mat()
            Imgproc.warpPerspective(src, outMat, perspectiveTransform, Size(maxWidth.toDouble(), maxHeight.toDouble()))

            ScannerDebugLogger.logWarpMatrix(maxWidth, maxHeight)

            val resultBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outMat, resultBitmap)
            ScannerDebugLogger.logExit("ImageProcessor.cropDocument")
            resultBitmap
        } catch (e: Exception) {
            ScannerDebugLogger.logError("Crop", "Failed to crop document in ImageProcessor", e)
            ScannerDebugLogger.logExit("ImageProcessor.cropDocument")
            bitmap
        } finally {
            src?.release()
            ptsSrc?.release()
            ptsDst?.release()
            perspectiveTransform?.release()
            outMat?.release()
        }
    }

    suspend fun apply(bitmap: Bitmap, state: EditorState): Bitmap = withContext(Dispatchers.Default) {
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
            var alpha = state.contrast.toDouble()
            var beta = state.brightness.toDouble() * 255.0 / 100.0 // approximate translation
            
            beta = beta.coerceIn(-60.0, 60.0)
            alpha = alpha.coerceIn(0.5, 3.0)

            dst = Mat()
            src.convertTo(dst, -1, alpha, 0.0)
            Core.add(dst, org.opencv.core.Scalar(beta, beta, beta, beta), dst)
            Core.normalize(dst, dst, 0.0, 255.0, Core.NORM_MINMAX)
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
            Utils.matToBitmap(outMat, resultBitmap)
            
            val duration = System.currentTimeMillis() - startTime
            ScannerDebugLogger.logFilter(state.filter.name, duration)
            ScannerDebugLogger.logExit("ImageProcessor.apply")
            resultBitmap
        } catch (e: Exception) {
            ScannerDebugLogger.logError("Filter", "Failed to apply filters in ImageProcessor", e)
            ScannerDebugLogger.logExit("ImageProcessor.apply")
            bitmap
        } finally {
            src?.release()
            outMat?.release()
            blurred?.release()
            dst?.release()
            hsv?.release()
            channels?.forEach { it.release() }
        }
    }

    suspend fun autoEnhance(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        var src: Mat? = null
        var lab: Mat? = null
        var labChannels: ArrayList<Mat>? = null
        var dilated: Mat? = null
        var kernel: Mat? = null
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
            dilated = Mat()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            Imgproc.dilate(lChannel, dilated, kernel)
            bgIllum = Mat()
            Imgproc.medianBlur(dilated, bgIllum, 21)
            
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
            Utils.matToBitmap(outMat, resultBitmap)
            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        } finally {
            src?.release()
            lab?.release()
            labChannels?.forEach { it.release() }
            dilated?.release()
            kernel?.release()
            bgIllum?.release()
            diff?.release()
            ones?.release()
            bgrChannels?.forEach { it.release() }
            outMat?.release()
        }
    }
}
