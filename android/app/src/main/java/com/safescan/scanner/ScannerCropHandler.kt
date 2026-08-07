package com.safescan.scanner

import android.graphics.Bitmap
import android.util.Log
import com.safescan.data.ScannerMode
import com.safescan.data.ScannerUiState
import com.safescan.domain.model.Point
import com.safescan.domain.usecase.DetectEdgesUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class ScannerCropHandler(
    private val detectEdgesUseCase: DetectEdgesUseCase
) {
    private val isDetectingFrame = AtomicBoolean(false)

    fun detectEdges(
        bitmap: Bitmap,
        currentMode: StateFlow<ScannerMode>,
        uiState: MutableStateFlow<ScannerUiState>,
        scope: CoroutineScope,
        attemptIndex: Int = 0,
        onResult: (List<Point>?) -> Unit
    ) {
        if (bitmap.isRecycled) {
            Log.e("ScannerCropHandler", "detectEdges: Provided bitmap is recycled!")
            onResult(null)
            return
        }

        if (!isDetectingFrame.compareAndSet(false, true)) {
            onResult(null)
            return
        }

        uiState.update { it.copy(isAutoRunning = true) }
        scope.launch(Dispatchers.IO) {
            var points: List<Point>? = null
            try {
                if (!bitmap.isRecycled) {
                    points = detectEdgesUseCase.detectWithOpenCV(bitmap, currentMode.value, isManualCrop = true, attemptIndex = attemptIndex)
                    Log.d("ScannerCropHandler", "detectEdges: Successfully detected corners using OpenCV (attempt: $attemptIndex)")
                }
            } catch (e: Throwable) {
                Log.e("ScannerCropHandler", "detectEdges: OpenCV detection failed", e)
            } finally {
                isDetectingFrame.set(false)
                uiState.update { it.copy(isAutoRunning = false) }
            }

            withContext(Dispatchers.Main) {
                try {
                    onResult(points)
                } catch (e: Throwable) {
                    Log.e("ScannerCropHandler", "detectEdges: Callback onResult failed", e)
                }
            }
        }
    }

    fun detectEdgesWithTFLite(
        bitmap: Bitmap,
        uiState: MutableStateFlow<ScannerUiState>,
        scope: CoroutineScope,
        onResult: (List<Point>?) -> Unit
    ) {
        if (bitmap.isRecycled) {
            Log.e("ScannerCropHandler", "detectEdgesWithTFLite: Provided bitmap is recycled!")
            onResult(null)
            return
        }

        if (!isDetectingFrame.compareAndSet(false, true)) {
            onResult(null)
            return
        }

        uiState.update { it.copy(isAutoRunning = true) }
        scope.launch(Dispatchers.IO) {
            var points: List<Point>? = null
            try {
                if (!bitmap.isRecycled) {
                    points = detectEdgesUseCase.detectWithTFLite(bitmap)
                    Log.d("ScannerCropHandler", "detectEdgesWithTFLite: Successfully detected corners using TFLite")
                }
            } catch (e: Throwable) {
                Log.e("ScannerCropHandler", "detectEdgesWithTFLite: TFLite detection failed", e)
            } finally {
                isDetectingFrame.set(false)
                uiState.update { it.copy(isAutoRunning = false) }
            }

            withContext(Dispatchers.Main) {
                try {
                    onResult(points)
                } catch (e: Throwable) {
                    Log.e("ScannerCropHandler", "detectEdgesWithTFLite: Callback onResult failed", e)
                }
            }
        }
    }
}
