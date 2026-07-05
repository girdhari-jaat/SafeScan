package com.safescan.mlkit_ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.safescan.core.AppResult
import com.safescan.ocr.OcrScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class OcrScannerImpl(private val context: Context) : OcrScanner {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizeText(bitmap: Bitmap): AppResult<List<String>> = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = textRecognizer.process(image).await()
            
            val lines = mutableListOf<String>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    lines.add(line.text)
                }
            }
            AppResult.Success(lines)
        } catch (e: Exception) {
            e.printStackTrace()
            AppResult.Error(e.message ?: "Text recognition failed", e)
        }
    }
}
