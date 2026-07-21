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
    private val intValues = IntArray(inputSize * inputSize)
    private val maskData = FloatArray(inputSize * inputSize)

    // Reusable letterbox Bitmap & Canvas to eliminate allocation in live loops
    private var letterboxedBitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    // Pre-allocated OpenCV Mat objects for Zero-Allocation loop execution
    private val maskMat = Mat(inputSize, inputSize, CvType.CV_32FC1)
    private val probmapU8 = Mat(inputSize, inputSize, CvType.CV_8UC1)
    private val probmapSmooth = Mat(inputSize, inputSize, CvType.CV_8UC1)
    private val bin = Mat(inputSize, inputSize, CvType.CV_8UC1)
    private val hierarchy = Mat()
    private val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    private val contour2f = MatOfPoint2f()
    private val approx = MatOfPoint2f()

    // Pre-allocated thresholds to prevent object allocations during frames
    private val liveThresholds = doubleArrayOf(0.5, 0.7, 0.85)
    private val batchThresholds = doubleArrayOf(0.4, 0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95)

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
                    } catch (gpuEx: Throwable) {
                        Log.w("TFLiteEngine", "GPU acceleration not supported or failed to initialize. Falling back to CPU safely.", gpuEx)
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
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("TFLiteEngine", "Fatal error loading Native TFLite model", e)
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

            // Adaptive tolerance: 3% of the smaller dimension of the input bitmap, with a safe 10.0px minimum floor
            val adaptiveTolerance = (Math.min(bitmap.width.toDouble(), bitmap.height.toDouble()) * 0.03).coerceAtLeast(10.0)

            try {
                // Lazily initialize and reuse letterbox bitmap and canvas
                if (letterboxedBitmap == null) {
                    letterboxedBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
                    canvas = Canvas(letterboxedBitmap!!)
                }
                
                val currentBitmap = letterboxedBitmap!!
                val currentCanvas = canvas!!
                
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
                outputBuffer.asFloatBuffer().get(maskData)
                maskMat.put(0, 0, maskData)
                
                // 1. Confidence & Mask Area Check (to avoid detecting false documents from a single noisy peak pixel)
                var maxConfidence = 0.0f
                var confidentPixelsCount = 0
                for (v in maskData) {
                    if (v > maxConfidence) {
                        maxConfidence = v
                    }
                    if (v > 0.50f) {
                        confidentPixelsCount++
                    }
                }
                // Ensure minimum peak confidence is met AND the mask covers at least 3% of the 256x256 image (1966 pixels)
                if (maxConfidence < 0.50f || confidentPixelsCount < 1966) {
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
                
                val minArea256 = inputSize * inputSize * 0.05 // Upgraded minimum area to 5% of the 256x256 canvas to filter out small noisy blobs
                
                for (thr in thresholds) {
                    Imgproc.threshold(probmapSmooth, bin, thr * 255.0, 255.0, Imgproc.THRESH_BINARY)
                    Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
                    
                    val contours = ArrayList<MatOfPoint>()
                    try {
                        Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                        
                        if (contours.isNotEmpty()) {
                            // Evaluate up to the top 3 largest contours using a zero-allocation, in-place loop.
                            var maxContour1: MatOfPoint? = null
                            var maxContour2: MatOfPoint? = null
                            var maxContour3: MatOfPoint? = null
                            var maxArea1 = -1.0
                            var maxArea2 = -1.0
                            var maxArea3 = -1.0

                            for (i in 0 until contours.size) {
                                val contour = contours[i]
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

                                if (area >= minArea256) {
                                    // Zero-Allocation native-to-native contour conversion to prevent heavy JVM allocations
                                    contour.convertTo(contour2f, CvType.CV_32F)
                                    
                                    val peri = Imgproc.arcLength(contour2f, true)
                                    var approxPoints: List<Point>? = null
                                    
                                    // Try to simplify to exactly 4 vertices
                                    for (i in 1..15) {
                                        val epsilon = (i * 0.01) * peri
                                        Imgproc.approxPolyDP(contour2f, approx, epsilon, true)
                                        if (approx.total() == 4L) {
                                            val approxArray = approx.toArray()
                                            approxPoints = listOf(
                                                Point(approxArray[0].x, approxArray[0].y),
                                                Point(approxArray[1].x, approxArray[1].y),
                                                Point(approxArray[2].x, approxArray[2].y),
                                                Point(approxArray[3].x, approxArray[3].y)
                                            )
                                            break
                                        }
                                    }
                                    
                                    // Fallback: Get extreme projection points
                                    if (approxPoints == null && contour.toArray().size >= 4) {
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
                        }
                    } finally {
                        contours.forEach { it.release() }
                    }

                    // High-performance early-exit: if we have found a highly valid, large, straight convex quadrilateral,
                    // stop evaluating lower-priority thresholds to save CPU cycles.
                    if (bestScore > 0.45) {
                        break
                    }
                }
                
                var result: Quadrilateral? = null
                
                if (bestQuadPoints != null) {
                    // Scale corners back to original bitmap dimensions reversing the letterbox translation and scale
                    val scaleD = Math.min(inputSize.toDouble() / bitmap.width.toDouble(), inputSize.toDouble() / bitmap.height.toDouble())
                    val dxD = (inputSize.toDouble() - bitmap.width.toDouble() * scaleD) / 2.0
                    val dyD = (inputSize.toDouble() - bitmap.height.toDouble() * scaleD) / 2.0

                    val scaledPoints = bestQuadPoints.map {
                        val originalX = ((it.x - dxD) / scaleD).coerceIn(0.0, bitmap.width.toDouble())
                        val originalY = ((it.y - dyD) / scaleD).coerceIn(0.0, bitmap.height.toDouble())
                        Point(originalX, originalY)
                    }
                    val ordered = orderPoints(scaledPoints)
                    
                    result = if (isLive) {
                        if (lastStableCorners != null && isSimilar(ordered, lastStableCorners!!, adaptiveTolerance)) {
                            stableFrameCount++
                        } else {
                            lastStableCorners = ordered
                            stableFrameCount = 1
                        }
                        
                        if (stableFrameCount >= STABLE_THRESHOLD) {
                            Quadrilateral(ordered[0], ordered[1], ordered[2], ordered[3])
                        } else {
                            null
                        }
                    } else {
                        Quadrilateral(ordered[0], ordered[1], ordered[2], ordered[3])
                    }
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
        val pts = contour.toArray()
        if (pts.size < 4) return emptyList()

        // Robust, non-greedy $x+y$ and $y-x$ projection method for finding accurate boundaries
        var tl = pts[0]
        var tr = pts[0]
        var br = pts[0]
        var bl = pts[0]

        var minSum = Double.MAX_VALUE
        var maxSum = -Double.MAX_VALUE
        var minDiff = Double.MAX_VALUE
        var maxDiff = -Double.MAX_VALUE

        for (p in pts) {
            val sum = p.x + p.y
            val diff = p.y - p.x

            // Top-Left (minimizes x + y)
            if (sum < minSum) {
                minSum = sum
                tl = p
            }
            // Bottom-Right (maximizes x + y)
            if (sum > maxSum) {
                maxSum = sum
                br = p
            }
            // Top-Right (minimizes y - x)
            if (diff < minDiff) {
                minDiff = diff
                tr = p
            }
            // Bottom-Left (maximizes y - x)
            if (diff > maxDiff) {
                maxDiff = diff
                bl = p
            }
        }

        return listOf(
            Point(tl.x, tl.y),
            Point(tr.x, tr.y),
            Point(br.x, br.y),
            Point(bl.x, bl.y)
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
        } finally {
            executionLock.unlock()
        }
    }
}
