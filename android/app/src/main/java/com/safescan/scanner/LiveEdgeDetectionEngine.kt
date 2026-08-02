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

                bitmap = imageProxy.toBitmap()
                if (bitmap == null) {
                    ScannerDebugLogger.logExit("LiveEdgeDetectionEngine.process")
                    imageProxy.close()
                    return
                }
                val currentBitmap = bitmap
                if (currentBitmap.config != android.graphics.Bitmap.Config.ARGB_8888 || !currentBitmap.isMutable) {
                    val softwareBmp = currentBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                    if (softwareBmp != null && softwareBmp != currentBitmap) {
                        currentBitmap.recycle()
                        bitmap = softwareBmp
                    }
                }

                val activeBitmap = bitmap ?: return
                val currentSrc = src ?: return
                val currentResized = resized ?: return
                val currentGray = gray ?: return

                Utils.bitmapToMat(activeBitmap, currentSrc)
                val srcW = currentSrc.width()
                val srcH = currentSrc.height()
                if (srcW <= 0 || srcH <= 0) {
                    return
                }
                val resizeRatio = 400.0 / Math.max(srcW, srcH)
                if (resizeRatio < 1.0) {
                    Imgproc.resize(currentSrc, currentResized, Size(srcW * resizeRatio, srcH * resizeRatio))
                } else {
                    currentSrc.copyTo(currentResized)
                }
                Imgproc.cvtColor(currentResized, currentGray, Imgproc.COLOR_RGBA2GRAY)
                sharpness = calculateSharpness(currentGray)

                // Mandatory TFLite Mask Gate: Check if TFLite detects a valid document mask first
                val quad = documentScanner?.detectDocument(activeBitmap, true)
                if (quad != null) {
                    // TFLite mask is valid: Pass 4 corners derived from the TFLite mask
                    foundCorners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
                    Log.d("LiveEdgeDetectionEngine", "Successfully detected document corners using TFLite mask on live feed")
                } else {
                    // TFLite mask is null/empty: Gate triggered! Skip OpenCV edge detection to prevent false positives on table/floor/wall
                    Log.d("LiveEdgeDetectionEngine", "TFLite mask is null or empty. Gating frame: skipping frame and stability count.")
                    foundCorners = null
                }
                
                val currentCorners = foundCorners
                if (currentCorners != null && currentCorners.size == 4) {
                    Log.d("LiveEdgeDetection", "Found 4 document corners: $currentCorners, sharpness=$sharpness")
                    framesWithoutDetection = 0
                    val prev = previousCorners
                    if (prev != null && prev.size == 4) {
                        val maxDistance = currentCorners.mapIndexed { index, p ->
                            Math.hypot(p.x - prev[index].x, p.y - prev[index].y)
                        }.maxOrNull() ?: 0.0
                        
                        if (maxDistance > 200) { 
                            // Large jump, reset smoothing
                            previousCorners = currentCorners
                            foundCorners = currentCorners
                        } else {
                            // Exponential Moving Average for stabilization
                            val smoothedCorners = currentCorners.mapIndexed { index, p ->
                                Point(
                                    prev[index].x + 0.35 * (p.x - prev[index].x),
                                    prev[index].y + 0.35 * (p.y - prev[index].y)
                                )
                            }
                            previousCorners = smoothedCorners
                            foundCorners = smoothedCorners
                        }
                    } else {
                        previousCorners = currentCorners
                    }
                    
                    val validCorners = foundCorners ?: currentCorners
                    if (validCorners.size >= 4) {
                        ScannerDebugLogger.logLiveEdgePoints(
                            validCorners[0].toString(),
                            validCorners[1].toString(),
                            validCorners[2].toString(),
                            validCorners[3].toString()
                        )
                    }
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
        val lap = laplacian ?: return 0.0
        val mean = meanStdDevMean ?: return 0.0
        val stdDev = meanStdDevStdDev ?: return 0.0
        Imgproc.Laplacian(mat, lap, CvType.CV_64F)
        Core.meanStdDev(lap, mean, stdDev)
        val arr = stdDev.get(0, 0)
        if (arr == null || arr.isEmpty()) return 0.0
        val stddevVal = arr[0]
        return stddevVal * stddevVal
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
        val mat4 = tempMatOfPoint4 ?: return false
        tempIntArray8[0] = points[0].x.toInt()
        tempIntArray8[1] = points[0].y.toInt()
        tempIntArray8[2] = points[1].x.toInt()
        tempIntArray8[3] = points[1].y.toInt()
        tempIntArray8[4] = points[2].x.toInt()
        tempIntArray8[5] = points[2].y.toInt()
        tempIntArray8[6] = points[3].x.toInt()
        tempIntArray8[7] = points[3].y.toInt()
        mat4.put(0, 0, tempIntArray8)
        return Imgproc.isContourConvex(mat4)
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
