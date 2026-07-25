package com.safescan.ui

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.camera.core.FocusMeteringAction
import com.safescan.scanner.ScannerEngineType
import java.util.concurrent.TimeUnit

class ScannerUiActions(
    private val fragment: ScannerFragment
) {
    fun setupListeners() {
        val binding = fragment.binding ?: return
        val viewModel = fragment.viewModel

        binding.btnCapture.setOnClickListener {
            fragment.focusAndTakePhoto(isAutoCapture = false)
        }

        binding.btnFlash.setOnClickListener {
            toggleFlash()
        }

        binding.btnSwitchEngine.setOnClickListener {
            val current = viewModel.uiState.value.currentEngine
            val next = if (current == ScannerEngineType.OPENCV) {
                ScannerEngineType.LOCAL_ML
            } else {
                ScannerEngineType.OPENCV
            }
            viewModel.toggleEngine(next)
            fragment.context?.let { ctx ->
                Toast.makeText(ctx, "Engine set to: $next", Toast.LENGTH_SHORT).show()
            }
        }

        val scaleGestureDetector = ScaleGestureDetector(
            fragment.requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cameraInfoObj = fragment.cameraInfo ?: return false
                    val cameraControlObj = fragment.cameraControl ?: return false
                    val currentZoomRatio = cameraInfoObj.zoomState.value?.zoomRatio ?: 1f
                    val delta = detector.scaleFactor
                    val targetZoomRatio = (currentZoomRatio * delta).coerceIn(1f, 10f)
                    cameraControlObj.setZoomRatio(targetZoomRatio)
                    return true
                }
            }
        )

        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (scaleGestureDetector.isInProgress) {
                return@setOnTouchListener true
            }
            if (event.action == MotionEvent.ACTION_DOWN) {
                fragment.captureManager.notifyUserTappedToFocus(event.x, event.y)
                val factory = binding.previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)

                val focusMode = viewModel.focusMode.value
                val action = when (focusMode) {
                    "Double" -> {
                        val centerPoint = factory.createPoint(binding.previewView.width / 2f, binding.previewView.height / 2f)
                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .addPoint(centerPoint, FocusMeteringAction.FLAG_AF)
                            .setAutoCancelDuration(4, TimeUnit.SECONDS)
                            .build()
                    }
                    "Single" -> {
                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                            .setAutoCancelDuration(4, TimeUnit.SECONDS)
                            .build()
                    }
                    "Continuous" -> {
                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                    }
                    else -> null
                }

                if (action != null) {
                    fragment.cameraControl?.startFocusAndMetering(action)
                }
                return@setOnTouchListener true
            }
            true
        }
    }

    fun toggleFlash() {
        fragment.viewModel.cycleFlashMode()
    }
}
