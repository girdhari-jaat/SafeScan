package com.safescan.ui

import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ScannerNavigation(
    private val fragment: ScannerFragment
) {
    private var lastBackPressedTime = 0L

    fun setupNavigation() {
        val viewModel = fragment.viewModel

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val isEditing = viewModel.isEditing.value
                val isCropping = viewModel.isCropping.value
                val isSettingsOpen = viewModel.isSettingsOpen.value
                val isGridViewVisible = viewModel.isGridViewVisible.value

                if (isGridViewVisible) {
                    viewModel.isGridViewVisible.value = false
                } else if (isSettingsOpen) {
                    viewModel.isSettingsOpen.value = false
                } else if (isCropping) {
                    viewModel.isCropping.value = false
                } else if (isEditing) {
                    viewModel.isEditing.value = false
                } else if (fragment.currentViewMode == ScannerFragment.FragmentViewMode.WIZARD) {
                    fragment.updateViewMode(ScannerFragment.FragmentViewMode.LIBRARY)
                } else if (fragment.currentViewMode == ScannerFragment.FragmentViewMode.SCANNER) {
                    fragment.updateViewMode(ScannerFragment.FragmentViewMode.LIBRARY)
                } else if (fragment.currentViewMode == ScannerFragment.FragmentViewMode.LIBRARY) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastBackPressedTime < 2000) {
                        isEnabled = false
                        fragment.requireActivity().finish()
                    } else {
                        lastBackPressedTime = currentTime
                        Toast.makeText(fragment.context, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, callback)

        // Check if user set "Start with Camera" in settings; otherwise remain on Library
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val startWithCam = viewModel.settingsRepository.startWithCameraFlow.first()
                if (startWithCam) {
                    fragment.checkPermissionAndStartScanner()
                }
            } catch (e: Exception) {
                // Keep default LIBRARY mode initialized on startup
            }
        }
    }
}
