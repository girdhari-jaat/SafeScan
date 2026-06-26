package com.safescan.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.safescan.core.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class OcrEngine {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val barcodeScannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_ALL_FORMATS
        )
        .build()

    private val barcodeScanner = BarcodeScanning.getClient(barcodeScannerOptions)

    suspend fun recognizeText(bitmap: Bitmap): AppResult<List<String>> = withContext(Dispatchers.IO) {
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

    suspend fun scanQR(bitmap: Bitmap): AppResult<String?> = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val barcodes = barcodeScanner.process(image).await()
            
            val result = if (barcodes.isNotEmpty()) {
                barcodes.firstOrNull()?.rawValue
            } else {
                null
            }
            AppResult.Success(result)
        } catch (e: Exception) {
            e.printStackTrace()
            AppResult.Error(e.message ?: "QR scanning failed", e)
        }
    }
}
