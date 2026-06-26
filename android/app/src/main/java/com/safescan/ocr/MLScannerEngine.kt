package com.safescan.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.safescan.core.AppResult
import com.safescan.scanner.DocumentScannerEngine
import com.safescan.scanner.ScannerEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProcessedImage(val bitmap: Bitmap)

class MLScannerEngine(
    mlEngine: com.safescan.scanner.MLScannerEngine? = null
) : DocumentScannerEngine(mlEngine) {

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

    override suspend fun scanDocument(bitmap: Bitmap): AppResult<Bitmap> {
        if (engineType == ScannerEngineType.MLKIT) {
            return when (val result = processFrame(bitmap)) {
                is AppResult.Success -> AppResult.Success(result.data.bitmap)
                is AppResult.Error -> AppResult.Error(result.message, result.e)
            }
        }
        return super.scanDocument(bitmap)
    }
}
