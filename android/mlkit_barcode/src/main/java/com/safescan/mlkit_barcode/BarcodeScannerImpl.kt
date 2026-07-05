package com.safescan.mlkit_barcode

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.safescan.core.AppResult
import com.safescan.ocr.BarcodeScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class BarcodeScannerImpl(private val context: Context) : BarcodeScanner {
    private val barcodeScannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_ALL_FORMATS
        )
        .build()
    private val barcodeScanner = BarcodeScanning.getClient(barcodeScannerOptions)

    override suspend fun scanQR(bitmap: Bitmap): AppResult<String?> = withContext(Dispatchers.IO) {
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
