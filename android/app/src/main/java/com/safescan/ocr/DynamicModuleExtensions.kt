package com.safescan.ocr

import android.app.Activity
import android.widget.Toast

fun Activity.downloadOcrModule(onComplete: () -> Unit) {
    Toast.makeText(this, "OCR Module enabled in Offline Zero-Dependency Mode!", Toast.LENGTH_SHORT).show()
    onComplete()
}

fun Activity.downloadBarcodeModule(onComplete: () -> Unit) {
    Toast.makeText(this, "Barcode/QR Module enabled in Offline Zero-Dependency Mode!", Toast.LENGTH_SHORT).show()
    onComplete()
}

