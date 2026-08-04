package com.safescan.scanner

import android.content.Context
import android.graphics.Bitmap
import com.safescan.core.AppResult
import com.safescan.core.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScannerOcrHandler(context: Context) {
    private val ocrEngine = OcrEngine(context)

    fun runOcrOnCurrentBitmap(
        bitmap: Bitmap?,
        isOcrRunning: MutableStateFlow<Boolean>,
        recognizedText: MutableStateFlow<String?>,
        scope: CoroutineScope
    ) {
        val bmp = bitmap ?: return
        isOcrRunning.value = true
        recognizedText.value = null
        DiagnosticsLogger.info("Starting Text Recognition (OCR) off-thread...")
        scope.launch(Dispatchers.IO) {
            val result = ocrEngine.recognizeText(bmp)
            withContext(Dispatchers.Main) {
                isOcrRunning.value = false
                when (result) {
                    is AppResult.Success -> {
                        recognizedText.value = result.data.joinToString("\n")
                        DiagnosticsLogger.info("OCR completed successfully. Recognized ${result.data.size} lines.")
                    }
                    is AppResult.Error -> {
                        recognizedText.value = "Error: ${result.message}"
                        DiagnosticsLogger.error("OCR recognition error: ${result.message}")
                    }
                }
            }
        }
    }

    fun runBarcodeOnCurrentBitmap(
        bitmap: Bitmap?,
        isBarcodeRunning: MutableStateFlow<Boolean>,
        recognizedText: MutableStateFlow<String?>,
        scope: CoroutineScope
    ) {
        val bmp = bitmap ?: return
        isBarcodeRunning.value = true
        recognizedText.value = null
        DiagnosticsLogger.info("Scanning for Barcode/QR Code...")
        scope.launch(Dispatchers.IO) {
            val result = ocrEngine.scanQR(bmp)
            withContext(Dispatchers.Main) {
                isBarcodeRunning.value = false
                when (result) {
                    is AppResult.Success -> {
                        recognizedText.value = result.data ?: "No QR/Barcode found."
                        DiagnosticsLogger.info("QR/Barcode scan completed: ${result.data}")
                    }
                    is AppResult.Error -> {
                        recognizedText.value = "Error: ${result.message}"
                        DiagnosticsLogger.error("QR/Barcode scan error: ${result.message}")
                    }
                }
            }
        }
    }
}
