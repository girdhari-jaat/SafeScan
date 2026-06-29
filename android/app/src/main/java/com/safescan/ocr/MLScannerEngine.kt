package com.safescan.ocr

import android.graphics.Bitmap
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
            // MLKit removed to reduce size.
            AppResult.Success(ProcessedImage(bitmap))
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Processing failed", e)
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
