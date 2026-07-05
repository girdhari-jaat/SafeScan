package com.safescan.ocr

import android.graphics.Bitmap
import com.safescan.core.AppResult

interface OcrScanner {
    suspend fun recognizeText(bitmap: Bitmap): AppResult<List<String>>
}

interface BarcodeScanner {
    suspend fun scanQR(bitmap: Bitmap): AppResult<String?>
}
