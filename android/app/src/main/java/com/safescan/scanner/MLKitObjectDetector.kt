package com.safescan.scanner

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.safescan.android.scanner.Point
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MLKitObjectDetector @Inject constructor() {

    private val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableClassification() // To detect "document" or similar
        .build()

    private val detector: ObjectDetector = ObjectDetection.getClient(options)
    
    /**
     * Detects objects (documents) in an ImageProxy and returns the bounding box as points.
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    suspend fun detectDocumentEdges(imageProxy: ImageProxy): List<Point>? {
        val mediaImage = imageProxy.image ?: return null
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        return try {
            val results = detector.process(image).await()
            if (results.isEmpty()) return null
            
            val detectedObject = results.first()
            val box = detectedObject.boundingBox
            
            listOf(
                Point(box.left.toDouble(), box.top.toDouble()),
                Point(box.right.toDouble(), box.top.toDouble()),
                Point(box.right.toDouble(), box.bottom.toDouble()),
                Point(box.left.toDouble(), box.bottom.toDouble())
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Detects objects (documents) in a bitmap and returns the bounding box as points.
     */
    suspend fun detectDocumentEdges(bitmap: Bitmap): List<Point>? {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val results = detector.process(image).await()
            if (results.isEmpty()) return null
            
            // We take the most prominent object
            val detectedObject = results.first()
            val box = detectedObject.boundingBox
            
            listOf(
                Point(box.left.toDouble(), box.top.toDouble()),
                Point(box.right.toDouble(), box.top.toDouble()),
                Point(box.right.toDouble(), box.bottom.toDouble()),
                Point(box.left.toDouble(), box.bottom.toDouble())
            )
        } catch (e: Exception) {
            null
        }
    }
}
