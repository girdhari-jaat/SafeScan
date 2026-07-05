package com.safescan.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.safescan.core.AppResult

class OcrEngine(private val context: Context) {

    private val splitInstallManager = SplitInstallManagerFactory.create(context)

    suspend fun recognizeText(bitmap: Bitmap): AppResult<List<String>> {
        if (!splitInstallManager.installedModules.contains("mlkit_ocr")) {
            showToastOnMainThread("Download Required for OCR Module")
            return AppResult.Error("OCR Module is not downloaded. Please download it from Settings.")
        }

        return try {
            val clazz = Class.forName("com.safescan.mlkit_ocr.OcrScannerImpl")
            val constructor = clazz.getDeclaredConstructor(Context::class.java)
            val instance = constructor.newInstance(context) as OcrScanner
            instance.recognizeText(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            AppResult.Error("Failed to load OCR scanner implementation dynamically: ${e.message}", e)
        }
    }

    suspend fun scanQR(bitmap: Bitmap): AppResult<String?> {
        if (!splitInstallManager.installedModules.contains("mlkit_barcode")) {
            showToastOnMainThread("Download Required for Barcode Module")
            return AppResult.Error("Barcode Module is not downloaded. Please download it from Settings.")
        }

        return try {
            val clazz = Class.forName("com.safescan.mlkit_barcode.BarcodeScannerImpl")
            val constructor = clazz.getDeclaredConstructor(Context::class.java)
            val instance = constructor.newInstance(context) as BarcodeScanner
            instance.scanQR(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            AppResult.Error("Failed to load Barcode scanner implementation dynamically: ${e.message}", e)
        }
    }

    private fun showToastOnMainThread(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
