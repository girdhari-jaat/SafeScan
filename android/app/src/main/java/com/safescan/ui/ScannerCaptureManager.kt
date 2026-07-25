package com.safescan.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.PointF
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.safescan.core.DiagnosticsLogger
import com.safescan.core.ScannerDebugLogger
import com.safescan.data.ScannerMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class ScannerCaptureManager(
    private val fragment: ScannerFragment
) {
    var isCapturingPhoto = false
        private set
    var photoUri: Uri? = null

    fun openPhoneCamera() {
        val context = fragment.requireContext()
        val photoFile = File(context.cacheDir, "temp_camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile
        )
        photoUri = uri
        fragment.takePictureLauncher.launch(uri)
    }

    fun processCapturedPhonePhoto(uri: Uri) {
        try {
            val context = fragment.requireContext()
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, uri)
                )
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
            var softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            if (softwareBitmap != bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }

            val exifRotation = ScannerImageUtils.getExifRotation(context, uri)
            if (exifRotation != 0) {
                val matrix = Matrix().apply { postRotate(exifRotation.toFloat()) }
                val rotated = Bitmap.createBitmap(softwareBitmap, 0, 0, softwareBitmap.width, softwareBitmap.height, matrix, true)
                softwareBitmap.recycle()
                softwareBitmap = rotated
            }

            if (fragment.viewModel.autoRotation.value) {
                softwareBitmap = ScannerImageUtils.autoRotateForMode(softwareBitmap, fragment.viewModel.currentMode.value)
            }

            fragment.viewModel.onCapture(softwareBitmap, isNativeScanned = true)
        } catch (e: Exception) {
            Log.e("ScannerCaptureManager", "Failed to process captured image", e)
        }
    }

    fun focusAndTakePhoto(isAutoCapture: Boolean = false) {
        if (isCapturingPhoto) return
        fragment.cameraController.scannerStateMachine.isFocusing = true

        val binding = fragment.binding
        if (binding == null) {
            fragment.cameraController.scannerStateMachine.isFocusing = false
            takePhoto()
            return
        }

        val previewView = binding.previewView
        val mappedCorners = binding.overlayView.getCorners()

        var centerX = previewView.width / 2f
        var centerY = previewView.height / 2f

        if (mappedCorners != null && mappedCorners.size == 4) {
            var sumX = 0f
            var sumY = 0f
            for (p in mappedCorners) {
                sumX += p.x
                sumY += p.y
            }
            centerX = sumX / 4f
            centerY = sumY / 4f
        }

        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(centerX, centerY)
        val flags = if (isAutoCapture) FocusMeteringAction.FLAG_AF else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        val action = FocusMeteringAction.Builder(
            point,
            flags
        ).setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS).build()

        fragment.cameraControl?.startFocusAndMetering(action)
        fragment.cameraController.scannerStateMachine.isFocusing = false
        takePhoto()
    }

    fun runAccuracyTest(previewCorners: List<PointF>?) {
        Log.d("ScannerTest", "=== ACCURACY TEST START ===")
        DiagnosticsLogger.info("ScannerTest: === ACCURACY TEST START ===")
        val cornersToLog = previewCorners ?: fragment.binding.overlayView.getCorners()
        if (cornersToLog.isNullOrEmpty()) {
            Log.d("ScannerTest", "Corners: No document corners detected on overlay")
            DiagnosticsLogger.info("ScannerTest: Corners: No document corners detected on overlay")
        } else {
            cornersToLog.forEachIndexed { i, p ->
                Log.d("ScannerTest", "Corner $i: View(${p.x},${p.y})")
                DiagnosticsLogger.info("ScannerTest: Corner $i: View(${p.x},${p.y})")
            }
        }
        Log.d("ScannerTest", "=== ACCURACY TEST END ===")
        DiagnosticsLogger.info("ScannerTest: === ACCURACY TEST END ===")
    }

    fun takePhoto() {
        if (isCapturingPhoto) {
            Log.w("ScannerCaptureManager", "takePhoto ignored: photo capture already in progress")
            return
        }
        isCapturingPhoto = true

        val viewModel = fragment.viewModel

        if (viewModel.usePhoneCamera.value) {
            fragment.cameraController.scannerStateMachine.isFocusing = false
            isCapturingPhoto = false
            openPhoneCamera()
            return
        }
        if (viewModel.useNativeScanner.value) {
            fragment.cameraController.scannerStateMachine.isFocusing = false
            isCapturingPhoto = false
            val maxPages = when (viewModel.currentMode.value) {
                ScannerMode.CARD -> 2
                ScannerMode.GRID -> 8
                else -> 150
            }
            fragment.openDocumentScanner(maxPages)
            return
        }

        val imageCapture = fragment.imageCapture ?: run {
            fragment.cameraController.scannerStateMachine.isFocusing = false
            isCapturingPhoto = false
            return
        }
        val currentContext = fragment.context ?: run {
            fragment.cameraController.scannerStateMachine.isFocusing = false
            isCapturingPhoto = false
            return
        }
        val binding = fragment.binding

        binding.progressBar.visibility = View.VISIBLE

        if (viewModel.clickSound.value) {
            try {
                fragment.shutterSound?.play(MediaActionSound.SHUTTER_CLICK)
            } catch (e: Exception) {
                Log.e("ScannerCaptureManager", "Failed to play shutter sound", e)
            }
        }

        if (viewModel.vibrateOnCapture.value) {
            com.safescan.utils.HapticFeedbackHelper.triggerHaptic(binding.root, currentContext)
        }

        runAccuracyTest(fragment.binding.overlayView.getCorners())

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(currentContext),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        fragment.cameraControl?.cancelFocusAndMetering()
                        val rawBitmap = imageProxy.toBitmap()
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        ScannerDebugLogger.logCameraRotation(rotationDegrees)
                        
                        var finalBitmap = rawBitmap
                        val needsRotation = rotationDegrees != 0
                        val needsSoftware = finalBitmap.config != Bitmap.Config.ARGB_8888 || !finalBitmap.isMutable

                        if (needsRotation) {
                            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                            if (rotated != rawBitmap) {
                                rawBitmap.recycle()
                            }
                            finalBitmap = if (rotated.config != Bitmap.Config.ARGB_8888 || !rotated.isMutable) {
                                val soft = rotated.copy(Bitmap.Config.ARGB_8888, true)
                                if (soft != rotated) rotated.recycle()
                                soft
                            } else {
                                rotated
                            }
                        } else if (needsSoftware) {
                            val soft = rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
                            if (soft != rawBitmap) rawBitmap.recycle()
                            finalBitmap = soft
                        }

                        if (viewModel.autoRotation.value || viewModel.currentMode.value == ScannerMode.CARD || viewModel.currentMode.value == ScannerMode.GRID) {
                            val autoRotated = ScannerImageUtils.autoRotateForMode(finalBitmap, viewModel.currentMode.value)
                            if (autoRotated !== finalBitmap) {
                                finalBitmap.recycle()
                                finalBitmap = autoRotated
                            }
                        }

                        viewModel.onCapture(finalBitmap)
                    } catch (e: Exception) {
                        Log.e("ScannerCaptureManager", "Failed to process photo capture", e)
                    } finally {
                        imageProxy.close()
                        isCapturingPhoto = false
                        fragment.binding.progressBar.visibility = View.GONE
                        fragment.cameraController.scannerStateMachine.isFocusing = false
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    try {
                        Log.e("ScannerCaptureManager", "Photo capture failed: ${exception.message}", exception)
                        fragment.context?.let { ctx ->
                            Toast.makeText(ctx, "Capture failed: ${exception.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        fragment.cameraControl?.cancelFocusAndMetering()
                        fragment.cameraController.scannerStateMachine.isFocusing = false
                        isCapturingPhoto = false
                        fragment.binding.progressBar.visibility = View.GONE
                    }
                }
            }
        )
    }
}
