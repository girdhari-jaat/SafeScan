package com.safescan.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
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

class OcrEngine(private val context: Context) {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val barcodeScannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_ALL_FORMATS
        )
        .build()
    private val barcodeScanner = BarcodeScanning.getClient(barcodeScannerOptions)

    init {
        // Dynamically trigger module installation on app startup / engine initialization
        triggerModulesDownload()
    }

    private fun triggerModulesDownload() {
        try {
            val moduleInstallClient = ModuleInstall.getClient(context)
            val request = ModuleInstallRequest.newBuilder()
                .addApi(textRecognizer)
                .addApi(barcodeScanner)
                .build()

            moduleInstallClient.installModules(request)
                .addOnSuccessListener { response ->
                    if (response.areModulesAlreadyInstalled()) {
                        Log.d("OcrEngine", "ML Kit Modules are already installed on this device.")
                    } else {
                        Log.d("OcrEngine", "ML Kit Modules download has been triggered in the background.")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("OcrEngine", "Failed to initiate dynamic download of ML Kit Modules", e)
                }
        } catch (e: Exception) {
            Log.e("OcrEngine", "Error initializing Google Play Services ModuleInstall", e)
        }
    }

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
            val errorMessage = if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
                triggerModulesDownload() // re-trigger download in background
                "Text recognition module is downloading dynamically from Google Play Services. Please try again in a few seconds."
            } else {
                e.message ?: "Text recognition failed"
            }
            AppResult.Error(errorMessage, e)
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
            val errorMessage = if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
                triggerModulesDownload() // re-trigger download in background
                "QR/Barcode scanner module is downloading dynamically from Google Play Services. Please try again in a few seconds."
            } else {
                e.message ?: "QR scanning failed"
            }
            AppResult.Error(errorMessage, e)
        }
    }
}
