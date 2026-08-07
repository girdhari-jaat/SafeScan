package com.safescan.ui

import android.util.Log
import android.view.View
import androidx.camera.core.ImageCapture
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ScannerObservers(
    private val fragment: ScannerFragment
) {
    fun setupObservers() {
        val viewModel = fragment.viewModel

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val binding = fragment.binding ?: return@collect
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                    state.scannedBitmap?.let { bitmap ->
                        binding.resultImageView.visibility = View.VISIBLE
                        binding.resultImageView.setImageBitmap(bitmap)
                        binding.previewView.visibility = View.GONE
                    } ?: run {
                        binding.resultImageView.visibility = View.GONE
                        if (fragment.currentViewMode == ScannerFragment.FragmentViewMode.SCANNER && fragment.cameraController.shouldCameraBeOn()) {
                            binding.previewView.visibility = View.VISIBLE
                        } else {
                            binding.previewView.visibility = View.GONE
                        }
                    }

                    state.errorMessage?.let { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Observe flash/torch state to update physical camera on-the-fly without restart
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flashMode.collect { mode ->
                    val torchOn = mode == com.safescan.data.FlashMode.TORCH

                    try {
                        fragment.cameraControl?.enableTorch(torchOn)
                    } catch (e: Exception) {
                        Log.e("ScannerObservers", "Failed to update torch", e)
                    }
                    fragment.imageCapture?.flashMode = when (mode) {
                        com.safescan.data.FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                        com.safescan.data.FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                        com.safescan.data.FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF // Torch uses cameraControl
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                    fragment.binding?.btnFlash?.alpha = if (mode != com.safescan.data.FlashMode.OFF) 1.0f else 0.5f
                }
            }
        }

        // Observe liveDetect state to clear corners overlay when disabled
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveDetect.collect { enabled ->
                    if (!enabled) {
                        fragment.binding?.overlayView?.updateCorners(null)
                        fragment.cameraController.motionDetector?.stop()
                    } else {
                        fragment.cameraController.motionDetector?.start()
                    }
                }
            }
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.autoCaptureEvent.collect {
                    if (fragment.currentViewMode == ScannerFragment.FragmentViewMode.SCANNER && !viewModel.isEditing.value && !viewModel.isCropping.value) {
                        fragment.focusAndTakePhoto(isAutoCapture = true)
                    }
                }
            }
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.isEditing,
                    viewModel.isCropping,
                    viewModel.isSettingsOpen,
                    viewModel.isDocumentOpenedFromLibrary,
                    viewModel.isGridViewVisible,
                    viewModel.usePhoneCamera,
                    viewModel.useNativeScanner
                ) { _ ->
                    // Trigger state update when any of these change
                }.collect {
                    fragment.updateCameraState()
                }
            }
        }
    }
}
