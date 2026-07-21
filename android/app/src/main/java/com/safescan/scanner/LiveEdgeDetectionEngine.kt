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
    
    private val lock = Any()

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
    private var yData: ByteArray? = null
    
    private var previousCorners: List<Point>? = null
    private var framesWithoutDetection = 0
    private var lastWidth = 0
    private var lastHeight = 0
    
    private var kernel5: Mat? = null
    private var kernel7: Mat? = null

    private fun initMatsIfNeeded(width: Int, height: Int) {
        if (src == null || lastWidth != width || lastHeight != height) {
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
        synchronized(lock) {
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
                Log.d("LiveEdgeDetectionEngine", "Mats released successfully")
            } catch (e: Throwable) {
                Log.e("LiveEdgeDetectionEngine", "Failed to release Mats", e)
            }
        }
    }

    fun process(
        imageProxy: ImageProxy, 
        documentScanner: DocumentScanner?, 
        engineType: ScannerEngineType, 
        mode: com.safescan.data.ScannerMode? = null,
        onResult: (List<Point>?, Double) -> Unit
    ) {
        synchronized(lock) {
            ScannerDebugLogger.logEnter("LiveEdgeDetectionEngine.process")
            initMatsIfNeeded(imageProxy.width, imageProxy.height)
            var bitmap: android.graphics.Bitmap? = null
            val contours = ArrayList<MatOfPoint>()
            var actualGray: Mat? = null
            try {
                var foundCorners: List<Point>? = null
                var sharpness = 0.0

                if (engineType == ScannerEngineType.LOCAL_ML) {
                    bitmap = imageProxy.toBitmap()
                    if (bitmap == null) {
                        ScannerDebugLogger.logExit("LiveEdgeDetectionEngine.process")
                        return@synchronized
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
                        val submat = src!!.colRange(0, width)
                        if (!submat.isContinuous) {
                            val clone = submat.clone()
                            submat.release()
                            clone
                        } else {
                            submat
                        }
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
                    contours.sortByDescending { Imgproc.contourArea(it) }
                    
                    val maxArea = resized!!.width() * resized!!.height()
                    val minArea = when (mode) {
                        com.safescan.data.ScannerMode.CARD, com.safescan.data.ScannerMode.GRID -> maxArea * 0.06 // Card/Grid must occupy at least 6% of the frame
                        else -> maxArea * 0.12 // Document must occupy at least 12% of the frame
                    }
                    
                    for (contour in contours) {
                        val area = Imgproc.contourArea(contour)
                        if (area < minArea) break // since they are sorted, we can stop early
                        
                        try {
                            tempContour2f?.let { contour.convertTo(it, CvType.CV_32F) }
                            val peri = tempContour2f?.let { Imgproc.arcLength(it, true) } ?: 0.0
                            
                            var approxSuccess = false
                            // Try different epsilon approximations to get a clean quadrilateral
                            for (epsFactor in listOf(0.015, 0.02, 0.03, 0.04)) {
                                if (tempContour2f != null && tempApprox != null) {
                                    Imgproc.approxPolyDP(tempContour2f, tempApprox, epsFactor * peri, true)
                                    if (tempApprox!!.total() == 4L) {
                                        val approxArray = tempApprox!!.toArray()
                                        if (isConvexPoints(approxArray) && getMaxCosinePoints(approxArray) < 0.4) {
                                            val approxPoints = approxArray.map { Point(it.x / resizeRatio, it.y / resizeRatio) }
                                            foundCorners = orderPoints(approxPoints)
                                            approxSuccess = true
                                            break
                                        }
                                    }
                                }
                            }
                            
                            // FALLBACK: If approxPolyDP failed but it's a large contour, use extreme points
                            if (!approxSuccess) {
                                val extremePointsArray = getExtremePointsArray(contour)
                                if (extremePointsArray != null && isConvexPoints(extremePointsArray) && getMaxCosinePoints(extremePointsArray) < 0.4) {
                                    val extremePoints = extremePointsArray.map { Point(it.x / resizeRatio, it.y / resizeRatio) }
                                    foundCorners = orderPoints(extremePoints)
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e("LiveEdgeDetectionEngine", "Contour loop processing error", e)
                        }
                        
                        if (foundCorners != null) {
                            ScannerDebugLogger.logLiveEdgeArea(area, (area.toDouble() / maxArea.toDouble()) * 100.0)
                            break // Found our document
                        }
                    }
                }
                
                if (foundCorners != null) {
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
        }
    }

    private fun calculateSharpness(mat: Mat): Double {
        if (laplacian == null) initMatsIfNeeded()
        Imgproc.Laplacian(mat, laplacian!!, CvType.CV_64F)
        Core.meanStdDev(laplacian!!, meanStdDevMean!!, meanStdDevStdDev!!)
        val stddevVal = meanStdDevStdDev!!.get(0, 0)[0]
        val variance = stddevVal * stddevVal
        return variance
    }

    private fun getExtremePointsArray(contour: MatOfPoint): Array<org.opencv.core.Point>? {
        val pts = contour.toArray()
        if (pts.isEmpty()) return null

        var minSum = Double.MAX_VALUE
        var maxSum = -Double.MAX_VALUE
        var minDiff = Double.MAX_VALUE
        var maxDiff = -Double.MAX_VALUE

        var tl: org.opencv.core.Point? = null
        var br: org.opencv.core.Point? = null
        var tr: org.opencv.core.Point? = null
        var bl: org.opencv.core.Point? = null

        for (pt in pts) {
            val sum = pt.x + pt.y
            val diff = pt.y - pt.x

            if (sum < minSum) { minSum = sum; tl = pt }
            if (sum > maxSum) { maxSum = sum; br = pt }
            if (diff < minDiff) { minDiff = diff; tr = pt }
            if (diff > maxDiff) { maxDiff = diff; bl = pt }
        }

        if (tl == null || tr == null || br == null || bl == null) return null

        return arrayOf(tl, tr, br, bl)
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
        tempMatOfPoint4!!.put(0, 0, *tempIntArray8)
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

    private fun orderPoints(pts: List<Point>): List<Point> {
        val cx = (pts[0].x + pts[1].x + pts[2].x + pts[3].x) / 4.0
        val cy = (pts[0].y + pts[1].y + pts[2].y + pts[3].y) / 4.0

        // 2. Compute polar angle of each vertex relative to the centroid
        val p0 = pts[0]
        val p1 = pts[1]
        val p2 = pts[2]
        val p3 = pts[3]

        val a0 = Math.atan2(p0.y - cy, p0.x - cx)
        val a1 = Math.atan2(p1.y - cy, p1.x - cx)
        val a2 = Math.atan2(p2.y - cy, p2.x - cx)
        val a3 = Math.atan2(p3.y - cy, p3.x - cx)

        // 3. Set up pre-allocated array and angle structures to prevent GC thrashing
        val sorted = arrayOf(p0, p1, p2, p3)
        val angles = doubleArrayOf(a0, a1, a2, a3)

        // 4. Sort points in continuous circular order (increasing polar angle)
        for (i in 1..3) {
            val keyAngle = angles[i]
            val keyPoint = sorted[i]
            var j = i - 1
            while (j >= 0 && angles[j] > keyAngle) {
                angles[j + 1] = angles[j]
                sorted[j + 1] = sorted[j]
                j--
            }
            sorted[j + 1] = keyPoint
        }

        // 5. Identify the Top-Left vertex. Top-Left minimizes (x + y).
        var minSumIndex = 0
        var minSum = Double.MAX_VALUE
        for (i in 0..3) {
            val sum = sorted[i].x + sorted[i].y
            if (sum < minSum) {
                minSum = sum
                minSumIndex = i
            }
        }

        // 6. Map points to standard scanning corners: index 0 (TL), index 1 (TR), index 2 (BR), index 3 (BL)
        val tl = sorted[minSumIndex]
        val tr = sorted[(minSumIndex + 1) % 4]
        val br = sorted[(minSumIndex + 2) % 4]
        val bl = sorted[(minSumIndex + 3) % 4]

        // 7. Prevent winding inversion: if horizontal projection is inverted, swap right and left bounds
        if (tr.x < bl.x) {
            return listOf(tl, bl, br, tr)
        }

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
