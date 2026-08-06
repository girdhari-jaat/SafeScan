package com.safescan.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.safescan.domain.model.Point
import com.safescan.domain.model.Quadrilateral
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class TFLiteEngine(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val executionLock = java.util.concurrent.locks.ReentrantLock()
    private val inputSize = 256 // Fairscan model input size

    // Zero-copy, high-performance pre-allocated buffers to prevent Garbage Collection (GC) thrashing
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 1).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputFloatBuffer = outputBuffer.asFloatBuffer()
    private val intValues = IntArray(inputSize * inputSize)
    private val maskData = FloatArray(inputSize * inputSize)

    // Reusable letterbox Bitmap & Canvas to eliminate allocation in live loops
    private var letterboxedBitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    // Pre-allocated OpenCV Mat objects and buffers for Zero-Allocation loop execution
    private val maskMat = Mat(inputSize, inputSize, CvType.CV_32FC1)
    private val probmapU8 = Mat(inputSize, inputSize, CvType.CV_8UC1)
    private val probmapSmooth = Mat(inputSize, inputSize, CvType.CV_8UC1)
    private val bin = Mat(inputSize, inputSize, CvType.CV_8UC1)
    private val hierarchy = Mat()
    private val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
    private val contour2f = MatOfPoint2f()
    private val approx = MatOfPoint2f()
    private val hullMat = MatOfInt()
    private val hullPoints = MatOfPoint2f()
    private val contourMatOfPoint = MatOfPoint()
    private val reusableHullPoints = ArrayList<org.opencv.core.Point>()
    private val contoursList = ArrayList<MatOfPoint>()
    private val tempFloat8 = FloatArray(8)
    private val ptBuf = DoubleArray(2)
    private var contourIntBuffer = IntArray(1024)

    // Pre-allocated thresholds prioritized from tighter high-confidence boundaries to fallback levels
    private val liveThresholds = doubleArrayOf(0.50, 0.40, 0.60, 0.35)
    private val batchThresholds = doubleArrayOf(0.60, 0.70, 0.50, 0.40, 0.35)

    val isGpuAccelerated: Boolean
        get() = gpuDelegate != null

    init {
        try {
            // Memory leak and File Descriptor leak fix: using use() blocks to guarantee that streams & file descriptors close safely after mapping
            context.assets.openFd("fairscan-segmentation-model.tflite").use { assetFileDescriptor ->
                FileInputStream(assetFileDescriptor.fileDescriptor).use { fileInputStream ->
                    val fileChannel = fileInputStream.channel
                    val startOffset = assetFileDescriptor.startOffset
                    val declaredLength = assetFileDescriptor.declaredLength
                    val tfliteModel = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

                    try {
                        // Try initializing with GPU Delegate
                        val options = Interpreter.Options()
                        gpuDelegate = GpuDelegate()
                        options.addDelegate(gpuDelegate)
                        interpreter = Interpreter(tfliteModel, options)
                        Log.d("TFLiteEngine", "Native TFLite model loaded successfully with GPU acceleration")
                        com.safescan.core.ScannerDebugLogger.logTFLiteInit("Model loaded successfully with GPU acceleration")
                    } catch (gpuEx: Throwable) {
                        Log.w("TFLiteEngine", "GPU acceleration not supported or failed to initialize. Falling back to CPU safely.", gpuEx)
                        com.safescan.core.ScannerDebugLogger.logTFLiteInit("GPU acceleration failed, falling back to CPU")
                        // Clean up GPU delegate if it was created
                        try {
                            gpuDelegate?.close()
                        } catch (closeEx: Throwable) {
                            Log.e("TFLiteEngine", "Failed to close gpuDelegate", closeEx)
                        }
                        gpuDelegate = null
                        
                        // Load interpreter with standard CPU options
                        val options = Interpreter.Options()
                        options.setNumThreads(4) // Use 4 CPU threads for high performance
                        interpreter = Interpreter(tfliteModel, options)
                        Log.d("TFLiteEngine", "Native TFLite model loaded successfully with CPU (4 threads)")
                        com.safescan.core.ScannerDebugLogger.logTFLiteInit("Model loaded successfully with CPU (4 threads)")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("TFLiteEngine", "Fatal error loading Native TFLite model", e)
            com.safescan.core.ScannerDebugLogger.logError("TFLiteEngine", "Fatal error loading Native TFLite model", e)
        }
    }

    @Volatile
    private var isClosed = false

    private var lastStableCorners: List<Point>? = null
    private var stableFrameCount = 0
    private val STABLE_THRESHOLD = 3 // Increased threshold to 3 for better stability and less frame flicker
    private var lastBitmapWidth = 0
    private var lastBitmapHeight = 0

    private fun isSimilar(current: List<Point>, previous: List<Point>, tolerance: Double): Boolean {
        if (current.size != previous.size) return false
        for (i in current.indices) {
            val p1 = current[i]
            val p2 = previous[i]
            val dist = Math.sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y))
            if (dist > tolerance) return false
        }
        return true
    }

    fun detectCorners(bitmap: Bitmap, isLive: Boolean = false): Quadrilateral? {
        executionLock.lock()
        try {
            if (isClosed) return null
            val tflite = interpreter ?: return null
            if (bitmap.isRecycled) return null
            
            if (bitmap.width != lastBitmapWidth || bitmap.height != lastBitmapHeight) {
                lastStableCorners = null
                stableFrameCount = 0
                lastBitmapWidth = bitmap.width
                lastBitmapHeight = bitmap.height
            }

            // Adaptive tolerance: 8% of the smaller dimension of the input bitmap, with a safe 30.0px minimum floor for hand tremor robustness
            val adaptiveTolerance = (Math.min(bitmap.width.toDouble(), bitmap.height.toDouble()) * 0.08).coerceAtLeast(30.0)

            try {
                // Lazily initialize and reuse letterbox bitmap and canvas
                var lbBitmap = letterboxedBitmap
                if (lbBitmap == null) {
                    lbBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
                    letterboxedBitmap = lbBitmap
                    canvas = Canvas(lbBitmap)
                }
                
                val currentBitmap = lbBitmap
                val currentCanvas = canvas ?: Canvas(currentBitmap)
                
                // Clear with black background
                currentCanvas.drawColor(Color.BLACK)

                val scale = Math.min(inputSize.toFloat() / bitmap.width, inputSize.toFloat() / bitmap.height)
                val dx = (inputSize - bitmap.width * scale) / 2f
                val dy = (inputSize - bitmap.height * scale) / 2f

                // Safe reuse of shared Matrix to prevent memory reallocation and state leaks
                matrix.reset()
                matrix.postScale(scale, scale)
                matrix.postTranslate(dx, dy)

                currentCanvas.drawBitmap(bitmap, matrix, paint)
                
                // Zero-copy: Rewind pre-allocated input buffer and populate
                inputBuffer.rewind()
                currentBitmap.getPixels(intValues, 0, currentBitmap.width, 0, 0, currentBitmap.width, currentBitmap.height)
                
                val scaleFactor = 1.0f / 255.0f
                for (pixelValue in intValues) {
                    inputBuffer.putFloat(((pixelValue shr 16 and 0xFF) * scaleFactor))
                    inputBuffer.putFloat(((pixelValue shr 8 and 0xFF) * scaleFactor))
                    inputBuffer.putFloat(((pixelValue and 0xFF) * scaleFactor))
                }
                
                // Zero-copy: Rewind pre-allocated output buffer and run inference
                outputBuffer.rewind()
                tflite.run(inputBuffer, outputBuffer)
                
                outputBuffer.rewind()
                
                // Populate the pre-allocated float32 maskMat directly from output buffer
                outputFloatBuffer.rewind()
                outputFloatBuffer.get(maskData)
                maskMat.put(0, 0, maskData)
                
                // 1. Confidence & Mask Area Check (to avoid detecting false documents from a single noisy peak pixel)
                var maxConfidence = 0.0f
                var confidentPixelsCount = 0
                for (v in maskData) {
                    if (v > maxConfidence) {
                        maxConfidence = v
                    }
                    if (v > 0.35f) {
                        confidentPixelsCount++
                    }
                }
                val minConf = if (isLive) 0.35f else 0.50f
                val minPixels = if (isLive) 500 else 1966
                // Ensure minimum peak confidence is met AND the mask covers at least minimum pixels
                if (maxConfidence < minConf || confidentPixelsCount < minPixels) {
                    lastStableCorners = null
                    stableFrameCount = 0
                    return null
                }
                
                // Convert float32 probability mask to 8-bit [0-255] mask using pre-allocated probmapU8
                maskMat.convertTo(probmapU8, CvType.CV_8UC1, 255.0)
                
                // Smooth the mask using pre-allocated probmapSmooth to reduce noise
                Imgproc.GaussianBlur(probmapU8, probmapSmooth, Size(3.0, 3.0), 0.0)
                
                // Access pre-allocated threshold arrays without object instantiation
                val thresholds = if (isLive) liveThresholds else batchThresholds
                
                var bestQuadPoints: List<Point>? = null
                var bestScore = -Double.MAX_VALUE
                
                val minArea256 = if (isLive) (inputSize * inputSize * 0.015) else (inputSize * inputSize * 0.04)
                
                for (thr in thresholds) {
                    Imgproc.threshold(probmapSmooth, bin, thr * 255.0, 255.0, Imgproc.THRESH_BINARY)
                    Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
                    
                    contoursList.clear()
                    try {
                        Imgproc.findContours(bin, contoursList, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                        
                        if (contoursList.isNotEmpty()) {
                            // Evaluate up to the top 3 largest contours using a zero-allocation, in-place loop.
                            var maxContour1: MatOfPoint? = null
                            var maxContour2: MatOfPoint? = null
                            var maxContour3: MatOfPoint? = null
                            var maxArea1 = -1.0
                            var maxArea2 = -1.0
                            var maxArea3 = -1.0

                            for (i in 0 until contoursList.size) {
                                val contour = contoursList[i]
                                val area = Imgproc.contourArea(contour)
                                if (area > maxArea1) {
                                    maxArea3 = maxArea2
                                    maxContour3 = maxContour2
                                    maxArea2 = maxArea1
                                    maxContour2 = maxContour1
                                    maxArea1 = area
                                    maxContour1 = contour
                                } else if (area > maxArea2) {
                                    maxArea3 = maxArea2
                                    maxContour3 = maxContour2
                                    maxArea2 = area
                                    maxContour2 = contour
                                } else if (area > maxArea3) {
                                    maxArea3 = area
                                    maxContour3 = contour
                                }
                            }

                            for (idx in 0 until 3) {
                                val contour = when (idx) {
                                    0 -> maxContour1
                                    1 -> maxContour2
                                    2 -> maxContour3
                                    else -> null
                                } ?: continue
                                val area = when (idx) {
                                    0 -> maxArea1
                                    1 -> maxArea2
                                    2 -> maxArea3
                                    else -> 0.0
                                }

                                if (area < minArea256) {
                                    break // Subsequent contours are even smaller
                                }

                                // Zero-Allocation native-to-native contour conversion to prevent heavy JVM allocations
                                contour.convertTo(contour2f, CvType.CV_32F)
                                
                                val peri = Imgproc.arcLength(contour2f, true)
                                var approxPoints: List<Point>? = null
                                
                                // 1. Try direct approxPolyDP on smooth mask boundary
                                for (i in 1..15) {
                                    val epsilon = (i * 0.01) * peri
                                    Imgproc.approxPolyDP(contour2f, approx, epsilon, true)
                                    if (approx.total() == 4L) {
                                        approx.get(0, 0, tempFloat8)
                                        approxPoints = listOf(
                                            Point(tempFloat8[0].toDouble(), tempFloat8[1].toDouble()),
                                            Point(tempFloat8[2].toDouble(), tempFloat8[3].toDouble()),
                                            Point(tempFloat8[4].toDouble(), tempFloat8[5].toDouble()),
                                            Point(tempFloat8[6].toDouble(), tempFloat8[7].toDouble())
                                        )
                                        break
                                    }
                                }
                                
                                // 2. Convex Hull pass: Smooths out mask boundary noise & prevents single corner from projecting outwards
                                if (approxPoints == null && contour.total() >= 4) {
                                    Imgproc.convexHull(contour, hullMat)
                                    val hullIndices = hullMat.toArray()
                                    if (hullIndices.size >= 4) {
                                        while (reusableHullPoints.size < hullIndices.size) {
                                            reusableHullPoints.add(org.opencv.core.Point())
                                        }
                                        val activeHullPts = reusableHullPoints.subList(0, hullIndices.size)
                                        for (hIdx in 0 until hullIndices.size) {
                                            val idx = hullIndices[hIdx]
                                            contour.get(idx, 0, ptBuf)
                                            val pt = activeHullPts[hIdx]
                                            pt.x = ptBuf[0]
                                            pt.y = ptBuf[1]
                                        }
                                        contourMatOfPoint.fromList(activeHullPts)
                                        contourMatOfPoint.convertTo(hullPoints, CvType.CV_32F)
                                        val hPeri = Imgproc.arcLength(hullPoints, true)

                                        for (i in 1..20) {
                                            val epsilon = (i * 0.01) * hPeri
                                            Imgproc.approxPolyDP(hullPoints, approx, epsilon, true)
                                            if (approx.total() == 4L) {
                                                approx.get(0, 0, tempFloat8)
                                                approxPoints = listOf(
                                                    Point(tempFloat8[0].toDouble(), tempFloat8[1].toDouble()),
                                                    Point(tempFloat8[2].toDouble(), tempFloat8[3].toDouble()),
                                                    Point(tempFloat8[4].toDouble(), tempFloat8[5].toDouble()),
                                                    Point(tempFloat8[6].toDouble(), tempFloat8[7].toDouble())
                                                )
                                                break
                                            }
                                        }

                                        // 3. If convex hull approx yields 5..12 vertices (e.g. rounded corners), pick best 4 vertices maximizing area
                                        if (approxPoints == null) {
                                            val totalPts = approx.total().toInt()
                                            if (totalPts in 4..12) {
                                                val polyPts = ArrayList<Point>(totalPts)
                                                val floatBuf = FloatArray(totalPts * 2)
                                                approx.get(0, 0, floatBuf)
                                                for (k in 0 until totalPts) {
                                                    polyPts.add(Point(floatBuf[k * 2].toDouble(), floatBuf[k * 2 + 1].toDouble()))
                                                }
                                                approxPoints = findBest4CornerQuad(polyPts)
                                            }
                                        }
                                    }
                                }

                                // 4. Fallback: Extreme projection points
                                if (approxPoints == null && contour.total() >= 4) {
                                    approxPoints = getExtremePoints(contour)
                                }
                                    
                                    // Validate and score the quadrilateral
                                    if (approxPoints != null && approxPoints.size == 4 && isConvexPoints(approxPoints)) {
                                        val maxCos = getMaxCosinePoints(approxPoints)
                                        if (maxCos < 0.707) { // 0.707 threshold (45 degrees) allows skewed/perspective shots
                                            val normArea = area / (inputSize.toDouble() * inputSize.toDouble())
                                            val score = normArea - maxCos // Score favors larger, straighter shapes
                                            if (score > bestScore) {
                                                bestScore = score
                                                bestQuadPoints = approxPoints
                                            }
                                        }
                                    }
                            }
                        }
                    } finally {
                        contoursList.forEach { it.release() }
                        contoursList.clear()
                    }

                    // High-performance early-exit: if we have found a highly valid, large, straight convex quadrilateral,
                    // stop evaluating lower-priority thresholds to save CPU cycles.
                    if (bestScore > 0.45) {
                        break
                    }
                }
                
                var result: Quadrilateral? = null
                
                if (bestQuadPoints != null) {
                    // Contract quad points by 0.5px towards centroid on 256x256 tensor space to eliminate outer edge protrusion
                    val cx = (bestQuadPoints[0].x + bestQuadPoints[1].x + bestQuadPoints[2].x + bestQuadPoints[3].x) / 4.0
                    val cy = (bestQuadPoints[0].y + bestQuadPoints[1].y + bestQuadPoints[2].y + bestQuadPoints[3].y) / 4.0
                    val tightQuadPoints = bestQuadPoints.map { pt ->
                        val vx = cx - pt.x
                        val vy = cy - pt.y
                        val dist = Math.hypot(vx, vy)
                        if (dist > 1e-3) {
                            Point(pt.x + (vx / dist) * 0.5, pt.y + (vy / dist) * 0.5)
                        } else pt
                    }

                    // Scale corners back to original bitmap dimensions reversing the letterbox translation and scale
                    val scaleD = Math.min(inputSize.toDouble() / bitmap.width.toDouble(), inputSize.toDouble() / bitmap.height.toDouble())
                    val dxD = (inputSize.toDouble() - bitmap.width.toDouble() * scaleD) / 2.0
                    val dyD = (inputSize.toDouble() - bitmap.height.toDouble() * scaleD) / 2.0

                    val scaledPoints = tightQuadPoints.map {
                        val originalX = ((it.x - dxD) / scaleD).coerceIn(0.0, bitmap.width.toDouble())
                        val originalY = ((it.y - dyD) / scaleD).coerceIn(0.0, bitmap.height.toDouble())
                        Point(originalX, originalY)
                    }
                    val ordered = orderPoints(scaledPoints)
                    
                    if (isLive) {
                        val prevCorners = lastStableCorners
                        if (prevCorners != null) {
                            if (isSimilar(ordered, prevCorners, adaptiveTolerance)) {
                                stableFrameCount++
                            } else {
                                // Graceful decay: decrement instead of resetting to 1 immediately to allow minor hand tremors
                                stableFrameCount = (stableFrameCount - 1).coerceAtLeast(1)
                                // Gradually update the baseline to filter out high-frequency noise
                                lastStableCorners = ordered
                            }
                        } else {
                            lastStableCorners = ordered
                            stableFrameCount = 1
                        }
                    }
                    result = Quadrilateral(ordered[0], ordered[1], ordered[2], ordered[3])
                } else {
                    if (isLive) {
                        lastStableCorners = null
                        stableFrameCount = 0
                    }
                }
                
                return result
            } catch (e: Throwable) {
                Log.e("TFLiteEngine", "Error running inference in adaptive multi-thresholding", e)
            }
            return null
        } finally {
            executionLock.unlock()
        }
    }

    private fun getExtremePoints(contour: MatOfPoint): List<Point> {
        val total = contour.total().toInt()
        if (total < 4) return emptyList()
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

            // Top-Left (minimizes x + y)
            if (sum < minSum) {
                minSum = sum
                tlX = px; tlY = py
            }
            // Bottom-Right (maximizes x + y)
            if (sum > maxSum) {
                maxSum = sum
                brX = px; brY = py
            }
            // Top-Right (minimizes y - x)
            if (diff < minDiff) {
                minDiff = diff
                trX = px; trY = py
            }
            // Bottom-Left (maximizes y - x)
            if (diff > maxDiff) {
                maxDiff = diff
                blX = px; blY = py
            }
        }

        return listOf(
            Point(tlX, tlY),
            Point(trX, trY),
            Point(brX, brY),
            Point(blX, blY)
        )
    }

    private fun isConvexPoints(points: List<Point>): Boolean {
        if (points.size != 4) return false
        val a = points[0]
        val b = points[1]
        val c = points[2]
        val d = points[3]

        // Pure mathematical 2D cross-product convex check to prevent JVM garbage allocations
        val cp1 = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        val cp2 = (c.x - b.x) * (d.y - c.y) - (c.y - b.y) * (d.x - c.x)
        val cp3 = (d.x - c.x) * (a.y - d.y) - (d.y - c.y) * (a.x - d.x)
        val cp4 = (a.x - d.x) * (b.y - a.y) - (a.y - d.y) * (b.x - a.x)

        return (cp1 > 0 && cp2 > 0 && cp3 > 0 && cp4 > 0) || (cp1 < 0 && cp2 < 0 && cp3 < 0 && cp4 < 0)
    }

    private fun getMaxCosinePoints(points: List<Point>): Double {
        if (points.size != 4) return 1.0
        var maxCosine = 0.0
        for (i in 0..3) {
            val cosine = Math.abs(angle(points[(i + 1) % 4], points[(i + 3) % 4], points[i]))
            if (cosine > maxCosine) {
                maxCosine = cosine
            }
        }
        return maxCosine
    }

    private fun angle(pt1: Point, pt2: Point, pt0: Point): Double {
        val dx1 = pt1.x - pt0.x
        val dy1 = pt1.y - pt0.y
        val dx2 = pt2.x - pt0.x
        val dy2 = pt2.y - pt0.y
        return (dx1 * dx2 + dy1 * dy2) / Math.sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10)
    }

    private fun orderPoints(pts: List<Point>): List<Point> {
        if (pts.size != 4) return pts

        // 1. Calculate centroid (center of mass) of the four vertices to establish a central polar pivot
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

        // 3. Set up pre-allocated array and angle structures to prevent Garbage Collection (GC) thrashing
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

    private fun findBest4CornerQuad(pts: List<Point>): List<Point>? {
        val n = pts.size
        if (n < 4) return null
        if (n == 4) return pts

        var maxArea = -1.0
        var bestQuad: List<Point>? = null

        for (i in 0 until n - 3) {
            for (j in i + 1 until n - 2) {
                for (k in j + 1 until n - 1) {
                    for (m in k + 1 until n) {
                        val quad = listOf(pts[i], pts[j], pts[k], pts[m])
                        if (isConvexPoints(quad)) {
                            val area = Math.abs(
                                (quad[0].x * (quad[1].y - quad[3].y) +
                                 quad[1].x * (quad[2].y - quad[0].y) +
                                 quad[2].x * (quad[3].y - quad[1].y) +
                                 quad[3].x * (quad[0].y - quad[2].y)) / 2.0
                            )
                            if (area > maxArea) {
                                maxArea = area
                                bestQuad = quad
                            }
                        }
                    }
                }
            }
        }
        return bestQuad
    }
    
    fun close() {
        executionLock.lock()
        try {
            if (isClosed) return
            isClosed = true

            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
            letterboxedBitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            letterboxedBitmap = null
            canvas = null

            // Explicitly release pre-allocated OpenCV Mat structures to prevent native memory leaks
            maskMat.release()
            probmapU8.release()
            probmapSmooth.release()
            bin.release()
            hierarchy.release()
            kernel.release()
            contour2f.release()
            approx.release()
            hullMat.release()
            hullPoints.release()
            contourMatOfPoint.release()
        } finally {
            executionLock.unlock()
        }
    }
}
