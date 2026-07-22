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
        bitmap: Bitmap,
        mode: ScannerMode,
        isManualCrop: Boolean
    ): List<Point>? {
        return edgeDetectionEngine.detectEdges(bitmap, mode, isManualCrop)
    }

    fun detectWithTFLite(bitmap: Bitmap): List<Point>? {
        return tfLiteEdgeDetectionEngine.detectEdges(bitmap)
    }
}
