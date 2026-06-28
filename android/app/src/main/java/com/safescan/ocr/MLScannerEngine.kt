package com.safescan.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.safescan.core.AppResult
import com.safescan.android.ml.local.LocalMLEngine
import com.safescan.scanner.DocumentScannerEngine
import com.safescan.scanner.ScannerEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ProcessedImage(val bitmap: Bitmap)

@Singleton
class MLScannerEngine @Inject constructor(
    private val mlKitObjectDetector: com.safescan.scanner.MLKitObjectDetector,
    private val localMLEngine: LocalMLEngine
) : DocumentScannerEngine(localMLEngine) {

    /**
     * Attempts to find document corners using ML Kit Object Detection if enabled,
     * falling back to LocalMLEngine if needed.
     */
    override suspend fun detectCorners(bitmap: Bitmap): List<com.safescan.android.scanner.Point>? {
        return if (engineType == ScannerEngineType.MLKIT) {
            mlKitObjectDetector.detectDocumentEdges(bitmap) ?: localMLEngine.detectCorners(bitmap)
        } else {
            localMLEngine.detectCorners(bitmap)
        }
    }

    suspend fun processFrame(bitmap: Bitmap): AppResult<ProcessedImage> = withContext(Dispatchers.IO) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            // Simulating ML Kit Document Scanner processing for raw frames
            // In a real ML Kit implementation with direct frame access,
            // this would auto crop, perspective transform, and binarize the inputImage.
            AppResult.Success(ProcessedImage(bitmap))
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "ML Kit Processing failed", e)
        }
    }

    override suspend fun scanDocument(bitmap: Bitmap): AppResult<Bitmap> = withContext(Dispatchers.Default) {
        // If we are in MLKIT mode, we still want the perspective warp from super.scanDocument
        // or we can use ML Kit specific results if we had them.
        // For now, ensuring super.scanDocument is called to utilize its high-performance warping logic.
        super.scanDocument(bitmap)
    }
}
