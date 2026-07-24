package com.safescan.scanner

import android.util.Log
import androidx.camera.core.ImageProxy
import com.safescan.domain.model.Point
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.core.CvType
import org.opencv.core.MatOfDouble
import org.opencv.core.Core
import com.safescan.core.ScannerDebugLogger

class LiveEdgeDetectionEngine {
    
    private val processLock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var isReleased = false

    // Pre-allocated Mats for performance to avoid GC overhead during live preview (initialized lazily)
    private var src: Mat? = null
    private var resized: Mat? = null
    private var gray: Mat? = null
    private var blurred: Mat? = null
    private var edges: Mat? = null
    private var hierarchy: Mat? = null
    private var laplacian: Mat? = null
    private var meanStdDevMean: MatOfDouble? = null
    private var meanStdDevStdDev: MatOfDouble? = null
    private var tempContour2f: MatOfPoint2f? = null
    private var tempApprox: MatOfPoint2f? = null
    private var tempMatOfPoint4: MatOfPoint? = null
    private val tempIntArray8 = IntArray(8)
    private val tempFloatArray8 = FloatArray(8)
    private var contourIntBuffer = IntArray(1024)
    private val reusableCvPoints = Array<org.opencv.core.Point>(4) { org.opencv.core.Point() }
    private var yData: ByteArray? = null
    
    private val reusablePointsArray = Array<Point?>(4) { null }
    private var previousCorners: List<Point>? = null
    private var framesWithoutDetection = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastLoggedProcessorType: String? = null
    
    private var kernel5: Mat? = null
    private var kernel7: Mat? = null

    private fun initMatsIfNeeded(width: Int, height: Int) {
        if (src == null || lastWidth != width || lastHeight != height || isReleased) {
            isReleased = false
            src?.release()
            resized?.release()
            gray?.release()
            blurred?.release()
            edges?.release()
            hierarchy?.release()
            kernel5?.release()
            kernel7?.release()
            laplacian?.release()
            meanStdDevMean?.release()
            meanStdDevStdDev?.release()
            tempContour2f?.release()
            tempApprox?.release()
            tempMatOfPoint4?.release()

            src = Mat()
            resized = Mat()
            gray = Mat()
            blurred = Mat()
            edges = Mat()
            hierarchy = Mat()
            kernel5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            kernel7 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            laplacian = Mat()
            meanStdDevMean = MatOfDouble()
            meanStdDevStdDev = MatOfDouble()
            tempContour2f = MatOfPoint2f()
            tempApprox = MatOfPoint2f()
            tempMatOfPoint4 = MatOfPoint(org.opencv.core.Point(), org.opencv.core.Point(), org.opencv.core.Point(), org.opencv.core.Point())
            
            lastWidth = width
            lastHeight = height
        }
    }

    fun release() {
        isReleased = true
        processLock.lock()
        try {
            src?.release()
            src = null
            resized?.release()
            resized = null
            gray?.release()
            gray = null
            blurred?.release()
            blurred = null
            edges?.release()
            edges = null
            hierarchy?.release()
            hierarchy = null
            kernel5?.release()
            kernel5 = null
            kernel7?.release()
            kernel7 = null
            laplacian?.release()
            laplacian = null
            meanStdDevMean?.release()
            meanStdDevMean = null
            meanStdDevStdDev?.release()
            meanStdDevStdDev = null
            tempContour2f?.release()
            tempContour2f = null
            tempApprox?.release()
            tempApprox = null
            tempMatOfPoint4?.release()
            tempMatOfPoint4 = null
            Log.d("LiveEdgeDetectionEngine", "Mats released successfully")
        } catch (e: Throwable) {
            Log.e("LiveEdgeDetectionEngine", "Failed to release Mats", e)
        } finally {
            processLock.unlock()
        }
    }

