package com.safescan.scanner.ml

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

class LocalMLEngine(private val context: Context) {
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

            val options = Interpreter.Options()
            gpuDelegate = GpuDelegate()
            options.addDelegate(gpuDelegate)
            
            interpreter = Interpreter(tfliteModel, options)
            Log.d("LocalMLEngine", "TFLite model loaded successfully")
        } catch (e: Exception) {
            Log.e("LocalMLEngine", "Error loading TFLite model", e)
        }
    }

    fun detectCorners(bitmap: Bitmap): Quadrilateral? {
        val tflite = interpreter ?: return null
        var binaryMask: Mat? = null
        var resizedMask: Mat? = null
        var maskMat: Mat? = null
        val contours = ArrayList<MatOfPoint>()
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
            
            // Threshold to binary map (0 or 255)
            binaryMask = Mat()
            maskMat.convertTo(binaryMask, CvType.CV_8UC1, 255.0)
            Imgproc.threshold(binaryMask, binaryMask, 127.0, 255.0, Imgproc.THRESH_BINARY)
            
            // Resize mask back to original bitmap size
            resizedMask = Mat()
            Imgproc.resize(binaryMask, resizedMask, Size(bitmap.width.toDouble(), bitmap.height.toDouble()))
            
            // Find contours on the mask
            hierarchy = Mat()
            Imgproc.findContours(resizedMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            
            var bestQuad: MatOfPoint2f? = null
            var maxArea = 0.0

            for (contour in contours) {
                val contour2f = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(contour2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

                if (approx.total() == 4L) {
                    val area = Imgproc.contourArea(approx)
                    if (area > maxArea && area > (bitmap.width * bitmap.height * 0.05)) {
                        maxArea = area
                        bestQuad?.release()
                        bestQuad = approx
                    } else {
                        approx.release()
                    }
                } else {
                    approx.release()
                }
                contour2f.release()
            }
            
            val result = bestQuad?.let { quad ->
                val points = quad.toArray()
                val ordered = orderPoints(points.map { Point(it.x, it.y) })
                Quadrilateral(ordered[0], ordered[1], ordered[2], ordered[3])
            }
            bestQuad?.release()
            return result
        } catch (e: Exception) {
            Log.e("LocalMLEngine", "Error running inference", e)
        } finally {
            maskMat?.release()
            binaryMask?.release()
            resizedMask?.release()
            hierarchy?.release()
            contours.forEach { it.release() }
        }
        return null
    }

    private fun orderPoints(pts: List<Point>): List<Point> {
        val sums = pts.map { it.x + it.y }
        val diffs = pts.map { it.y - it.x }

        val tl = pts[sums.indexOf(sums.minOrNull()!!)]
        val br = pts[sums.indexOf(sums.maxOrNull()!!)]
        val tr = pts[diffs.indexOf(diffs.minOrNull()!!)]
        val bl = pts[diffs.indexOf(diffs.maxOrNull()!!)]

        return listOf(tl, tr, br, bl)
    }
    
    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
