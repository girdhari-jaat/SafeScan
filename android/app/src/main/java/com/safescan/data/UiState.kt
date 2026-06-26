package com.safescan.data

import android.graphics.Bitmap

// IMPROVEMENT: Extracted ScannerUiState to separate file with isAutoRunning and offline error properties
data class ScannerUiState(
    val isLoading: Boolean = false,
    val isAutoRunning: Boolean = false,
    val error: String? = null,
    val scannedBitmap: Bitmap? = null,
    val currentEngine: com.safescan.scanner.ScannerEngineType = com.safescan.scanner.ScannerEngineType.OPENCV
) {
    // IMPROVEMENT: Added compatibility property for legacy code that looks for errorMessage
    val errorMessage: String? get() = error
}
