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
        var bgIllum: Mat? = null
        var normalizedGray: Mat? = null
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
                Imgproc.resize(src, resized, Size(src.width() * resizeRatio, src.height() * resizeRatio), 0.0, 0.0, Imgproc.INTER_AREA)
                resizeRatio
            } else {
                src.copyTo(resized)
                1.0
            }

            val sw = resized.width()
            val sh = resized.height()

            gray = Mat()
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)

            // Super fast & scale-invariant shadow-flattening
            bgIllum = Mat()
            val smallGray = Mat()
            val smallIllum = Mat()
            val maxDim = Math.max(sw, sh).toDouble()
            val shadowScale = if (maxDim > 300.0) 300.0 / maxDim else 1.0
            val targetW = Math.max(1, (sw * shadowScale).toInt())
            val targetH = Math.max(1, (sh * shadowScale).toInt())
            
            Imgproc.resize(gray, smallGray, Size(targetW.toDouble(), targetH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val illumKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            Imgproc.morphologyEx(smallGray, smallIllum, Imgproc.MORPH_CLOSE, illumKernel)
            illumKernel.release()
            
            Imgproc.resize(smallIllum, bgIllum, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            smallGray.release()
            smallIllum.release()

            normalizedGray = Mat()
            Core.divide(gray, bgIllum, normalizedGray, 255.0)

            // 1. CLAHE (Contrast Limited Adaptive Histogram Equalization) for low-light & high-contrast document edges
            stretched = Mat()
            val clahe = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
            clahe.apply(normalizedGray, stretched)

            // 2. High-speed Gaussian smoothing for edge detection
            blurred = Mat()
            Imgproc.GaussianBlur(stretched, blurred, Size(5.0, 5.0), 0.0)

            // 3. Otsu Dynamic Auto-Thresholding & Morphological Closing
            binary = Mat()
            val otsuVal = Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

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
            val isLowLight = meanBrightness < 95.0
            if (isLowLight) {
                thresholdX *= 0.70
                thresholdY *= 0.70
            }

            // 6. Scanning strategies
            val isManualCropActive = isManualCrop || mode?.name?.startsWith("MANUAL") == true
            val isCardMode = mode == com.safescan.data.ScannerMode.CARD || mode == com.safescan.data.ScannerMode.GRID

            val borderY = Math.round(sh * 0.02f)
            val borderX = Math.round(sw * 0.02f)

            val est = RansacHelper.estimateForegroundPercentages(closedData, sw, sh)

            var finalPts: List<Point>? = detectContourQuad(
                stretched ?: resized,
                closed,
                otsuVal,
                sw,
                sh,
                est,
                isCardMode,
                isManualCropActive
            )

            if (finalPts != null) {
                Log.d(TAG, "OpenCV Contour Quad successfully detected document edges")
            } else {
                Log.d(TAG, "Contour detection yielded no quad, trying RANSAC Outside-In Scan")
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
            src?.release(); resized?.release(); gray?.release(); bgIllum?.release(); normalizedGray?.release()
            stretched?.release(); blurred?.release(); binary?.release(); closed?.release()
            gradX?.release(); gradY?.release()
        }
    }

    private fun detectContourQuad(
        imageMat: Mat,
        closedMat: Mat?,
        otsuVal: Double,
        sw: Int,
        sh: Int,
        est: ForecastPct?,
        isCardMode: Boolean,
        isManualCrop: Boolean
    ): List<Point>? {
        var gray: Mat? = null
        var blurred: Mat? = null
        var edges: Mat? = null
        var hierarchy: Mat? = null
        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        try {
            gray = Mat()
            if (imageMat.channels() > 1) {
                Imgproc.cvtColor(imageMat, gray, Imgproc.COLOR_RGBA2GRAY)
            } else {
                imageMat.copyTo(gray)
            }

            blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

            // Dynamic Canny thresholds tied to Otsu auto-threshold
            val lowThresh = Math.max(20.0, otsuVal * 0.35)
            val highThresh = Math.max(60.0, otsuVal * 0.85)

            edges = Mat()
            Imgproc.Canny(blurred, edges, lowThresh, highThresh)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edges, edges, kernel)
            kernel.release()

            hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            var bestQuad = evaluateContoursForQuad(contours, sw, sh, est, isCardMode, isManualCrop)

            // Fallback pass on closed binary mask if Canny yields no quad
            if (bestQuad == null && closedMat != null) {
                for (c in contours) c.release()
                contours.clear()
                hierarchy.release()
                hierarchy = Mat()
                
                Imgproc.findContours(closedMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                bestQuad = evaluateContoursForQuad(contours, sw, sh, est, isCardMode, isManualCrop)
            }

            return bestQuad
        } catch (e: Exception) {
            Log.e(TAG, "Contour quad detection failed: ${e.message}")
            return null
        } finally {
            gray?.release()
            blurred?.release()
            edges?.release()
            hierarchy?.release()
            for (c in contours) {
                c.release()
            }
        }
    }

    private fun evaluateContoursForQuad(
        contours: List<org.opencv.core.MatOfPoint>,
        sw: Int,
        sh: Int,
        est: ForecastPct?,
        isCardMode: Boolean,
        isManualCrop: Boolean
    ): List<Point>? {
        var bestQuad: List<Point>? = null
        var maxScore = -1.0
        val imgArea = sw.toDouble() * sh.toDouble()
        val minArea = imgArea * 0.04
        val maxInnerArea = imgArea * 0.92

        for (contour in contours) {
            val cArea = Imgproc.contourArea(contour)
            if (cArea < minArea) continue

            var c2f: org.opencv.core.MatOfPoint2f? = null
            var approx: org.opencv.core.MatOfPoint2f? = null
            var hullMat: org.opencv.core.MatOfInt? = null
            var hullPoints: org.opencv.core.MatOfPoint2f? = null
            try {
                c2f = org.opencv.core.MatOfPoint2f()
                contour.convertTo(c2f, org.opencv.core.CvType.CV_32F)

                val peri = Imgproc.arcLength(c2f, true)
                approx = org.opencv.core.MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)

                // If direct approx is not 4 points, attempt Convex Hull approx to smooth shadow/glare noise
                if (approx.total() != 4L) {
                    hullMat = org.opencv.core.MatOfInt()
                    Imgproc.convexHull(contour, hullMat)

                    val hullIndices = hullMat.toArray()
                    val ptBuf = DoubleArray(2)
                    val hPts = ArrayList<org.opencv.core.Point>(hullIndices.size)
                    for (hIdx in 0 until hullIndices.size) {
                        val idx = hullIndices[hIdx]
                        contour.get(idx, 0, ptBuf)
                        hPts.add(org.opencv.core.Point(ptBuf[0], ptBuf[1]))
                    }
                    val hullContour = org.opencv.core.MatOfPoint()
                    hullContour.fromList(hPts)

                    hullPoints = org.opencv.core.MatOfPoint2f()
                    hullContour.convertTo(hullPoints, org.opencv.core.CvType.CV_32F)
                    hullContour.release()

                    val hPeri = Imgproc.arcLength(hullPoints, true)
                    approx.release()
                    approx = org.opencv.core.MatOfPoint2f()
                    Imgproc.approxPolyDP(hullPoints, approx, 0.025 * hPeri, true)
                }

                if (approx.total() == 4L) {
                    val floatBuff = FloatArray(8)
                    approx.get(0, 0, floatBuff)
                    val rawPts = listOf(
                        Point(floatBuff[0].toDouble(), floatBuff[1].toDouble()),
                        Point(floatBuff[2].toDouble(), floatBuff[3].toDouble()),
                        Point(floatBuff[4].toDouble(), floatBuff[5].toDouble()),
                        Point(floatBuff[6].toDouble(), floatBuff[7].toDouble())
                    )
                    val validQuad = RansacHelper.validateAndRepairTier1Quad(
                        rawPts, sw.toDouble(), sh.toDouble(), est, isCardMode, isManualCrop
                    )
                    if (validQuad.size == 4) {
                        val area = RansacHelper.polygonArea(validQuad)

                        val topLen = Math.hypot(validQuad[1].x - validQuad[0].x, validQuad[1].y - validQuad[0].y)
                        val botLen = Math.hypot(validQuad[2].x - validQuad[3].x, validQuad[2].y - validQuad[3].y)
                        val leftLen = Math.hypot(validQuad[3].x - validQuad[0].x, validQuad[3].y - validQuad[0].y)
                        val rightLen = Math.hypot(validQuad[2].x - validQuad[1].x, validQuad[2].y - validQuad[1].y)

                        val avgW = (topLen + botLen) / 2.0
                        val avgH = (leftLen + rightLen) / 2.0
                        val aspect = Math.max(avgW, avgH) / Math.max(1e-3, Math.min(avgW, avgH))

                        // Check if candidate quad touches outer frame borders (false outer line in low light)
                        var borderTouchCount = 0
                        val marginX = sw * 0.025
                        val marginY = sh * 0.025
                        for (p in validQuad) {
                            if (p.x <= marginX || p.x >= sw - marginX || p.y <= marginY || p.y >= sh - marginY) {
                                borderTouchCount++
                            }
                        }

                        // Score calculation: Give penalty if quad is an outer border line, fills >92% of screen, or is square (choras)
                        var score = area
                        
                        if (aspect in 1.25..1.75) {
                            score *= 1.35 // Boost ideal document ratio range (A4, Letter, ID Card)
                        } else if (aspect < 1.18) {
                            score *= 0.15 // Heavy penalty for near-square (choras) shapes
                        }

                        if (borderTouchCount >= 3 || area > maxInnerArea) {
                            score *= 0.35 // Penalize outer background/frame lines
                        }

                        if (score > maxScore) {
                            maxScore = score
                            bestQuad = validQuad
                        }
                    }
                }
            } finally {
                c2f?.release()
                approx?.release()
                hullMat?.release()
                hullPoints?.release()
            }
        }
        return bestQuad
    }

    fun getFallbackQuad(w: Double, h: Double): List<Point> {
        val px = w * 0.05
        val py = h * 0.05
        return listOf(Point(px, py), Point(w - px, py), Point(w - px, h - py), Point(px, h - py))
    }
}
