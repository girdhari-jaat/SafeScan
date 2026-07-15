package com.safescan.scanner

import android.graphics.Bitmap
import android.util.Log
import com.safescan.domain.model.Point
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TFLiteEdgeDetectionEngine encapsulates the hybrid TFLite segmentation + OpenCV contour
 * analysis logic for offline document boundary detection.
 * Highly robust against background textures and clutter since it processes model-segmented masks.
 */
@Singleton
class TFLiteEdgeDetectionEngine @Inject constructor(
    private val tfLiteEngine: TFLiteEngine
) {
    /**
     * Detects document corner points using the TFLite + OpenCV model-based pipeline.
     * Returns 4 ordered points (Top-Left, Top-Right, Bottom-Right, Bottom-Left)
     * scaled back to original bitmap coordinates, or null if detection fails.
     */
    fun detectEdges(bitmap: Bitmap): List<Point>? {
        if (bitmap.isRecycled) {
            Log.e("TFLiteEdgeDetectionEngine", "detectEdges: Provided bitmap is recycled!")
            return null
        }

        return try {
            val quad = tfLiteEngine.detectCorners(bitmap, isLive = false)
            if (quad != null) {
                listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.e("TFLiteEdgeDetectionEngine", "Failed to detect edges using TFLite + OpenCV pipeline", e)
            null
        }
    }
}
