package com.safescan.android.scanner

import android.graphics.Bitmap
import com.safescan.android.ml.local.LocalMLEngine

enum class ScannerEngineType {
    OPENCV_CANNY,
    LOCAL_ML
}

class DocumentScanner(
    private val openCVScanner: OpenCVScanner = OpenCVScanner(),
    private val localMLEngine: LocalMLEngine = LocalMLEngine()
) {
    var currentEngine: ScannerEngineType = ScannerEngineType.OPENCV_CANNY

    fun detectDocument(bitmap: Bitmap): Quadrilateral? {
        return when (currentEngine) {
            ScannerEngineType.OPENCV_CANNY -> openCVScanner.findDocumentQuadrilateral(bitmap)
            ScannerEngineType.LOCAL_ML -> localMLEngine.detectCorners(bitmap)
        }
    }

    fun cropAndTransform(bitmap: Bitmap, quad: Quadrilateral): Bitmap {
        // Perspective transform is handled by OpenCV for both engines
        return openCVScanner.cropDocument(bitmap, quad)
    }
}