    fun process(
        imageProxy: ImageProxy, 
        documentScanner: DocumentScanner?, 
        engineType: ScannerEngineType, 
        mode: com.safescan.data.ScannerMode? = null,
        onResult: (List<Point>?, Double) -> Unit
    ) {
        if (!processLock.tryLock()) {
            imageProxy.close()
            return
        }
        try {
            if (isReleased) {
                isReleased = false
            }
            ScannerDebugLogger.logEnter("LiveEdgeDetectionEngine.process")
            initMatsIfNeeded(imageProxy.width, imageProxy.height)
            Log.d("LiveEdgeDetection", "Processing frame: ${imageProxy.width}x${imageProxy.height}, engine=$engineType, mode=$mode")
            var bitmap: android.graphics.Bitmap? = null
            val contours = ArrayList<MatOfPoint>()
            var actualGray: Mat? = null
            try {
                var foundCorners: List<Point>? = null
                var sharpness = 0.0

                if (engineType == ScannerEngineType.LOCAL_ML) {
                    val processorType = if (documentScanner?.isGpuAccelerated == true) "TFLite (GPU)" else "TFLite (CPU)"
                    if (processorType != lastLoggedProcessorType) {
                        Log.i("LiveEdgeDetectionEngine", "Processing frame using: $processorType")
                        com.safescan.core.DiagnosticsLogger.info("Live edge detection active: running on $processorType")
                        lastLoggedProcessorType = processorType
                    }
                    
                    bitmap = imageProxy.toBitmap()
                    if (bitmap == null) {
                        ScannerDebugLogger.logExit("LiveEdgeDetectionEngine.process")
                        imageProxy.close()
                        return
                    }
                    if (bitmap!!.config != android.graphics.Bitmap.Config.ARGB_8888 || !bitmap!!.isMutable) {
                        val softwareBmp = bitmap!!.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                        if (softwareBmp != null && softwareBmp != bitmap) {
                            bitmap!!.recycle()
                            bitmap = softwareBmp
                        }
                    }
                    Utils.bitmapToMat(bitmap, src!!)
                    val resizeRatio = 400.0 / Math.max(src!!.width(), src!!.height())
                    if (resizeRatio < 1.0) {
                        Imgproc.resize(src!!, resized!!, Size(src!!.width() * resizeRatio, src!!.height() * resizeRatio))
                    } else {
                        src!!.copyTo(resized!!)
                    }
                    Imgproc.cvtColor(resized!!, gray!!, Imgproc.COLOR_RGBA2GRAY)
                    
                    sharpness = calculateSharpness(gray!!)
                    
                    if (documentScanner != null) {
                        try {
                            val quad = documentScanner.detectDocument(bitmap, true)
                            if (quad != null) {
                                foundCorners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
                                Log.d("LiveEdgeDetectionEngine", "Successfully detected document corners using TFLite ML on live feed")
                            }
                        } catch (e: Throwable) {
                            Log.e("LiveEdgeDetectionEngine", "TFLite ML detection failed in live feed", e)
                        }
                    }
                } else {
                    val processorType = "OpenCV (Fallback)"
                    if (processorType != lastLoggedProcessorType) {
                        Log.i("LiveEdgeDetectionEngine", "Processing frame using: $processorType")
                        lastLoggedProcessorType = processorType
                    }
                    // Extremely fast path: bypass Bitmap conversion completely for OpenCV Engine
                    val yPlane = imageProxy.planes[0]
                    val yBuffer = yPlane.buffer
                    val yRowStride = yPlane.rowStride
                    val width = imageProxy.width
                    val height = imageProxy.height

                    if (src == null || src!!.rows() != height || src!!.cols() != yRowStride || src!!.type() != CvType.CV_8UC1) {
                        src?.release()
                        src = Mat(height, yRowStride, CvType.CV_8UC1)
                    }

                    val remaining = yBuffer.remaining()
                    if (yData == null || yData!!.size != remaining) {
                        yData = ByteArray(remaining)
                    }
                    yBuffer.get(yData!!)
                    src!!.put(0, 0, yData!!)

                    // Submat to crop out padding bytes if rowStride > width
                    actualGray = if (yRowStride > width) {
                        src!!.colRange(0, width)
                    } else {
                        src!!
                    }

                    val resizeRatio = 400.0 / Math.max(actualGray.width(), actualGray.height())
                    if (resizeRatio < 1.0) {
                        Imgproc.resize(actualGray, resized!!, Size(actualGray.width() * resizeRatio, actualGray.height() * resizeRatio))
                    } else {
                        actualGray.copyTo(resized!!)
                    }

                    sharpness = calculateSharpness(resized!!)

                    // Gaussian blur is faster for live preview and provides good edge smoothing
                    val blurSize = when (mode) {
                        com.safescan.data.ScannerMode.CARD, com.safescan.data.ScannerMode.GRID -> Size(5.0, 5.0)
                        else -> Size(5.0, 5.0)
                    }
                    Imgproc.GaussianBlur(resized!!, blurred!!, blurSize, 0.0)
                    
                    val (lowThresh, highThresh) = when (mode) {
                        com.safescan.data.ScannerMode.CARD, com.safescan.data.ScannerMode.GRID -> Pair(50.0, 125.0)
                        else -> Pair(40.0, 100.0)
                    }
                    
                    Imgproc.Canny(blurred!!, edges!!, lowThresh, highThresh) 
                    
                    val morphKernel = when (mode) {
                        com.safescan.data.ScannerMode.CARD, com.safescan.data.ScannerMode.GRID -> kernel7!!
                        else -> kernel5!!
                    }
                    Imgproc.morphologyEx(edges!!, edges!!, Imgproc.MORPH_CLOSE, morphKernel)
                    
                    // RETR_EXTERNAL is faster and we only care about the outermost document contour
                    Imgproc.findContours(edges!!, contours, hierarchy!!, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                    
                    ScannerDebugLogger.logLiveEdge(contours.size)
                    
                    val maxArea = resized!!.width() * resized!!.height()
                    val minArea = when (mode) {
                        com.safescan.data.ScannerMode.CARD, com.safescan.data.ScannerMode.GRID -> maxArea * 0.06
                        else -> maxArea * 0.12
                    }
                    
                    var bestArea = 0.0
                    var bestCorners: List<Point>? = null

                    for (contour in contours) {
                        val area = Imgproc.contourArea(contour)
                        if (area < minArea || area < bestArea) continue
                        
                        try {
                            tempContour2f?.let { contour.convertTo(it, CvType.CV_32F) }
                            val peri = tempContour2f?.let { Imgproc.arcLength(it, true) } ?: 0.0
                            
                            var currentCorners: List<Point>? = null
                            var approxSuccess = false

                            // Try different epsilon approximations to get a clean quadrilateral
                            val epsilons = doubleArrayOf(0.015, 0.02, 0.03, 0.04)
                            for (epsFactor in epsilons) {
                                if (tempContour2f != null && tempApprox != null) {
                                    Imgproc.approxPolyDP(tempContour2f, tempApprox, epsFactor * peri, true)
                                    if (tempApprox!!.total() == 4L) {
                                        tempApprox!!.get(0, 0, tempFloatArray8)
                                        reusableCvPoints[0].x = tempFloatArray8[0].toDouble(); reusableCvPoints[0].y = tempFloatArray8[1].toDouble()
                                        reusableCvPoints[1].x = tempFloatArray8[2].toDouble(); reusableCvPoints[1].y = tempFloatArray8[3].toDouble()
                                        reusableCvPoints[2].x = tempFloatArray8[4].toDouble(); reusableCvPoints[2].y = tempFloatArray8[5].toDouble()
                                        reusableCvPoints[3].x = tempFloatArray8[6].toDouble(); reusableCvPoints[3].y = tempFloatArray8[7].toDouble()
                                        if (isConvexPoints(reusableCvPoints) && getMaxCosinePoints(reusableCvPoints) < 0.4) {
                                            reusablePointsArray[0] = Point(tempFloatArray8[0] / resizeRatio, tempFloatArray8[1] / resizeRatio)
                                            reusablePointsArray[1] = Point(tempFloatArray8[2] / resizeRatio, tempFloatArray8[3] / resizeRatio)
                                            reusablePointsArray[2] = Point(tempFloatArray8[4] / resizeRatio, tempFloatArray8[5] / resizeRatio)
                                            reusablePointsArray[3] = Point(tempFloatArray8[6] / resizeRatio, tempFloatArray8[7] / resizeRatio)
                                            currentCorners = orderPoints(reusablePointsArray)
                                            approxSuccess = true
                                            break
                                        }
                                    }
                                }
                            }
                            
                            // FALLBACK 1: Extreme Projection Points
                            if (!approxSuccess) {
                                val extremePointsArray = getExtremePointsArray(contour)
                                if (extremePointsArray != null && isConvexPoints(extremePointsArray) && getMaxCosinePoints(extremePointsArray) < 0.4) {
                                    reusablePointsArray[0] = Point(extremePointsArray[0].x / resizeRatio, extremePointsArray[0].y / resizeRatio)
                                    reusablePointsArray[1] = Point(extremePointsArray[1].x / resizeRatio, extremePointsArray[1].y / resizeRatio)
                                    reusablePointsArray[2] = Point(extremePointsArray[2].x / resizeRatio, extremePointsArray[2].y / resizeRatio)
                                    reusablePointsArray[3] = Point(extremePointsArray[3].x / resizeRatio, extremePointsArray[3].y / resizeRatio)
                                    currentCorners = orderPoints(reusablePointsArray)
                                    approxSuccess = true
                                }
                            }

                            // FALLBACK 2: Convex Hull & Bounding Box fallback to prevent frame drops in low contrast
                            if (!approxSuccess && currentCorners == null) {
                                val hullInts = org.opencv.core.MatOfInt()
                                val hullPointsMat = MatOfPoint()
                                try {
                                    Imgproc.convexHull(contour, hullInts)
                                    val totalHull = hullInts.total().toInt()
                                    if (totalHull > 0) {
                                        val hullIndices = IntArray(totalHull)
                                        hullInts.get(0, 0, hullIndices)
                                        
                                        val totalContour = contour.total().toInt()
                                        val contourInts = IntArray(totalContour * 2)
                                        contour.get(0, 0, contourInts)
                                        
                                        val hullPtsInts = IntArray(totalHull * 2)
                                        for (idx in 0 until totalHull) {
                                            val cIdx = hullIndices[idx]
                                            hullPtsInts[idx * 2] = contourInts[cIdx * 2]
                                            hullPtsInts[idx * 2 + 1] = contourInts[cIdx * 2 + 1]
                                        }
                                        hullPointsMat.put(0, 0, hullPtsInts)
                                        
                                        val hull32f = MatOfPoint2f()
                                        val approxHull32f = MatOfPoint2f()
                                        try {
                                            hullPointsMat.convertTo(hull32f, CvType.CV_32F)
                                            val hullPeri = Imgproc.arcLength(hull32f, true)
                                            for (epsFactor in doubleArrayOf(0.015, 0.02, 0.03, 0.04)) {
                                                Imgproc.approxPolyDP(hull32f, approxHull32f, epsFactor * hullPeri, true)
                                                if (approxHull32f.total() == 4L) {
                                                    val floatBuff = FloatArray(8)
                                                    approxHull32f.get(0, 0, floatBuff)
                                                    reusablePointsArray[0] = Point(floatBuff[0].toDouble() / resizeRatio, floatBuff[1].toDouble() / resizeRatio)
                                                    reusablePointsArray[1] = Point(floatBuff[2].toDouble() / resizeRatio, floatBuff[3].toDouble() / resizeRatio)
                                                    reusablePointsArray[2] = Point(floatBuff[4].toDouble() / resizeRatio, floatBuff[5].toDouble() / resizeRatio)
                                                    reusablePointsArray[3] = Point(floatBuff[6].toDouble() / resizeRatio, floatBuff[7].toDouble() / resizeRatio)
                                                    currentCorners = orderPoints(reusablePointsArray)
                                                    approxSuccess = true
                                                    break
                                                }
                                            }
                                        } finally {
                                            hull32f.release()
                                            approxHull32f.release()
                                        }
                                    }
                                } catch (hullEx: Throwable) {
                                    Log.e("LiveEdgeDetectionEngine", "Hull fallback computation failed", hullEx)
                                } finally {
                                    hullInts.release()
                                    hullPointsMat.release()
                                }


                            }

                            if (currentCorners != null) {
                                bestArea = area
                                bestCorners = currentCorners
                            }

                        } catch (e: Throwable) {
                            Log.e("LiveEdgeDetectionEngine", "Contour loop processing error", e)
                        }
                    }
                    
                    if (bestCorners != null) {
                        foundCorners = bestCorners
                        ScannerDebugLogger.logLiveEdgeArea(bestArea, (bestArea / maxArea.toDouble()) * 100.0)
                    }
                }
                
                if (foundCorners != null) {
                    Log.d("LiveEdgeDetection", "Found 4 document corners: $foundCorners, sharpness=$sharpness")
                    framesWithoutDetection = 0
                    if (previousCorners != null) {
                        val maxDistance = foundCorners!!.mapIndexed { index, p ->
                            Math.hypot(p.x - previousCorners!![index].x, p.y - previousCorners!![index].y)
                        }.maxOrNull() ?: 0.0
                        
                        if (maxDistance > 200) { 
                            // Large jump, reset smoothing
                            previousCorners = foundCorners
                        } else {
                            // Exponential Moving Average for stabilization
                            foundCorners = foundCorners!!.mapIndexed { index, p ->
                                Point(
                                    previousCorners!![index].x + 0.35 * (p.x - previousCorners!![index].x),
                                    previousCorners!![index].y + 0.35 * (p.y - previousCorners!![index].y)
                                )
                            }
                            previousCorners = foundCorners
                        }
                    } else {
                        previousCorners = foundCorners
                    }
                    
                    ScannerDebugLogger.logLiveEdgePoints(
                        foundCorners!![0].toString(),
                        foundCorners!![1].toString(),
                        foundCorners!![2].toString(),
                        foundCorners!![3].toString()
                    )
                } else {
                    framesWithoutDetection++
                    if (framesWithoutDetection < 5 && previousCorners != null) {
                        // Keep showing previous corners for a few frames to prevent flickering
                        foundCorners = previousCorners
                    } else {
                        previousCorners = null
                    }
                }
                
                onResult(foundCorners, sharpness)
            } catch (e: Throwable) {
                ScannerDebugLogger.logError("LiveEdge", "Live edge detection processing error", e)
            } finally {
                bitmap?.recycle()
                // Cleanup dynamically allocated actualGray if it was a submat
                if (actualGray != null && actualGray != src) {
                    actualGray.release()
                }
                for (contour in contours) {
                    try {
                        contour.release()
                    } catch (ce: Throwable) {
                        Log.e("LiveEdgeDetectionEngine", "Failed to release individual contour", ce)
                    }
                }
                // FIX: FINAL LEAK
                try {
                    imageProxy.close()
                } catch (ipe: Throwable) {
                    Log.e("LiveEdgeDetectionEngine", "Failed to close ImageProxy", ipe)
                }
                ScannerDebugLogger.logExit("LiveEdgeDetectionEngine.process")
            }
        } finally {
            processLock.unlock()
        }
    }

    private fun calculateSharpness(mat: Mat): Double {
        Imgproc.Laplacian(mat, laplacian!!, CvType.CV_64F)
        Core.meanStdDev(laplacian!!, meanStdDevMean!!, meanStdDevStdDev!!)
        val stddevVal = meanStdDevStdDev!!.get(0, 0)[0]
        val variance = stddevVal * stddevVal
        return variance
    }

    private fun getExtremePointsArray(contour: MatOfPoint): Array<org.opencv.core.Point>? {
        val total = contour.total().toInt()
        if (total == 0) return null
        val requiredSize = total * 2
        if (contourIntBuffer.size < requiredSize) {
            contourIntBuffer = IntArray(requiredSize)
        }
        contour.get(0, 0, contourIntBuffer)

        var minSum = Double.MAX_VALUE
        var maxSum = -Double.MAX_VALUE
        var minDiff = Double.MAX_VALUE
        var maxDiff = -Double.MAX_VALUE

        var tlX = 0.0; var tlY = 0.0
        var trX = 0.0; var trY = 0.0
        var brX = 0.0; var brY = 0.0
        var blX = 0.0; var blY = 0.0

        for (i in 0 until requiredSize step 2) {
            val px = contourIntBuffer[i].toDouble()
            val py = contourIntBuffer[i + 1].toDouble()
            val sum = px + py
            val diff = py - px

            if (sum < minSum) { minSum = sum; tlX = px; tlY = py }
            if (sum > maxSum) { maxSum = sum; brX = px; brY = py }
            if (diff < minDiff) { minDiff = diff; trX = px; trY = py }
            if (diff > maxDiff) { maxDiff = diff; blX = px; blY = py }
        }

        reusableCvPoints[0].x = tlX; reusableCvPoints[0].y = tlY
        reusableCvPoints[1].x = trX; reusableCvPoints[1].y = trY
        reusableCvPoints[2].x = brX; reusableCvPoints[2].y = brY
        reusableCvPoints[3].x = blX; reusableCvPoints[3].y = blY

        return reusableCvPoints
    }

    private fun isConvexPoints(points: Array<org.opencv.core.Point>): Boolean {
        if (points.size != 4) return false
        tempIntArray8[0] = points[0].x.toInt()
        tempIntArray8[1] = points[0].y.toInt()
        tempIntArray8[2] = points[1].x.toInt()
        tempIntArray8[3] = points[1].y.toInt()
        tempIntArray8[4] = points[2].x.toInt()
        tempIntArray8[5] = points[2].y.toInt()
        tempIntArray8[6] = points[3].x.toInt()
        tempIntArray8[7] = points[3].y.toInt()
        tempMatOfPoint4!!.put(0, 0, tempIntArray8)
        return Imgproc.isContourConvex(tempMatOfPoint4!!)
    }

    private fun getMaxCosinePoints(points: Array<org.opencv.core.Point>): Double {
        var maxCosine = 0.0
        for (i in 2..5) {
            val pt1 = points[i % 4]
            val pt2 = points[(i - 2) % 4]
            val pt0 = points[(i - 1) % 4]
            val cosine = Math.abs(angle(pt1, pt2, pt0))
            maxCosine = Math.max(maxCosine, cosine)
        }
        return maxCosine
    }

    private fun orderPoints(pts: Array<Point?>): List<Point>? {
        var minSum = Double.MAX_VALUE
        var maxSum = -Double.MAX_VALUE
        var minDiff = Double.MAX_VALUE
        var maxDiff = -Double.MAX_VALUE

        var tl: Point? = null
        var br: Point? = null
        var tr: Point? = null
        var bl: Point? = null

        for (pt in pts) {
            if (pt == null) return null
            val sum = pt.x + pt.y
            val diff = pt.y - pt.x

            if (sum < minSum) { minSum = sum; tl = pt }
            if (sum > maxSum) { maxSum = sum; br = pt }
            if (diff < minDiff) { minDiff = diff; tr = pt }
            if (diff > maxDiff) { maxDiff = diff; bl = pt }
        }

        if (tl == null || tr == null || br == null || bl == null) return null

        return listOf(tl, tr, br, bl)
    }

    private fun angle(pt1: org.opencv.core.Point, pt2: org.opencv.core.Point, pt0: org.opencv.core.Point): Double {
        val dx1 = pt1.x - pt0.x
        val dy1 = pt1.y - pt0.y
        val dx2 = pt2.x - pt0.x
        val dy2 = pt2.y - pt0.y
        return (dx1 * dx2 + dy1 * dy2) / Math.sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10)
    }
}
