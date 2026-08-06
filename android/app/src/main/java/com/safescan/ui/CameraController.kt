package com.safescan.ui

import android.graphics.PointF
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.safescan.domain.model.Point
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraController(
    private val fragment: ScannerFragment
) {
    var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    var imageCapture: ImageCapture? = null
    var cameraControl: CameraControl? = null
    var cameraInfo: CameraInfo? = null
    var cameraProvider: ProcessCameraProvider? = null
    var imageAnalysis: ImageAnalysis? = null

    var isCameraBound = false
    var lastBoundMode: com.safescan.data.ScannerMode? = null
    var lastBoundHdMode: String? = null
    var lastAnalysisTime = 0L
    var isTargetLocked = false
    var motionDetector: com.safescan.scanner.DeviceMotionDetector? = null

    val scannerStateMachine = com.safescan.scanner.ScannerStateMachine(
        onDocumentDetectedStateChanged = { isDetected ->
            fragment.viewModel.isDocumentDetected.value = isDetected
        },
        onAutoCaptureTriggered = {
            fragment.viewModel.triggerAutoCapture()
        },
        onDetectionStateChanged = { state ->
            fragment.viewModel.detectionState.value = state
        }
    )

    fun shouldCameraBeOn(): Boolean {
        if (!fragment.isAdded) return false
        val viewModel = fragment.viewModel
        val isEditing = viewModel.isEditing.value
        val isCropping = viewModel.isCropping.value
        val isSettingsOpen = viewModel.isSettingsOpen.value
        val isDocOpenFromLib = viewModel.isDocumentOpenedFromLibrary.value
        val isGridViewVisible = viewModel.isGridViewVisible.value
        val isScannerMode = fragment.currentViewMode == ScannerFragment.FragmentViewMode.SCANNER
        val usePhoneCam = viewModel.usePhoneCamera.value
        val useNativeScan = viewModel.useNativeScanner.value

        return isScannerMode &&
                !isDocOpenFromLib &&
                !isGridViewVisible &&
                !isEditing &&
                !isCropping &&
                !isSettingsOpen &&
                !usePhoneCam &&
                !useNativeScan &&
                fragment.permissionManager.allPermissionsGranted()
    }

    fun setupCamera() {
        if (!fragment.isAdded) return
        val currentContext = fragment.context ?: return
        if (motionDetector == null) {
            motionDetector = com.safescan.scanner.DeviceMotionDetector(currentContext)
        }
        val binding = fragment.binding ?: return
        val viewModel = fragment.viewModel

        if (shouldCameraBeOn()) {
            val mode = viewModel.currentMode.value
            val hdModeStr = viewModel.hdMode.value
            if (isCameraBound && lastBoundMode == mode && lastBoundHdMode == hdModeStr) {
                binding.previewView.visibility = View.VISIBLE
                return
            }
            startCamera()
        } else {
            binding.previewView.visibility = View.GONE
            binding.overlayView.clear()
            val provider = this.cameraProvider
            if (provider != null && isCameraBound) {
                try {
                    provider.unbindAll()
                    isCameraBound = false
                } catch (e: Exception) {
                    Log.e("CameraController", "Failed to unbind camera instantly", e)
                }
            } else if (!isCameraBound) {
                // Camera already unbound
            } else {
                try {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(currentContext)
                    cameraProviderFuture.addListener({
                        try {
                            val p = cameraProviderFuture.get()
                            this.cameraProvider = p
                            p.unbindAll()
                            isCameraBound = false
                        } catch (e: Exception) {
                            Log.e("CameraController", "Failed to unbind camera in updateCameraState listener", e)
                        }
                    }, ContextCompat.getMainExecutor(currentContext))
                } catch (e: Exception) {
                    Log.e("CameraController", "Error getting camera provider in updateCameraState listener", e)
                }
            }
        }
    }

    private fun startCamera() {
        if (!fragment.isAdded) return
        val currentContext = fragment.context ?: return
        if (ContextCompat.checkSelfPermission(currentContext, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(currentContext)

        cameraProviderFuture.addListener({
            try {
                val binding = fragment.binding ?: return@addListener
                val provider: ProcessCameraProvider = cameraProviderFuture.get()
                this.cameraProvider = provider

                if (!shouldCameraBeOn()) {
                    provider.unbindAll()
                    isCameraBound = false
                    binding.previewView.visibility = View.GONE
                    binding.overlayView.clear()
                    return@addListener
                } else {
                    binding.previewView.visibility = View.VISIBLE
                }

                bindUseCases(provider)

            } catch (exc: Exception) {
                Log.e("CameraController", "CameraX initialization or binding failed", exc)
                fragment.context?.let { ctx ->
                    Toast.makeText(ctx, "Failed to initialize camera: ${exc.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }

        }, ContextCompat.getMainExecutor(currentContext))
    }

    fun bindUseCases(cameraProvider: ProcessCameraProvider) {
        val currentContext = fragment.context ?: return
        val binding = fragment.binding ?: return
        val viewModel = fragment.viewModel

        if (!shouldCameraBeOn()) {
            cameraProvider.unbindAll()
            isCameraBound = false
            binding.previewView.visibility = View.GONE
            binding.overlayView.clear()
            return
        }

        val mode = viewModel.currentMode.value
        val hdModeStr = viewModel.hdMode.value
        val currentRatio = com.safescan.scanner.CameraHardwareConfig.getTargetRatio(currentContext, mode)
        val captureSettings = com.safescan.scanner.CameraHardwareConfig.getCaptureSettings(currentContext, mode, hdModeStr)
        Log.d("CameraController", "Negotiated CameraX: Mode=$mode, HD=$hdModeStr -> Target=${captureSettings.targetSize.width}x${captureSettings.targetSize.height}")
        com.safescan.core.ScannerDebugLogger.logCameraX("${captureSettings.targetSize.width}x${captureSettings.targetSize.height} (${captureSettings.megapixelsLabel})", mode.name)

        val previewSelector = com.safescan.scanner.CameraHardwareConfig.getPreviewResolutionSelector(currentContext, mode)
        val analysisSelector = com.safescan.scanner.CameraHardwareConfig.getImageAnalysisResolutionSelector(currentContext, mode)

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(previewSelector)

        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(captureSettings.captureMode)
            .setResolutionSelector(captureSettings.resolutionSelector)
            .build()

        this.imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(analysisSelector)
            .build()

        setupAnalyzer()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        cameraProvider.unbindAll()

        val camera = cameraProvider.bindToLifecycle(
            fragment.viewLifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis
        )

        isCameraBound = true
        lastBoundMode = mode
        lastBoundHdMode = hdModeStr

        cameraControl = camera.cameraControl
        cameraInfo = camera.cameraInfo

        cameraControl?.enableTorch(viewModel.flashOn.value)
    }

    fun setupAnalyzer() {
        val viewModel = fragment.viewModel
        this.imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
            val isLiveDetectOn = viewModel.liveDetect.value
            val isBatterySaverOn = viewModel.batterySaver.value
            val isLoading = viewModel.uiState.value.isLoading
            val isOverlayActive = !viewModel.isEditing.value && !viewModel.isCropping.value && !viewModel.isSettingsOpen.value && !viewModel.isDocumentOpenedFromLibrary.value && !viewModel.isGridViewVisible.value && !isLoading
            if (isLiveDetectOn && !isBatterySaverOn && isOverlayActive) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val isDocDetected = viewModel.isDocumentDetected.value
                    val delayThreshold = if (isDocDetected) 60L else 40L

                    if (currentTime - lastAnalysisTime < delayThreshold) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastAnalysisTime = currentTime

                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val width = imageProxy.width
                    val height = imageProxy.height

                    val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
                    val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

                    Log.d("LiveEdgeDetection", "Analyzer received frame: ${width}x${height}, rot=$rotationDegrees")

                    fragment.liveEdgeDetectionEngine.process(imageProxy, viewModel.documentScanner, viewModel.uiState.value.currentEngine, viewModel.currentMode.value) { corners, sharpness ->
                        val isStable = motionDetector?.isDeviceStable ?: true
                        scannerStateMachine.processFrame(corners, sharpness, viewModel.autoCapture.value, isStable, width, height)

                        val activeQuad = scannerStateMachine.getHeldPoints()
                        val mappedPoints = if (activeQuad != null && activeQuad.isNotEmpty()) {
                            val mapped = mapPointsToPreviewView(activeQuad, width, height, rotationDegrees)
                            if (mapped.size == 4) mapped else null
                        } else {
                            null
                        }

                        fragment.activity?.runOnUiThread {
                            val bindingObj = fragment.binding
                            val isOverlayStillActive = !viewModel.isEditing.value &&
                                    !viewModel.isCropping.value &&
                                    !viewModel.isSettingsOpen.value &&
                                    !viewModel.isDocumentOpenedFromLibrary.value &&
                                    !viewModel.isGridViewVisible.value &&
                                    fragment.currentViewMode == ScannerFragment.FragmentViewMode.SCANNER
                            val isDetectedState = scannerStateMachine.detectionState != com.safescan.scanner.DetectionState.IDLE &&
                                                  scannerStateMachine.detectionState != com.safescan.scanner.DetectionState.COOLDOWN
                            
                            val showOverlay = isOverlayStillActive && isDetectedState && mappedPoints != null
                            
                            if (bindingObj != null) {
                                if (showOverlay) {
                                    bindingObj.overlayView.visibility = View.VISIBLE
                                    bindingObj.overlayView.updateCorners(mappedPoints)
                                } else {
                                    bindingObj.overlayView.visibility = View.GONE
                                    bindingObj.overlayView.updateCorners(null)
                                }
                            }
                            if (isDetectedState) {
                                if (!isTargetLocked) {
                                    isTargetLocked = true
                                    if (viewModel.vibrateOnCapture.value) {
                                        bindingObj?.root?.let { com.safescan.utils.HapticFeedbackHelper.triggerHaptic(it) }
                                    }
                                }
                            } else {
                                isTargetLocked = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CameraController", "Live detection error", e)
                    imageProxy.close()
                }
            } else {
                imageProxy.close()
                isTargetLocked = false
                scannerStateMachine.resetDetection()
                fragment.activity?.runOnUiThread {
                    fragment.binding?.overlayView?.clear()
                }
            }
        }
    }

    fun mapPointsToPreviewView(
        points: List<Point>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        rotationDegrees: Int
    ): List<PointF> {
        val binding = fragment.binding ?: return emptyList()
        val viewWidth = binding.previewView.width.toFloat()
        val viewHeight = binding.previewView.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return emptyList()

        val rotatedWidth: Float
        val rotatedHeight: Float
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            rotatedWidth = bitmapHeight.toFloat()
            rotatedHeight = bitmapWidth.toFloat()
        } else {
            rotatedWidth = bitmapWidth.toFloat()
            rotatedHeight = bitmapHeight.toFloat()
        }

        val frameRatio = rotatedWidth / rotatedHeight
        val viewRatio = viewWidth / viewHeight

        val isFillCenter = binding.previewView.scaleType == androidx.camera.view.PreviewView.ScaleType.FILL_CENTER

        val scale: Float
        val dx: Float
        val dy: Float

        if (isFillCenter) {
            if (frameRatio > viewRatio) {
                scale = viewHeight / rotatedHeight
                dx = (viewWidth - rotatedWidth * scale) / 2f
                dy = 0f
            } else {
                scale = viewWidth / rotatedWidth
                dx = 0f
                dy = (viewHeight - rotatedHeight * scale) / 2f
            }
        } else {
            if (frameRatio > viewRatio) {
                scale = viewWidth / rotatedWidth
                dx = 0f
                dy = (viewHeight - rotatedHeight * scale) / 2f
            } else {
                scale = viewHeight / rotatedHeight
                dx = (viewWidth - rotatedWidth * scale) / 2f
                dy = 0f
            }
        }

        return points.map { pt ->
            val normX = pt.x.toFloat() / bitmapWidth
            val normY = pt.y.toFloat() / bitmapHeight

            val rotatedX: Float
            val rotatedY: Float
            when (rotationDegrees) {
                90 -> {
                    rotatedX = 1f - normY
                    rotatedY = normX
                }
                180 -> {
                    rotatedX = 1f - normX
                    rotatedY = 1f - normY
                }
                270 -> {
                    rotatedX = normY
                    rotatedY = 1f - normX
                }
                else -> {
                    rotatedX = normX
                    rotatedY = normY
                }
            }

            val screenX = dx + (rotatedX * rotatedWidth * scale)
            val screenY = dy + (rotatedY * rotatedHeight * scale)

            PointF(screenX, screenY)
        }
    }

    fun unbindAll() {
        motionDetector?.stop()
        motionDetector = null
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        isCameraBound = false
        isTargetLocked = false
        fragment.binding?.overlayView?.clear()
        fragment.binding?.overlayView?.visibility = View.GONE
        scannerStateMachine.resetDetection()
    }

    fun destroy() {
        unbindAll()
        cameraExecutor.shutdown()
    }
}
