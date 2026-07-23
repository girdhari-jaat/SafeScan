package com.safescan.scanner

import com.safescan.core.DiagnosticsLogger
import com.safescan.data.FlashMode
import com.safescan.data.ScannerMode
import com.safescan.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScannerSettingsHandler(
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope
) {
    private var jpegQualityJob: Job? = null

    fun switchMode(mode: ScannerMode) {
        scope.launch { settingsRepository.setScannerMode(mode) }
    }

    fun toggleAutoCrop(enabled: Boolean) {
        scope.launch {
            settingsRepository.setAutoCrop(enabled)
            DiagnosticsLogger.info("Auto Crop toggled: $enabled")
        }
    }

    fun cycleFlashMode(currentFlashMode: FlashMode) {
        scope.launch {
            val nextMode = when (currentFlashMode) {
                FlashMode.OFF -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.ON
                FlashMode.ON -> FlashMode.TORCH
                FlashMode.TORCH -> FlashMode.OFF
            }
            settingsRepository.setFlashMode(nextMode)
            DiagnosticsLogger.info("Flash mode cycled to: ${nextMode.name}")
        }
    }

    fun toggleFlash(enabled: Boolean) {
        scope.launch {
            settingsRepository.setFlashOn(enabled)
            DiagnosticsLogger.info("Flash toggled: $enabled")
        }
    }

    fun toggleDoubleFocus(enabled: Boolean) {
        scope.launch {
            settingsRepository.setDoubleFocus(enabled)
            DiagnosticsLogger.info("Double Focus toggled: $enabled")
        }
    }

    fun setFocusMode(mode: String) {
        scope.launch {
            settingsRepository.setFocusMode(mode)
            DiagnosticsLogger.info("Focus Mode set to: $mode")
        }
    }

    fun toggleSaveJpg(enabled: Boolean) {
        scope.launch {
            settingsRepository.setSaveJpg(enabled)
            DiagnosticsLogger.info("Save Raw JPG toggled: $enabled")
        }
    }

    fun toggleAutoPdf(enabled: Boolean) {
        scope.launch {
            settingsRepository.setAutoPdf(enabled)
            DiagnosticsLogger.info("Auto-PDF generation toggled: $enabled")
        }
    }

    fun toggleBatchScan(enabled: Boolean) {
        scope.launch {
            settingsRepository.setBatchScan(enabled)
            DiagnosticsLogger.info("Batch Scan toggled: $enabled")
        }
    }

    fun toggleShowGrid(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowGrid(enabled)
            DiagnosticsLogger.info("Show Grid Lines toggled: $enabled")
        }
    }

    fun toggleClickSound(enabled: Boolean) {
        scope.launch {
            settingsRepository.setClickSound(enabled)
            DiagnosticsLogger.info("Capture shutter sound toggled: $enabled")
        }
    }

    fun toggleAutoOrientation(enabled: Boolean) {
        scope.launch {
            settingsRepository.setAutoOrientation(enabled)
            DiagnosticsLogger.info("Auto Orientation toggled: $enabled")
        }
    }

    fun toggleShadowRemove(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShadowRemove(enabled)
            DiagnosticsLogger.info("Shadow Removal toggled: $enabled")
        }
    }

    fun toggleAutoRotation(enabled: Boolean) {
        scope.launch {
            settingsRepository.setAutoRotation(enabled)
            DiagnosticsLogger.info("Auto Rotation toggled: $enabled")
        }
    }

    fun setDefaultFilter(filter: String) {
        scope.launch {
            settingsRepository.setDefaultFilter(filter)
            DiagnosticsLogger.info("Default Filter set to: $filter")
        }
    }

    fun setUiLanguage(language: String) {
        scope.launch {
            settingsRepository.setUiLanguage(language)
            DiagnosticsLogger.info("UI Language set to: $language")
        }
    }

    fun setVibrateOnCapture(enabled: Boolean) {
        scope.launch {
            settingsRepository.setVibrateOnCapture(enabled)
            DiagnosticsLogger.info("Vibrate On Capture toggled: $enabled")
        }
    }

    fun setSaveToGallery(enabled: Boolean) {
        scope.launch {
            settingsRepository.setSaveToGallery(enabled)
            DiagnosticsLogger.info("Save To Gallery toggled: $enabled")
        }
    }

    fun toggleLiveDetect(enabled: Boolean) {
        scope.launch {
            settingsRepository.setLiveDetect(enabled)
            DiagnosticsLogger.info("Live Edge Detection toggled: $enabled")
        }
    }

    fun toggleBatterySaver(enabled: Boolean) {
        scope.launch {
            settingsRepository.setBatterySaver(enabled)
            DiagnosticsLogger.info("Battery Saver toggled: $enabled")
        }
    }

    fun toggleStartWithCamera(enabled: Boolean) {
        scope.launch {
            settingsRepository.setStartWithCamera(enabled)
            DiagnosticsLogger.info("Start with Camera toggled: $enabled")
        }
    }

    fun toggleUsePhoneCamera(enabled: Boolean) {
        scope.launch {
            settingsRepository.setUsePhoneCamera(enabled)
            DiagnosticsLogger.info("Use Phone Camera toggled: $enabled")
        }
    }

    fun toggleUseNativeScanner(enabled: Boolean) {
        scope.launch {
            settingsRepository.setUseNativeScanner(enabled)
            DiagnosticsLogger.info("ML Kit Native Scanner toggled: $enabled")
        }
    }

    fun setHdMode(mode: String) {
        scope.launch {
            settingsRepository.setHdMode(mode)
            DiagnosticsLogger.info("Capture Quality set to: $mode")
        }
    }

    fun setDpi(value: Float) {
        scope.launch {
            settingsRepository.setDpi(value)
            DiagnosticsLogger.info("Export DPI resolution set to: ${value.toInt()}")
        }
    }

    fun setJpegQuality(value: Float) {
        jpegQualityJob?.cancel()
        jpegQualityJob = scope.launch {
            delay(150)
            settingsRepository.setJpegQuality(value)
            DiagnosticsLogger.info("Export JPEG Quality set to: ${value.toInt()}%")
        }
    }

    fun setPdfFilename(value: String) {
        scope.launch {
            settingsRepository.setPdfFilename(value)
            DiagnosticsLogger.info("Default PDF filename set to: '$value'")
        }
    }

    fun setWizardDontShowAgain(enabled: Boolean) {
        scope.launch {
            settingsRepository.setWizardDontShowAgain(enabled)
            DiagnosticsLogger.info("Wizard Don't Show Again set to: $enabled")
        }
    }

    fun setWizardWarp(warp: String) {
        scope.launch {
            settingsRepository.setWizardWarp(warp)
            DiagnosticsLogger.info("Wizard Warp set to: $warp")
        }
    }

    fun setWizardRotation(rotation: String) {
        scope.launch {
            settingsRepository.setWizardRotation(rotation)
            DiagnosticsLogger.info("Wizard Rotation set to: $rotation")
        }
    }

    fun setWizardManualCrop(enabled: Boolean) {
        scope.launch {
            settingsRepository.setWizardManualCrop(enabled)
            DiagnosticsLogger.info("Wizard Manual Crop set to: $enabled")
        }
    }

    fun setPageSize(value: String) {
        scope.launch {
            settingsRepository.setPageSize(value)
            DiagnosticsLogger.info("Export Page Size set to: $value")
        }
    }

    fun setPdfOrientation(value: String) {
        scope.launch {
            settingsRepository.setPdfOrientation(value)
            DiagnosticsLogger.info("Export PDF Orientation set to: $value")
        }
    }
}
