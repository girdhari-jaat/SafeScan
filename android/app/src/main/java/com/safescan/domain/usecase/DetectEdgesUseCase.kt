package com.safescan.domain.usecase

import android.graphics.Bitmap
import com.safescan.data.ScannerMode
import com.safescan.scanner.EdgeDetectionEngine
import com.safescan.scanner.TFLiteEdgeDetectionEngine
import com.safescan.domain.model.Point
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DetectEdgesUseCase @Inject constructor(
    private val edgeDetectionEngine: EdgeDetectionEngine,
    private val tfLiteEdgeDetectionEngine: TFLiteEdgeDetectionEngine
) {
    fun detectWithOpenCV(
        bitmap: Bitmap?,
        mode: ScannerMode,
        isManualCrop: Boolean
    ): List<Point>? {
        if (bitmap == null || bitmap.isRecycled) return null
        return try {
            edgeDetectionEngine.detectEdges(bitmap, mode, isManualCrop)
        } catch (e: Exception) {
            null
        }
    }

    fun detectWithTFLite(bitmap: Bitmap?): List<Point>? {
        if (bitmap == null || bitmap.isRecycled) return null
        return try {
            tfLiteEdgeDetectionEngine.detectEdges(bitmap)
        } catch (e: Exception) {
            null
        }
    }
}
