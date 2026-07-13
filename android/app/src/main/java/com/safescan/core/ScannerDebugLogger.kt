package com.safescan.core

import android.util.Log

object ScannerDebugLogger {
    private const val TAG = "ScannerDebug"

    fun logEnter(functionName: String) {
        val msg = "ENTER: $functionName"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logExit(functionName: String) {
        val msg = "EXIT: $functionName"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logCameraX(resolution: String, mode: String) {
        val msg = "[CameraX] Negotiated Resolution: $resolution, Mode=$mode"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logCameraRotation(rotation: Int) {
        val msg = "[CameraX] Rotation: $rotation degrees"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logCapture(slot: String) {
        val msg = "[Capture] Image captured for slot: $slot"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logAutoRotation(degrees: Float) {
        val msg = "[AutoRotation] Applied rotation: $degrees degrees to bitmap"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logLiveEdge(contours: Int) {
        val msg = "[LiveEdge] Frame processed. Found contours: $contours"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logLiveEdgePoints(p1: String, p2: String, p3: String, p4: String) {
        val msg = "[LiveEdge] Best contour points: [p1=$p1, p2=$p2, p3=$p3, p4=$p4]"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logLiveEdgeArea(area: Double, percentage: Double) {
        val msg = "[LiveEdge] Contour Area: $area. Area% of Overlay: ${String.format("%.2f", percentage)}%"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logSmoothing(distance: Double) {
        val msg = "[LiveEdge] Smoothing: Prev vs New corner distance: ${String.format("%.2f", distance)} pixels"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logStability(stableCount: Int) {
        val msg = "[Stability] Stable frame count: $stableCount/5"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logAutoCap(inBox: Boolean, sharpness: Double, stable: Int, trigger: Boolean) {
        val msg = "[AutoCap] InBox: $inBox, Sharpness: ${String.format("%.2f", sharpness)}, Stable: $stable. Trigger: $trigger"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logCrop(x: Float, y: Float, w: Float, h: Float) {
        val msg = "[Crop] Overlay Rect: x=${String.format("%.1f", x)}, y=${String.format("%.1f", y)}, w=${String.format("%.1f", w)}, h=${String.format("%.1f", h)}"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logCropRoiSize(w: Int, h: Int) {
        val msg = "[Crop] ROI Cropped size: ${w}x${h}"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logOrientation(ratio: String, targetPageSize: String) {
        val msg = "[Orientation] Detected Image Ratio: $ratio. Target Page Size: $targetPageSize"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logWarpMatrix(w: Int, h: Int) {
        val msg = "[Warp] Perspective transform matrix calculated. Output size: ${w}x${h}"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logWarp4Point(p1: String, p2: String, p3: String, p4: String) {
        val msg = "[Warp] Applied 4-point transform. Corners used: [$p1, $p2, $p3, $p4]"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logFilter(filter: String, timeTakenMs: Long) {
        val msg = "[Filter] Applied filter: $filter. Time taken: ${timeTakenMs}ms"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logSaveThumbnail(path: String) {
        val msg = "[Save] Compressed thumbnail saved. Path: $path"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logSaveFullImage(sizeKb: Long) {
        val msg = "[Save] Full image saved. Size: $sizeKb KB"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logPdfAssemble(pagesCount: Int, pageSize: String) {
        val msg = "[PDF] Assembling $pagesCount pages. Page Size: $pageSize"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logPdfSuccess(path: String, sizeMb: Double) {
        val msg = "[PDF] PDF generated successfully. Path: $path, Size: ${String.format("%.2f", sizeMb)} MB"
        Log.i(TAG, msg)
        DiagnosticsLogger.log("ℹ️ $msg")
    }

    fun logError(module: String, message: String, throwable: Throwable? = null) {
        val fullMsg = "[$module] ERROR: $message"
        if (throwable != null) {
            Log.e(TAG, fullMsg, throwable)
            DiagnosticsLogger.log("🔴 $fullMsg: ${throwable.localizedMessage}")
        } else {
            Log.e(TAG, fullMsg)
            DiagnosticsLogger.log("🔴 $fullMsg")
        }
    }
}
