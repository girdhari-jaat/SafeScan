package com.safescan.scanner

import android.content.Context
import android.graphics.Bitmap
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

    init {
        try {
            val assetFileDescriptor = context.assets.openFd("fairscan-segmentation-model.tflite")
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
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
            // Resize bitmap to 256x256
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            
            // Prepare input buffer [1, 256, 256, 3] float32
            val inputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
            inputBuffer.order(ByteOrder.nativeOrder())
            
            val intValues = IntArray(inputSize * inputSize)
            resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)
            
            for (pixelValue in intValues) {
                inputBuffer.putFloat(((pixelValue shr 16 and 0xFF) / 255.0f))
                inputBuffer.putFloat(((pixelValue shr 8 and 0xFF) / 255.0f))
                inputBuffer.putFloat(((pixelValue and 0xFF) / 255.0f))
            }
            
            // Prepare output buffer [1, 256, 256, 1] float32
            val outputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 1)
            outputBuffer.order(ByteOrder.nativeOrder())
            
            // Run inference
            tflite.run(inputBuffer, outputBuffer)
            
            outputBuffer.rewind()
            
            // Convert output buffer to an OpenCV Mat
            maskMat = Mat(inputSize, inputSize, CvType.CV_32FC1)
            val maskData = FloatArray(inputSize * inputSize)
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
            
            for (thr in thresholds) {
                val bin = Mat()
                Imgproc.threshold(probmapSmooth, bin, thr * 255.0, 255.0, Imgproc.THRESH_BINARY)
                
                // Morphology close operation to fill any gaps/holes
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
                Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
                kernel.release()
                
                val contours = ArrayList<MatOfPoint>()
                Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                
                if (contours.isNotEmpty()) {
                    val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) }
                    if (largestContour != null) {
                        val area = Imgproc.contourArea(largestContour)
                        if (area >= minArea256) {
                            val contour2f = MatOfPoint2f(*largestContour.toArray())
                            val peri = Imgproc.arcLength(contour2f, true)
                            var approxPoints: List<Point>? = null
                            
                            // Try to simplify to exactly 4 vertices
                            for (i in 1..15) {
                                val epsilon = (i * 0.01) * peri
                                val approx = MatOfPoint2f()
                                Imgproc.approxPolyDP(contour2f, approx, epsilon, true)
                                if (approx.total() == 4L) {
                                    approxPoints = approx.toArray().map { Point(it.x, it.y) }
                                    approx.release()
                                    break
                                }
                                approx.release()
                            }
                            
                            // Fallback: Get extreme projection points
                            if (approxPoints == null && largestContour.toArray().size >= 4) {
                                approxPoints = getExtremePoints(largestContour)
                            }
                            
                            contour2f.release()
                            
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
                        }
                    }
                }
                
                bin.release()
                contours.forEach { it.release() }
            }
            
            var result: Quadrilateral? = null
            
            if (bestQuadPoints != null) {
                // Scale corners back to original bitmap dimensions
                val scaleX = bitmap.width.toDouble() / inputSize.toDouble()
                val scaleY = bitmap.height.toDouble() / inputSize.toDouble()
                val scaledPoints = bestQuadPoints.map { Point(it.x * scaleX, it.y * scaleY) }
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
        if (pts.isEmpty()) return emptyList()

        val sums = pts.map { it.x + it.y }
        val diffs = pts.map { it.y - it.x }

        val tl = pts[sums.indexOf(sums.minOrNull()!!)]
        val br = pts[sums.indexOf(sums.maxOrNull()!!)]
        val tr = pts[diffs.indexOf(diffs.minOrNull()!!)]
        val bl = pts[diffs.indexOf(diffs.maxOrNull()!!)]

        return listOf(tl, tr, br, bl)
    }

    private fun isConvexPoints(points: List<Point>): Boolean {
        if (points.size != 4) return false
        val matOfPoint = MatOfPoint(*points.map { org.opencv.core.Point(it.x, it.y) }.toTypedArray())
        val convex = Imgproc.isContourConvex(matOfPoint)
        matOfPoint.release()
        return convex
    }

    private fun getMaxCosinePoints(points: List<Point>): Double {
        if (points.size != 4) return 1.0
        var maxCosine = 0.0
        val cvPoints = points.map { org.opencv.core.Point(it.x, it.y) }
        for (i in 0..3) {
            val cosine = Math.abs(angle(cvPoints[(i + 1) % 4], cvPoints[(i + 3) % 4], cvPoints[i]))
            if (cosine > maxCosine) {
                maxCosine = cosine
            }
        }
        return maxCosine
    }

    private fun angle(pt1: org.opencv.core.Point, pt2: org.opencv.core.Point, pt0: org.opencv.core.Point): Double {
        val dx1 = pt1.x - pt0.x
        val dy1 = pt1.y - pt0.y
        val dx2 = pt2.x - pt0.x
        val dy2 = pt2.y - pt0.y
        return (dx1 * dx2 + dy1 * dy2) / Math.sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10)
    }

    private fun orderPoints(pts: List<Point>): List<Point> {
        if (pts.size < 4) return pts

        val sums = pts.map { it.x + it.y }
        val diffs = pts.map { it.y - it.x }

        val minSum = sums.minOrNull() ?: 0.0
        val maxSum = sums.maxOrNull() ?: 0.0
        val minDiff = diffs.minOrNull() ?: 0.0
        val maxDiff = diffs.maxOrNull() ?: 0.0

        val tl = pts[sums.indexOf(minSum)]
        val br = pts[sums.indexOf(maxSum)]
        val tr = pts[diffs.indexOf(minDiff)]
        val bl = pts[diffs.indexOf(maxDiff)]

        return listOf(tl, tr, br, bl)
    }
    
    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
