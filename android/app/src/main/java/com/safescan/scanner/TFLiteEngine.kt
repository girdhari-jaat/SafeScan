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

    private var lastStableCorners: List<Point>? = null
    private var stableFrameCount = 0
    private val STABLE_THRESHOLD = 2
    private val TOLERANCE = 20.0
    private var lastBitmapWidth = 0
    private var lastBitmapHeight = 0

    private fun isSimilar(current: List<Point>, previous: List<Point>): Boolean {
        if (current.size != previous.size) return false
        for (i in current.indices) {
            val p1 = current[i]
            val p2 = previous[i]
            val dist = Math.sqrt(Math.pow(p1.x - p2.x, 2.0) + Math.pow(p1.y - p2.y, 2.0))
            if (dist > TOLERANCE) return false
        }
        return true
    }

    @Synchronized
    fun detectCorners(bitmap: Bitmap, isLive: Boolean = false): Quadrilateral? {
        val tflite = interpreter ?: return null
        if (bitmap.isRecycled) return null
        
        if (bitmap.width != lastBitmapWidth || bitmap.height != lastBitmapHeight) {
            lastStableCorners = null
            stableFrameCount = 0
            lastBitmapWidth = bitmap.width
            lastBitmapHeight = bitmap.height
        }
        
        var maskMat: Mat? = null
        var probmapU8: Mat? = null
        var probmapSmooth: Mat? = null
        var hierarchy: Mat? = null

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

            val matrix = Matrix()
            matrix.postScale(scale, scale)
            matrix.postTranslate(dx, dy)

            currentCanvas.drawBitmap(bitmap, matrix, paint)
            
            // Zero-copy: Rewind pre-allocated input buffer and populate
            inputBuffer.rewind()
            currentBitmap.getPixels(intValues, 0, currentBitmap.width, 0, 0, currentBitmap.width, currentBitmap.height)
            
            for (pixelValue in intValues) {
                inputBuffer.putFloat(((pixelValue shr 16 and 0xFF) / 255.0f))
                inputBuffer.putFloat(((pixelValue shr 8 and 0xFF) / 255.0f))
                inputBuffer.putFloat(((pixelValue and 0xFF) / 255.0f))
            }
            
            // Zero-copy: Rewind pre-allocated output buffer and run inference
            outputBuffer.rewind()
            tflite.run(inputBuffer, outputBuffer)
            
            outputBuffer.rewind()
            
            // Convert output buffer to an OpenCV Mat
            maskMat = Mat(inputSize, inputSize, CvType.CV_32FC1)
            outputBuffer.asFloatBuffer().get(maskData)
            maskMat.put(0, 0, maskData)
            
            // 1. Confidence Check
            var maxConfidence = 0.0f
            for (v in maskData) {
                if (v > maxConfidence) {
                    maxConfidence = v
                }
            }
            if (maxConfidence < 0.50f) {
                lastStableCorners = null
                stableFrameCount = 0
                return null
            }
            
            // Convert float32 probability mask to 8-bit [0-255] mask for OpenCV operations
            probmapU8 = Mat()
            maskMat.convertTo(probmapU8, CvType.CV_8UC1, 255.0)
            
            // Smooth the mask to reduce noise
            probmapSmooth = Mat()
            Imgproc.GaussianBlur(probmapU8, probmapSmooth, Size(3.0, 3.0), 0.0)
            
            // Determine adaptive thresholds to iterate over
            val thresholds = if (isLive) {
                listOf(0.5, 0.7, 0.85)
            } else {
                listOf(0.4, 0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95)
            }
            
            var bestQuadPoints: List<Point>? = null
            var bestScore = -Double.MAX_VALUE
            
            hierarchy = Mat()
            val minArea256 = inputSize * inputSize * 0.01 // At least 1% of the 256x256 canvas
            
            val bin = Mat()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            
            try {
                for (thr in thresholds) {
                    Imgproc.threshold(probmapSmooth, bin, thr * 255.0, 255.0, Imgproc.THRESH_BINARY)
                    Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
                    
                    val contours = ArrayList<MatOfPoint>()
                    try {
                        Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                        
                        if (contours.isNotEmpty()) {
                            val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) }
                            if (largestContour != null) {
                                val area = Imgproc.contourArea(largestContour)
                                if (area >= minArea256) {
                                    val contour2f = MatOfPoint2f(*largestContour.toArray())
                                    try {
                                        val peri = Imgproc.arcLength(contour2f, true)
                                        var approxPoints: List<Point>? = null
                                        
                                        // Try to simplify to exactly 4 vertices
                                        for (i in 1..15) {
                                            val epsilon = (i * 0.01) * peri
                                            val approx = MatOfPoint2f()
                                            try {
                                                Imgproc.approxPolyDP(contour2f, approx, epsilon, true)
                                                if (approx.total() == 4L) {
                                                    approxPoints = approx.toArray().map { Point(it.x, it.y) }
                                                    break
                                                }
                                            } finally {
                                                approx.release()
                                            }
                                        }
                                        
                                        // Fallback: Get extreme projection points
                                        if (approxPoints == null && largestContour.toArray().size >= 4) {
                                            approxPoints = getExtremePoints(largestContour)
                                        }
                                        
                                        // Validate and score the quadrilateral
                                        if (approxPoints != null && approxPoints.size == 4 && isConvexPoints(approxPoints)) {
                                            val maxCos = getMaxCosinePoints(approxPoints)
                                            if (maxCos < 0.5) { // Reject highly distorted shapes
                                                val normArea = area / (inputSize.toDouble() * inputSize.toDouble())
                                                val score = normArea - maxCos // Score favors larger, straighter shapes
                                                if (score > bestScore) {
                                                    bestScore = score
                                                    bestQuadPoints = approxPoints
                                                }
                                            }
                                        }
                                    } finally {
                                        contour2f.release()
                                    }
                                }
                            }
                        }
                    } finally {
                        contours.forEach { it.release() }
                    }
                }
            } finally {
                bin.release()
                kernel.release()
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
                    if (lastStableCorners != null && isSimilar(ordered, lastStableCorners!!)) {
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
        } finally {
            maskMat?.release()
            probmapU8?.release()
            probmapSmooth?.release()
            hierarchy?.release()
        }
        return null
    }

    private fun getExtremePoints(contour: MatOfPoint): List<Point> {
        val pts = contour.toArray().map { Point(it.x, it.y) }
        if (pts.size < 4) return emptyList()

        // Extremely robust boundary-based corner detection preventing duplicates
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }

        val idealCorners = listOf(
            Point(minX, minY), // TL
            Point(maxX, minY), // TR
            Point(maxX, maxY), // BR
            Point(minX, maxY)  // BL
        )

        val remaining = pts.toMutableList()
        val result = ArrayList<Point>(4)

        for (ideal in idealCorners) {
            if (remaining.isEmpty()) break
            val closest = remaining.minByOrNull { p ->
                val dx = p.x - ideal.x
                val dy = p.y - ideal.y
                dx * dx + dy * dy
            }!!
            result.add(closest)
            remaining.remove(closest)
        }

        return result
    }

    private fun isConvexPoints(points: List<Point>): Boolean {
        if (points.size != 4) return false
        val cvPoints = Array(4) { i -> org.opencv.core.Point(points[i].x, points[i].y) }
        val matOfPoint = MatOfPoint(*cvPoints)
        try {
            return Imgproc.isContourConvex(matOfPoint)
        } finally {
            matOfPoint.release()
        }
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

        // Robust, high-performance X/Y partition corner ordering algorithm
        // 1. Partition into left-most and right-most points
        val sortedByX = pts.sortedBy { it.x }
        val leftMost = listOf(sortedByX[0], sortedByX[1])
        val rightMost = listOf(sortedByX[2], sortedByX[3])

        // 2. Identify TL, BL from left-most, and TR, BR from right-most
        val tl = leftMost.minByOrNull { it.y }!!
        val bl = leftMost.maxByOrNull { it.y }!!

        val tr = rightMost.minByOrNull { it.y }!!
        val br = rightMost.maxByOrNull { it.y }!!

        return listOf(tl, tr, br, bl)
    }
    
    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        letterboxedBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        letterboxedBitmap = null
        canvas = null
    }
}
