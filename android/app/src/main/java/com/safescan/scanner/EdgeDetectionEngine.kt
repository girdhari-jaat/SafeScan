package com.safescan.scanner

import android.graphics.Bitmap
import android.util.Log
import com.safescan.domain.model.Point
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class EdgeDetectionEngine {

    companion object {
        private const val TAG = "EdgeDetectionEngine"
        private const val MAX_RESIZE_DIM = 1200.0
    }

    @Synchronized
    fun detectEdgesSafe(bitmap: Bitmap, mode: com.safescan.data.ScannerMode? = null, isManualCrop: Boolean = false): List<Point> {
        return detectEdges(bitmap, mode, isManualCrop) ?: getFallbackQuad(bitmap.width.toDouble(), bitmap.height.toDouble())
    }

    @Synchronized
    fun detectEdges(bitmap: Bitmap, mode: com.safescan.data.ScannerMode? = null, isManualCrop: Boolean = false): List<Point>? {
        if (bitmap.isRecycled) return null

        Log.d(TAG, "Starting Offline modern RANSAC + Outside-In edge detection")
        var src: Mat? = null
        var resized: Mat? = null
        var gray: Mat? = null
        var stretched: Mat? = null
        var blurred: Mat? = null
        var binary: Mat? = null
        var closed: Mat? = null
        var gradX: Mat? = null
        var gradY: Mat? = null

        try {
            src = Mat()
            Utils.bitmapToMat(bitmap, src)

            val resizeRatio = MAX_RESIZE_DIM / Math.max(src.width(), src.height())
            resized = Mat()
            val scaleFactor = if (resizeRatio < 1.0) {
                Imgproc.resize(src, resized, Size(src.width() * resizeRatio, src.height() * resizeRatio), 0.0, 0.0, Imgproc.INTER_CUBIC)
                resizeRatio
            } else {
                src.copyTo(resized)
                1.0
            }

            val sw = resized.width()
            val sh = resized.height()

            gray = Mat()
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)

            // 1. Normalize Contrast (Min-Max Stretch) to make text bounds and physical edges pop
            val minMax = Core.minMaxLoc(gray)
            val minG = minMax.minVal
            val maxG = minMax.maxVal
            val range = if (maxG - minG > 0.0) maxG - minG else 1.0
            val stretchFactor = if (mode == com.safescan.data.ScannerMode.CARD || mode == com.safescan.data.ScannerMode.GRID) 260.0 else 255.0
            
            stretched = Mat()
            gray.convertTo(stretched, -1, stretchFactor / range, -minG * (stretchFactor / range))

            // 2. Adaptive bilateral smoothing parameters
            var blurDiameter = 5
            var blurSigmaI = 20.0
            var blurSigmaS = 10.0
            
            if (mode == com.safescan.data.ScannerMode.CARD || mode == com.safescan.data.ScannerMode.GRID) {
                blurDiameter = 5
                blurSigmaI = 25.0
            } else {
                blurDiameter = 3
                blurSigmaI = 15.0
            }

            blurred = Mat()
            Imgproc.bilateralFilter(stretched, blurred, blurDiameter, blurSigmaI, blurSigmaS)

            // 3. Otsu Dynamic Auto-Thresholding & Morphological Closing
            binary = Mat()
            Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

            closed = Mat()
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, k)
            k.release()

            // 4. Calculate magnitudes using Sobel filters
            gradX = Mat()
            gradY = Mat()
            Imgproc.Sobel(blurred, gradX, org.opencv.core.CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(blurred, gradY, org.opencv.core.CvType.CV_32F, 0, 1, 3)

            // Copy OpenCV Mats to fast Kotlin arrays
            val closedData = ByteArray(sw * sh)
            closed.get(0, 0, closedData)

            val magnitudesX = FloatArray(sw * sh)
            gradX.get(0, 0, magnitudesX)

            val magnitudesY = FloatArray(sw * sh)
            gradY.get(0, 0, magnitudesY)

            for (i in 0 until sw * sh) {
                magnitudesX[i] = Math.abs(magnitudesX[i])
                magnitudesY[i] = Math.abs(magnitudesY[i])
            }

            // 5. Estimate Dynamic Gradient Threshold parameters based on distribution percentiles
            val sampledX = ArrayList<Float>()
            val sampledY = ArrayList<Float>()
            val stride = Math.max(1, (sw * sh) / 600)
            for (i in 0 until sw * sh step stride) {
                if (magnitudesX[i] > 5f) sampledX.add(magnitudesX[i])
                if (magnitudesY[i] > 5f) sampledY.add(magnitudesY[i])
            }

            var thresholdX = 14.0
            if (sampledX.size > 30) {
                sampledX.sort()
                thresholdX = sampledX[(sampledX.size * 0.84).toInt()].toDouble()
            }

            var thresholdY = 14.0
            if (sampledY.size > 30) {
                sampledY.sort()
                thresholdY = sampledY[(sampledY.size * 0.84).toInt()].toDouble()
            }

            val meanBrightness = Core.mean(gray).`val`[0]
            val isLowLight = meanBrightness < 85.0
            if (isLowLight) {
                thresholdX *= 0.70
                thresholdY *= 0.70
            }

            // 6. Scanning strategies
            val isManualCropActive = isManualCrop || mode?.name?.startsWith("MANUAL") == true
            val isCardMode = mode == com.safescan.data.ScannerMode.CARD || mode == com.safescan.data.ScannerMode.GRID

            val borderY = Math.round(sh * 0.02f)
            val borderX = Math.round(sw * 0.02f)

            var finalPts: List<Point>? = null

            val est = RansacHelper.estimateForegroundPercentages(closedData, sw, sh)
            if (est != null) {
                finalPts = RansacHelper.scanTarget(
                    sw, sh,
                    est.leftPct, est.rightPct, est.topPct, est.bottomPct,
                    borderX, borderY,
                    closedData,
                    magnitudesX, magnitudesY,
                    thresholdX, thresholdY,
                    isManualCropActive, isCardMode
                )

                if (finalPts == null) {
                    val paddingX = Math.min(0.06, (est.rightPct - est.leftPct) * 0.08)
                    val paddingY = Math.min(0.06, (est.bottomPct - est.topPct) * 0.08)

                    finalPts = RansacHelper.scanTarget(
                        sw, sh,
                        Math.max(0.01, est.leftPct - paddingX),
                        Math.min(0.99, est.rightPct + paddingX),
                        Math.max(0.01, est.topPct - paddingY),
                        Math.min(0.99, est.bottomPct + paddingY),
                        borderX, borderY,
                        closedData,
                        magnitudesX, magnitudesY,
                        thresholdX, thresholdY,
                        isManualCropActive, isCardMode
                    )
                }
            }

            if (finalPts == null) {
                if (isCardMode) {
                    val steps = listOf(
                        listOf(0.025, 0.975, 0.225, 0.775),
                        listOf(0.075, 0.925, 0.250, 0.750),
                        listOf(0.125, 0.875, 0.275, 0.725),
                        listOf(0.175, 0.825, 0.300, 0.700)
                    )
                    for (step in steps) {
                        finalPts = RansacHelper.scanTarget(
                            sw, sh,
                            step[0], step[1], step[2], step[3],
                            borderX, borderY,
                            closedData,
                            magnitudesX, magnitudesY,
                            thresholdX, thresholdY,
                            isManualCropActive, isCardMode
                        )
                        if (finalPts != null) break
                    }
                } else {
                    val steps = listOf(
                        listOf(0.0525, 0.9475, 0.025, 0.975),
                        listOf(0.098, 0.902, 0.075, 0.925),
                        listOf(0.125, 0.875, 0.125, 0.875)
                    )
                    for (step in steps) {
                        finalPts = RansacHelper.scanTarget(
                            sw, sh,
                            step[0], step[1], step[2], step[3],
                            borderX, borderY,
                            closedData,
                            magnitudesX, magnitudesY,
                            thresholdX, thresholdY,
                            isManualCropActive, isCardMode
                        )
                        if (finalPts != null) break
                    }
                }
            }

            if (finalPts == null && isManualCropActive) {
                finalPts = RansacHelper.findRobustForegroundBoundingBox(closedData, sw, sh)
            }

            if (finalPts == null) return null

            val originalPoints = finalPts.map { Point(it.x / scaleFactor, it.y / scaleFactor) }
            return RansacHelper.orderPoints(originalPoints)

        } finally {
            src?.release(); resized?.release(); gray?.release()
            stretched?.release(); blurred?.release(); binary?.release(); closed?.release()
            gradX?.release(); gradY?.release()
        }
    }

    fun getFallbackQuad(w: Double, h: Double): List<Point> {
        val px = w * 0.05
        val py = h * 0.05
        return listOf(Point(px, py), Point(w - px, py), Point(w - px, h - py), Point(px, h - py))
    }
}
