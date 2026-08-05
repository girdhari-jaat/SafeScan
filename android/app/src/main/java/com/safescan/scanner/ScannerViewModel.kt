package com.safescan.scanner

import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.pow
import dagger.hilt.android.lifecycle.HiltViewModel
import com.safescan.data.FlashMode
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import com.safescan.data.SettingsRepository
import com.safescan.data.ScannerUiState
import com.safescan.core.DiagnosticsLogger
import com.safescan.domain.model.Point
import com.safescan.core.ScannerDebugLogger
import com.safescan.domain.model.Quadrilateral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val scannerEngine: DocumentScannerEngine,
    val settingsRepository: SettingsRepository,
    val documentScanner: DocumentScanner,
    private val detectEdgesUseCase: com.safescan.domain.usecase.DetectEdgesUseCase,
    private val applyFilterUseCase: com.safescan.domain.usecase.ApplyFilterUseCase,
    private val exportPdfUseCase: com.safescan.domain.usecase.ExportPdfUseCase,
    private val manageSlotsUseCase: com.safescan.domain.usecase.ManageSlotsUseCase,
    private val saveDocumentUseCase: com.safescan.domain.usecase.SaveDocumentUseCase
) : ViewModel() {

    // IMPROVEMENT: Using ScannerUiState with isAutoRunning
    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private var lastExportPdfHash: Int = 0
    private var cachedPdfFile: java.io.File? = null

    val isDocumentDetected = MutableStateFlow(false)
    val detectionState = MutableStateFlow(com.safescan.scanner.DetectionState.IDLE)

    val currentMode: StateFlow<ScannerMode> = settingsRepository.scannerModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScannerMode.CARD)
        
    val autoCapture: StateFlow<Boolean> = settingsRepository.autoCaptureFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleAutoCapture(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoCapture(enabled)
            DiagnosticsLogger.info("Auto Capture toggled: $enabled")
        }
    }

    val autoCrop: StateFlow<Boolean> = settingsRepository.autoCropFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
        
    val flashMode: StateFlow<com.safescan.data.FlashMode> = settingsRepository.flashModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.safescan.data.FlashMode.OFF)

    val flashOn: StateFlow<Boolean> = settingsRepository.flashOnFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
        
    val dpi: StateFlow<Float> = settingsRepository.dpiFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 300f)
        
    val jpegQuality: StateFlow<Float> = settingsRepository.jpegQualityFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 80f)
        
    val pdfFilename: StateFlow<String> = settingsRepository.pdfFilenameFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Scan_Document")
        
    val pageSize: StateFlow<String> = settingsRepository.pageSizeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "A4")

    val pdfOrientation: StateFlow<String> = settingsRepository.pdfOrientationFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Auto")

    val doubleFocusEnabled: StateFlow<Boolean> = settingsRepository.doubleFocusFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val focusMode: StateFlow<String> = settingsRepository.focusModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Continuous")

    val saveJpg: StateFlow<Boolean> = settingsRepository.saveJpgFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val autoPdf: StateFlow<Boolean> = settingsRepository.autoPdfFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val batchScan: StateFlow<Boolean> = settingsRepository.batchScanFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val showGrid: StateFlow<Boolean> = settingsRepository.showGridFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val clickSound: StateFlow<Boolean> = settingsRepository.clickSoundFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val autoOrientation: StateFlow<Boolean> = settingsRepository.autoOrientationFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val shadowRemove: StateFlow<Boolean> = settingsRepository.shadowRemoveFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoRotation: StateFlow<Boolean> = settingsRepository.autoRotationFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val defaultFilter: StateFlow<String> = settingsRepository.defaultFilterFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Original")

    val uiLanguage: StateFlow<String> = settingsRepository.uiLanguageFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "en")

    val vibrateOnCapture: StateFlow<Boolean> = settingsRepository.vibrateOnCaptureFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val saveToGallery: StateFlow<Boolean> = settingsRepository.saveToGalleryFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val liveDetect: StateFlow<Boolean> = settingsRepository.liveDetectFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val batterySaver: StateFlow<Boolean> = settingsRepository.batterySaverFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val startWithCamera: StateFlow<Boolean> = settingsRepository.startWithCameraFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val usePhoneCamera: StateFlow<Boolean> = settingsRepository.usePhoneCameraFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val useNativeScanner: StateFlow<Boolean> = settingsRepository.useNativeScannerFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hdMode: StateFlow<String> = settingsRepository.hdModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Standard")

    val wizardDontShowAgain: StateFlow<Boolean> = settingsRepository.wizardDontShowAgainFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val wizardWarp: StateFlow<String> = settingsRepository.wizardWarpFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Perspective")

    val wizardRotation: StateFlow<String> = settingsRepository.wizardRotationFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Auto")

    val wizardManualCrop: StateFlow<Boolean> = settingsRepository.wizardManualCropFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val imageUpdateTick = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val capturedJpgFiles = androidx.compose.runtime.mutableStateListOf<java.io.File>()
    var openedDocumentId: String? = null
    private var initialDocumentTitle: String? = null
    private var editingJob: kotlinx.coroutines.Job? = null
    val originalJpgBitmaps = mutableMapOf<Int, Bitmap>()
    val jpgCorners = mutableMapOf<Int, List<com.safescan.domain.model.Point>>()

    private val settingsHandler = ScannerSettingsHandler(settingsRepository, viewModelScope)

    val imageCacheHelper = ImageCacheHelper(context, saveDocumentUseCase)
    val exportHelper = ScannerExportHelper(saveDocumentUseCase, exportPdfUseCase, documentScanner, imageCacheHelper)
    val ocrHandler = ScannerOcrHandler(context)
    val slotHandler = ScannerSlotHandler(context, saveDocumentUseCase, settingsRepository, imageCacheHelper)
    val cropHandler = ScannerCropHandler(detectEdgesUseCase)
    val editorHandler = ScannerEditorHandler()

    init {
        viewModelScope.launch {
            autoCapture.collect { enabled ->
                _uiState.update { it.copy(isAutoCaptureEnabled = enabled) }
            }
        }
    }

    suspend fun getFullResBitmap(slotId: String, isOriginal: Boolean = false): Bitmap? {
        return imageCacheHelper.getFullResBitmap(slotId, isOriginal, slots.value, openedDocumentId)
    }

    private fun saveHighResToDisk(bitmap: Bitmap, slotId: String, suffix: String): String? {
        return imageCacheHelper.saveHighResToDisk(bitmap, slotId, suffix)
    }

    private fun generateThumbnail(bitmap: Bitmap, maxDimension: Int = 360): Bitmap {
        return imageCacheHelper.generateThumbnail(bitmap, maxDimension)
    }

    fun getOrGenerateDocumentTitle(docId: String?): String {
        // 1. If we have an initialDocumentTitle stored, return it
        initialDocumentTitle?.let { return it }

        // 2. If the document is already in savedDocuments, use its title
        if (docId != null) {
            val savedDoc = savedDocuments.value.find { it.id == docId }
            if (savedDoc != null) {
                initialDocumentTitle = savedDoc.title
                return savedDoc.title
            }
        }

        // 3. Otherwise, generate a new dynamic filename and cache it in initialDocumentTitle
        val newTitle = ScannerTitleUtils.resolveDynamicFilename(pdfFilename.value, currentMode.value)
        initialDocumentTitle = newTitle
        return newTitle
    }

    val slots: MutableStateFlow<List<Slot>> = MutableStateFlow(emptyList())
    val selectedSlotId: MutableStateFlow<String?> = MutableStateFlow(null)

    val isEditing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isDocumentOpenedFromLibrary: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val editingSlotId: MutableStateFlow<String?> = MutableStateFlow(null)
    val editingBitmapOriginal: MutableStateFlow<Bitmap?> = MutableStateFlow(null)
    val editingBitmapPreview: MutableStateFlow<Bitmap?> = MutableStateFlow(null)
    val editorState: MutableStateFlow<com.safescan.data.EditorState> = MutableStateFlow(com.safescan.data.EditorState())

    // OCR & Text Recognition States
    val recognizedText: MutableStateFlow<String?> = MutableStateFlow(null)
    val isOcrRunning: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isBarcodeRunning: MutableStateFlow<Boolean> = MutableStateFlow(false)

    val isCropping: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isSettingsOpen: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isGridViewVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val croppingSlotId: MutableStateFlow<String?> = MutableStateFlow(null)
    val croppingBitmap: MutableStateFlow<Bitmap?> = MutableStateFlow(null)
    val croppingJpgIndex: MutableStateFlow<Int?> = MutableStateFlow(null)
    val editingJpgIndex: MutableStateFlow<Int?> = MutableStateFlow(null)

    val savedDocuments: MutableStateFlow<List<com.safescan.data.DocumentMetadata>> = MutableStateFlow(emptyList())

    init {
        _uiState.update { it.copy(currentEngine = scannerEngine.engineType) }
        reloadSavedDocuments()
        viewModelScope.launch {
            currentMode.collect { mode ->
                if (!isDocumentOpenedFromLibrary.value) {
                    slots.value = when (mode) {
                        ScannerMode.CARD -> (1..8).map { i ->
                            val pairNum = (i + 1) / 2
                            val side = if (i % 2 == 1) "Front" else "Back"
                            Slot(i.toString(), "$side $pairNum")
                        }
                        ScannerMode.DOCUMENT -> emptyList()
                    }
                    selectedSlotId.value = null
                    capturedJpgFiles.clear()
                }
            }
        }
    }

    fun reloadSavedDocuments() {
        viewModelScope.launch(Dispatchers.IO) {
            val docs = saveDocumentUseCase.getDocuments()
            withContext(Dispatchers.Main) {
                savedDocuments.value = docs
            }
        }
    }

    // IMPROVEMENT: Added async detectEdges runner updating isAutoRunning state Flow

    fun triggerAutoCapture() {
        // Trigger capture via event
        _autoCaptureEvent.tryEmit(Unit)
    }

    private val _autoCaptureEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val autoCaptureEvent = _autoCaptureEvent.asSharedFlow()

    fun detectEdges(bitmap: Bitmap, onResult: (List<Point>?) -> Unit) {
        cropHandler.detectEdges(bitmap, currentMode, _uiState, viewModelScope, onResult)
    }

    fun detectEdgesWithTFLite(bitmap: Bitmap, onResult: (List<Point>?) -> Unit) {
        cropHandler.detectEdgesWithTFLite(bitmap, _uiState, viewModelScope, onResult)
    }

    fun switchMode(mode: ScannerMode) = settingsHandler.switchMode(mode)
    fun toggleAutoCrop(enabled: Boolean) = settingsHandler.toggleAutoCrop(enabled)
    fun cycleFlashMode() = settingsHandler.cycleFlashMode(flashMode.value)
    fun setFlashMode(mode: FlashMode) = settingsHandler.setFlashMode(mode)
    fun toggleFlash(enabled: Boolean) = settingsHandler.toggleFlash(enabled)
    fun toggleDoubleFocus(enabled: Boolean) = settingsHandler.toggleDoubleFocus(enabled)
    fun setFocusMode(mode: String) = settingsHandler.setFocusMode(mode)
    fun toggleSaveJpg(enabled: Boolean) = settingsHandler.toggleSaveJpg(enabled)
    fun toggleAutoPdf(enabled: Boolean) = settingsHandler.toggleAutoPdf(enabled)
    fun toggleBatchScan(enabled: Boolean) = settingsHandler.toggleBatchScan(enabled)
    fun toggleShowGrid(enabled: Boolean) = settingsHandler.toggleShowGrid(enabled)
    fun toggleClickSound(enabled: Boolean) = settingsHandler.toggleClickSound(enabled)
    fun toggleAutoOrientation(enabled: Boolean) = settingsHandler.toggleAutoOrientation(enabled)
    fun toggleShadowRemove(enabled: Boolean) = settingsHandler.toggleShadowRemove(enabled)
    fun toggleAutoRotation(enabled: Boolean) = settingsHandler.toggleAutoRotation(enabled)
    fun setDefaultFilter(filter: String) = settingsHandler.setDefaultFilter(filter)
    fun setUiLanguage(language: String) = settingsHandler.setUiLanguage(language)
    fun setVibrateOnCapture(enabled: Boolean) = settingsHandler.setVibrateOnCapture(enabled)
    fun setSaveToGallery(enabled: Boolean) = settingsHandler.setSaveToGallery(enabled)
    fun toggleLiveDetect(enabled: Boolean) = settingsHandler.toggleLiveDetect(enabled)
    fun toggleBatterySaver(enabled: Boolean) = settingsHandler.toggleBatterySaver(enabled)
    fun toggleStartWithCamera(enabled: Boolean) = settingsHandler.toggleStartWithCamera(enabled)
    fun toggleUsePhoneCamera(enabled: Boolean) = settingsHandler.toggleUsePhoneCamera(enabled)
    fun toggleUseNativeScanner(enabled: Boolean) = settingsHandler.toggleUseNativeScanner(enabled)
    fun setHdMode(mode: String) = settingsHandler.setHdMode(mode)
    fun setDpi(value: Float) = settingsHandler.setDpi(value)
    fun setJpegQuality(value: Float) = settingsHandler.setJpegQuality(value)
    fun setPdfFilename(value: String) = settingsHandler.setPdfFilename(value)
    fun setWizardDontShowAgain(enabled: Boolean) = settingsHandler.setWizardDontShowAgain(enabled)
    fun setWizardWarp(warp: String) = settingsHandler.setWizardWarp(warp)
    fun setWizardRotation(rotation: String) = settingsHandler.setWizardRotation(rotation)
    fun setWizardManualCrop(enabled: Boolean) = settingsHandler.setWizardManualCrop(enabled)
    fun setPageSize(value: String) = settingsHandler.setPageSize(value)
    fun setPdfOrientation(value: String) = settingsHandler.setPdfOrientation(value)

    fun saveImageToGallery(context: android.content.Context, bitmap: Bitmap) {
        ScannerExportUtils.saveImageToGallery(context, bitmap, viewModelScope)
    }

    fun savePdfToPublicDocuments(context: android.content.Context, sourceFile: java.io.File) {
        ScannerExportUtils.savePdfToPublicDocuments(context, sourceFile, viewModelScope)
    }

    fun onSlotClick(slotId: String) {
        selectedSlotId.value = slotId
    }

    fun captureToSlot(
        bitmap: Bitmap,
        slotId: String,
        isCapture: Boolean = false,
        corners: List<Point>? = null,
        originalBitmap: Bitmap? = null
    ) {
        DiagnosticsLogger.info("Processing captured image for slot $slotId...")
        val activeDocId = openedDocumentId ?: ("doc_" + System.currentTimeMillis()).also { openedDocumentId = it }
        val docId = activeDocId

        val currentSlots = slots.value.toMutableList()
        val index = currentSlots.indexOfFirst { it.id == slotId }
        if (index != -1) {
            val existing = currentSlots[index]

            // Instantly update RAM cache & generate lightweight thumbnail
            imageCacheHelper.put("${slotId}_processed", bitmap)
            val origToSave = originalBitmap ?: bitmap
            if (isCapture || existing.originalBitmapPath == null) {
                imageCacheHelper.put("${slotId}_original", origToSave)
            }

            val thumbnail = generateThumbnail(bitmap, 360)

            // Update UI slot immediately (<10ms) so user sees the new photo instantly
            currentSlots[index] = existing.copy(
                bitmap = thumbnail,
                originalBitmap = null, // No high-res original in RAM!
                corners = corners ?: existing.corners
            )
            slots.value = currentSlots
            DiagnosticsLogger.info("Slot $slotId updated with thumbnail immediately.")

            // Save heavy disk files & metadata asynchronously in background IO thread
            val qualityVal = jpegQuality.value.toInt()
            viewModelScope.launch(Dispatchers.IO) {
                val processedPath = saveHighResToDisk(bitmap, slotId, "processed")
                if (processedPath != null) {
                    ScannerDebugLogger.logSaveThumbnail(processedPath)
                }

                var origPath = existing.originalBitmapPath
                if (isCapture || origPath == null) {
                    if (origToSave === bitmap && processedPath != null) {
                        val origFile = java.io.File(context.cacheDir, "temp_scans/${slotId}_original.jpg")
                        try {
                            java.io.File(processedPath).copyTo(origFile, overwrite = true)
                            origPath = origFile.absolutePath
                        } catch (e: Exception) {
                            origPath = saveHighResToDisk(origToSave, slotId, "original")
                        }
                    } else {
                        origPath = saveHighResToDisk(origToSave, slotId, "original")
                    }
                }

                if (processedPath != null || origPath != null) {
                    val updatedSlots = slots.value.toMutableList()
                    val uIdx = updatedSlots.indexOfFirst { it.id == slotId }
                    if (uIdx != -1) {
                        updatedSlots[uIdx] = updatedSlots[uIdx].copy(
                            bitmapPath = processedPath ?: updatedSlots[uIdx].bitmapPath,
                            originalBitmapPath = origPath ?: updatedSlots[uIdx].originalBitmapPath
                        )
                        slots.value = updatedSlots
                    }
                }

                // Sync with capturedJpgFiles if it exists
                if (index < capturedJpgFiles.size) {
                    val file = capturedJpgFiles[index]
                    try {
                        java.io.FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, qualityVal, out)
                            out.flush()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Auto-save the document state offline immediately
                saveDocumentStateOffline(docId)
            }
        }
    }

    private suspend fun buildPagesData(
        docId: String, 
        tempBitmapsToRecycle: MutableList<Bitmap>,
        overrideFilter: String? = null
    ): List<com.safescan.data.PageSaveData> {
        return exportHelper.buildPagesData(
            context = context,
            docId = docId,
            tempBitmapsToRecycle = tempBitmapsToRecycle,
            overrideFilter = overrideFilter,
            capturedJpgFiles = capturedJpgFiles,
            originalJpgBitmaps = originalJpgBitmaps,
            jpgCorners = jpgCorners,
            slots = slots.value,
            isEditing = isEditing.value,
            editingJpgIndex = editingJpgIndex.value,
            editingSlotId = editingSlotId.value,
            editorState = editorState.value,
            recognizedText = recognizedText.value,
            wizardWarp = wizardWarp.value,
            currentMode = currentMode.value,
            openedDocumentId = openedDocumentId
        )
    }

    private fun saveDocumentStateOffline(docId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var pagesData: List<com.safescan.data.PageSaveData>? = null
            val tempBitmapsToRecycle = mutableListOf<Bitmap>()
            try {
                val title = getOrGenerateDocumentTitle(docId)
                pagesData = buildPagesData(docId, tempBitmapsToRecycle)
                
                if (pagesData != null && pagesData.isNotEmpty()) {
                    saveDocumentUseCase.saveDocument(docId, title, currentMode.value.name, pagesData)
                    reloadSavedDocuments()
                }
                
                for (bmp in tempBitmapsToRecycle) {
                    if (!bmp.isRecycled) {
                        val isOriginal = originalJpgBitmaps.values.any { it === bmp } || 
                                         slots.value.any { it.bitmap === bmp }
                        if (!isOriginal) {
                            bmp.recycle()
                        }
                    }
                }
            } catch (e: Exception) {
                DiagnosticsLogger.error("Error auto-saving document: ${e.message}")
            } finally {
                for (bmp in tempBitmapsToRecycle) {
                    if (!bmp.isRecycled) {
                        val isOriginal = originalJpgBitmaps.values.any { it === bmp } || 
                                         slots.value.any { it.bitmap === bmp }
                        if (!isOriginal) {
                            bmp.recycle()
                        }
                    }
                }
                pagesData?.forEach { page ->
                    page.previewFile?.let { if (it.exists()) it.delete() }
                }
            }
        }
    }

    fun clearSlot(slotId: String) {
        val currentSlots = slots.value.toMutableList()
        val index = currentSlots.indexOfFirst { it.id == slotId }
        if (index != -1) {
            val slot = currentSlots[index]
            slot.bitmapPath?.let { path ->
                try {
                    java.io.File(path).delete()
                } catch (e: Exception) {}
            }
            slot.originalBitmapPath?.let { path ->
                try {
                    java.io.File(path).delete()
                } catch (e: Exception) {}
            }
            imageCacheHelper.remove("${slotId}_processed")
            imageCacheHelper.remove("${slotId}_original")

            if (currentMode.value == ScannerMode.DOCUMENT) {
                currentSlots.removeAt(index)
                // Re-label slots sequentially for consistency
                for (i in currentSlots.indices) {
                    currentSlots[i] = currentSlots[i].copy(label = "Page ${i + 1}")
                }
            } else {
                currentSlots[index] = slot.copy(
                    bitmap = null,
                    originalBitmap = null,
                    corners = null,
                    bitmapPath = null,
                    originalBitmapPath = null
                )
            }
            slots.value = currentSlots
            
            // Sync with capturedJpgFiles if it exists
            if (index < capturedJpgFiles.size) {
                try {
                    capturedJpgFiles[index].delete()
                } catch (e: Exception) {}
                capturedJpgFiles.removeAt(index)

                // Re-index originalJpgBitmaps map
                val newBitmaps = mutableMapOf<Int, Bitmap>()
                for ((k, v) in originalJpgBitmaps) {
                    if (k < index) {
                        newBitmaps[k] = v
                    } else if (k > index) {
                        newBitmaps[k - 1] = v
                    }
                }
                originalJpgBitmaps.clear()
                originalJpgBitmaps.putAll(newBitmaps)

                // Re-index jpgCorners map
                val newCorners = mutableMapOf<Int, List<Point>>()
                for ((k, v) in jpgCorners) {
                    if (k < index) {
                        newCorners[k] = v
                    } else if (k > index) {
                        newCorners[k - 1] = v
                    }
                }
                jpgCorners.clear()
                jpgCorners.putAll(newCorners)
            }

            // Sync CropScreen state if currently cropping this slot
            if (croppingSlotId.value == slotId) {
                croppingSlotId.value = null
                croppingBitmap.value = null
                isCropping.value = false
            }

            openedDocumentId?.let { saveDocumentStateOffline(it) }
        }
        if (selectedSlotId.value == slotId) {
            selectedSlotId.value = null
        }
        checkIfEmptyAndDelete()
    }

    fun clearJpgAt(index: Int) {
        if (index < capturedJpgFiles.size) {
            try {
                capturedJpgFiles[index].delete()
            } catch (e: Exception) {}
            capturedJpgFiles.removeAt(index)
            
            // Re-index originalJpgBitmaps map
            val newBitmaps = mutableMapOf<Int, Bitmap>()
            for ((k, v) in originalJpgBitmaps) {
                if (k < index) {
                    newBitmaps[k] = v
                } else if (k > index) {
                    newBitmaps[k - 1] = v
                }
            }
            originalJpgBitmaps.clear()
            originalJpgBitmaps.putAll(newBitmaps)

            // Re-index jpgCorners map
            val newCorners = mutableMapOf<Int, List<Point>>()
            for ((k, v) in jpgCorners) {
                if (k < index) {
                    newCorners[k] = v
                } else if (k > index) {
                    newCorners[k - 1] = v
                }
            }
            jpgCorners.clear()
            jpgCorners.putAll(newCorners)
            
            // Also sync back to slots if it corresponds to a slot
            if (index < slots.value.size) {
                val currentSlots = slots.value.toMutableList()
                val slot = currentSlots[index]
                slot.bitmapPath?.let { path ->
                    try {
                        java.io.File(path).delete()
                    } catch (e: Exception) {}
                }
                slot.originalBitmapPath?.let { path ->
                    try {
                        java.io.File(path).delete()
                    } catch (e: Exception) {}
                }
                imageCacheHelper.remove("${slot.id}_processed")
                imageCacheHelper.remove("${slot.id}_original")

                if (currentMode.value == ScannerMode.DOCUMENT) {
                    currentSlots.removeAt(index)
                    // Re-label slots sequentially for consistency
                    for (i in currentSlots.indices) {
                        currentSlots[i] = currentSlots[i].copy(label = "Page ${i + 1}")
                    }
                } else {
                    currentSlots[index] = slot.copy(
                        bitmap = null,
                        originalBitmap = null,
                        corners = null,
                        bitmapPath = null,
                        originalBitmapPath = null
                    )
                }
                slots.value = currentSlots
            }

            // Sync CropScreen state if currently cropping this index
            val cropIdx = croppingJpgIndex.value
            if (cropIdx == index) {
                croppingJpgIndex.value = null
                croppingBitmap.value = null
                isCropping.value = false
            } else if (cropIdx != null && cropIdx > index) {
                croppingJpgIndex.value = cropIdx - 1
            }

            openedDocumentId?.let { saveDocumentStateOffline(it) }
        }
        checkIfEmptyAndDelete()
    }

    fun endSession() {
        val mode = currentMode.value
        slots.value = when (mode) {
            com.safescan.data.ScannerMode.CARD -> (1..8).map { i ->
                val pairNum = (i + 1) / 2
                val side = if (i % 2 == 1) "Front" else "Back"
                com.safescan.data.Slot(i.toString(), "$side $pairNum")
            }
            com.safescan.data.ScannerMode.DOCUMENT -> emptyList()
        }
        selectedSlotId.value = null
        openedDocumentId = null
        initialDocumentTitle = null
        isDocumentOpenedFromLibrary.value = false
        capturedJpgFiles.clear()
        originalJpgBitmaps.clear()
        jpgCorners.clear()
        imageCacheHelper.evictAll()
        DiagnosticsLogger.info("Session ended. All slots and temporary images cleared for a new document.")
    }

    private fun checkIfEmptyAndDelete() {
        val pagesCount = if (capturedJpgFiles.isNotEmpty()) capturedJpgFiles.size else slots.value.count { it.bitmap != null }
        if (pagesCount == 0) {
            openedDocumentId?.let { docId ->
                deleteDocument(docId)
                isDocumentOpenedFromLibrary.value = false
                openedDocumentId = null
            }
        }
    }

    fun getCornersForCropping(): List<com.safescan.domain.model.Point>? {
        croppingSlotId.value?.let { slotId ->
            val slot = slots.value.find { it.id == slotId }
            return slot?.corners
        }
        croppingJpgIndex.value?.let { index ->
            return jpgCorners[index]
        }
        return null
    }

    fun openCrop(slotId: String) {
        val slot = slots.value.find { it.id == slotId }
        if (slot != null) {
            croppingSlotId.value = slotId
            croppingJpgIndex.value = null
            viewModelScope.launch(Dispatchers.IO) {
                val bmp = getFullResBitmap(slotId, isOriginal = true) ?: getFullResBitmap(slotId, isOriginal = false) ?: slot.bitmap
                withContext(Dispatchers.Main) {
                    croppingBitmap.value = bmp
                    isCropping.value = true
                }
            }
        }
    }

    fun openCropForJpg(index: Int) {
        val file = capturedJpgFiles.getOrNull(index) ?: return
        try {
            val originalBmp = originalJpgBitmaps[index]
            val bitmap = originalBmp ?: android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                if (originalBmp == null) {
                    originalJpgBitmaps[index] = bitmap
                }
                croppingSlotId.value = null
                croppingJpgIndex.value = index
                croppingBitmap.value = bitmap
                isCropping.value = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun closeCrop(save: Boolean) {
        isCropping.value = false
        if (!save) {
            croppingSlotId.value = null
            croppingJpgIndex.value = null
            croppingBitmap.value = null
        }
    }

    fun applyCrop(quad: Quadrilateral, andNext: Boolean = false) {
        val currentSlotId = croppingSlotId.value
        val currentJpgIndex = croppingJpgIndex.value
        viewModelScope.launch(Dispatchers.IO) {
            croppingBitmap.value?.let { bmp ->
                val isFlat = wizardWarp.value == "Flat" || wizardWarp.value == "Flat Crop Only"
                val cropped = documentScanner.cropAndTransform(bmp, quad, currentMode.value.name, flatCrop = isFlat)
                val cornersList = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)

                val docId = openedDocumentId ?: ("doc_" + System.currentTimeMillis()).also { openedDocumentId = it }
                val existingDoc = saveDocumentUseCase.getDocument(docId)

                val pageId = currentSlotId ?: (if (currentJpgIndex != null && currentJpgIndex < slots.value.size) slots.value[currentJpgIndex].id else "p${(currentJpgIndex ?: 0) + 1}")
                val existingPage = existingDoc?.pages?.find { p -> p.id == pageId || (currentJpgIndex != null && (p.id == "p${currentJpgIndex + 1}" || existingDoc.pages.size == 1)) }
                    ?: existingDoc?.pages?.getOrNull(currentJpgIndex ?: 0)

                val filterStr = existingPage?.filter ?: "COLOR"
                val filterEnum = try { com.safescan.data.FilterType.valueOf(filterStr) } catch (e: Exception) { com.safescan.data.FilterType.COLOR }
                val pageEdits = com.safescan.data.EditorState(
                    brightness = existingPage?.brightness ?: 0f,
                    contrast = existingPage?.contrast ?: 1.0f,
                    sharpness = existingPage?.sharpness ?: 0f,
                    saturation = existingPage?.saturation ?: 0f,
                    rotation = existingPage?.rotation ?: 0,
                    filter = filterEnum
                )

                // Re-apply existing filter and adjustments on newly cropped bitmap
                val processedCropped = com.safescan.domain.ImageProcessor.apply(cropped, pageEdits)

                croppingSlotId.value?.let { slotId ->
                    val currentSlots = slots.value.toMutableList()
                    val index = currentSlots.indexOfFirst { it.id == slotId }
                    if (index != -1) {
                        val existing = currentSlots[index]
                        
                        // Save high-res to disk
                        val processedPath = saveHighResToDisk(processedCropped, slotId, "processed")
                        imageCacheHelper.put("${slotId}_processed", processedCropped)
                        
                        var origPath = existing.originalBitmapPath
                        if (origPath == null) {
                            origPath = saveHighResToDisk(bmp, slotId, "original")
                        }
                        imageCacheHelper.put("${slotId}_original", bmp)

                        // Generate lightweight thumbnail
                        val thumbnail = generateThumbnail(processedCropped, 360)

                        currentSlots[index] = existing.copy(
                            bitmap = thumbnail,
                            originalBitmap = null,
                            bitmapPath = processedPath,
                            originalBitmapPath = origPath,
                            corners = cornersList
                        )
                        slots.value = currentSlots
                        
                        // Sync with capturedJpgFiles if it exists
                        if (index < capturedJpgFiles.size) {
                            val file = capturedJpgFiles[index]
                            try {
                                val out = java.io.FileOutputStream(file)
                                processedCropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality.value.toInt(), out)
                                out.flush()
                                out.close()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    
                    // Sync to persistent library JSON
                    saveDocumentUseCase.updatePageCornersAndPreview(
                        docId = docId,
                        pageId = slotId,
                        corners = cornersList,
                        newPreview = processedCropped
                    )
                }
                croppingJpgIndex.value?.let { index ->
                    jpgCorners[index] = cornersList
                    if (!originalJpgBitmaps.containsKey(index)) {
                        originalJpgBitmaps[index] = bmp
                    }
                    val file = capturedJpgFiles.getOrNull(index)
                    if (file != null) {
                        try {
                            val out = java.io.FileOutputStream(file)
                            processedCropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality.value.toInt(), out)
                            out.flush()
                            out.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    saveDocumentUseCase.updatePageCornersAndPreview(
                        docId = docId,
                        pageId = pageId,
                        corners = cornersList,
                        newPreview = processedCropped
                    )
                }

                // Sync document state offline to keep metadata.json consistent
                saveDocumentStateOffline(docId)
            }
            withContext(Dispatchers.Main) {
                imageUpdateTick.value = System.currentTimeMillis()
                if (andNext) {
                    if (currentSlotId != null) {
                        val currentSlots = slots.value
                        val currentIndex = currentSlots.indexOfFirst { it.id == currentSlotId }
                        var nextIndex = currentIndex + 1
                        while (nextIndex >= 0 && nextIndex < currentSlots.size && currentSlots[nextIndex].bitmap == null) {
                            nextIndex++
                        }
                        if (nextIndex >= 0 && nextIndex < currentSlots.size) {
                            val nextSlot = currentSlots[nextIndex]
                            openCrop(nextSlot.id)
                        } else {
                            closeCrop(true)
                        }
                    } else if (currentJpgIndex != null) {
                        val nextIndex = currentJpgIndex + 1
                        if (nextIndex >= 0 && nextIndex < capturedJpgFiles.size) {
                            openCropForJpg(nextIndex)
                        } else {
                            closeCrop(true)
                        }
                    } else {
                        closeCrop(true)
                    }
                } else {
                    closeCrop(true)
                }
            }
        }
    }

    fun openEditor(slotId: String) {
        val slot = slots.value.find { it.id == slotId }
        if (slot != null) {
            val docId = openedDocumentId
            viewModelScope.launch(Dispatchers.IO) {
                val fullRes = getFullResBitmap(slotId, isOriginal = false) ?: slot.bitmap
                val originalRes = getFullResBitmap(slotId, isOriginal = true) ?: fullRes
                if (fullRes != null) {
                    val corners = slot.corners
                    val baseImage = if (originalRes != null && corners != null && corners.size == 4) {
                        val isFlat = wizardWarp.value == "Flat" || wizardWarp.value == "Flat Crop Only"
                        val quad = Quadrilateral(corners[0], corners[1], corners[2], corners[3])
                        try {
                            documentScanner.cropAndTransform(originalRes, quad, currentMode.value.name, flatCrop = isFlat)
                        } catch (e: Exception) { originalRes }
                    } else {
                        originalRes ?: fullRes
                    }

                    val doc = docId?.let { saveDocumentUseCase.getDocument(it) }
                    val page = doc?.pages?.find { it.id == slotId }
                    val storedRotation = page?.rotation ?: 0

                    var rotatedBase = baseImage
                    if (storedRotation != 0 && baseImage != null) {
                        val matrix = android.graphics.Matrix().apply { postRotate(storedRotation.toFloat()) }
                        rotatedBase = Bitmap.createBitmap(baseImage, 0, 0, baseImage.width, baseImage.height, matrix, true)
                    }

                    val restoredState = if (page != null) {
                        val filterEnum = try {
                            com.safescan.data.FilterType.valueOf(page.filter)
                        } catch (e: Exception) {
                            com.safescan.data.FilterType.COLOR
                        }
                        com.safescan.data.EditorState(
                            brightness = page.brightness,
                            contrast = page.contrast,
                            sharpness = page.sharpness,
                            saturation = page.saturation,
                            filter = filterEnum,
                            rotation = storedRotation
                        )
                    } else {
                        com.safescan.data.EditorState(rotation = storedRotation)
                    }

                    val processedPreview = rotatedBase?.let { com.safescan.domain.ImageProcessor.apply(it, restoredState) } ?: fullRes

                    withContext(Dispatchers.Main) {
                        editingSlotId.value = slotId
                        editingJpgIndex.value = null
                        editingBitmapOriginal.value = rotatedBase
                        editingBitmapPreview.value = processedPreview
                        editorState.value = restoredState
                        isEditing.value = true
                    }
                }
            }
        }
    }

    fun openEditorForJpg(index: Int) {
        val file = capturedJpgFiles.getOrNull(index) ?: return
        try {
            val originalBmp = originalJpgBitmaps[index] ?: android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            if (originalBmp != null) {
                if (!originalJpgBitmaps.containsKey(index)) {
                    originalJpgBitmaps[index] = originalBmp
                }
                val corners = jpgCorners[index]
                val baseImage = if (corners != null && corners.size == 4) {
                    val isFlat = wizardWarp.value == "Flat" || wizardWarp.value == "Flat Crop Only"
                    val quad = Quadrilateral(corners[0], corners[1], corners[2], corners[3])
                    try {
                        documentScanner.cropAndTransform(originalBmp, quad, currentMode.value.name, flatCrop = isFlat)
                    } catch (e: Exception) { originalBmp }
                } else {
                    originalBmp
                }

                val docId = openedDocumentId
                viewModelScope.launch(Dispatchers.IO) {
                    val doc = docId?.let { saveDocumentUseCase.getDocument(it) }
                    val pageId = if (index < slots.value.size) slots.value[index].id else "p${index + 1}"
                    val page = doc?.pages?.find { it.id == pageId } ?: doc?.pages?.getOrNull(index)
                    val storedRotation = page?.rotation ?: 0

                    var rotatedBase = baseImage
                    if (storedRotation != 0 && baseImage != null) {
                        val matrix = android.graphics.Matrix().apply { postRotate(storedRotation.toFloat()) }
                        rotatedBase = Bitmap.createBitmap(baseImage, 0, 0, baseImage.width, baseImage.height, matrix, true)
                    }

                    val restoredState = if (page != null) {
                        val filterEnum = try {
                            com.safescan.data.FilterType.valueOf(page.filter)
                        } catch (e: Exception) {
                            com.safescan.data.FilterType.COLOR
                        }
                        com.safescan.data.EditorState(
                            brightness = page.brightness,
                            contrast = page.contrast,
                            sharpness = page.sharpness,
                            saturation = page.saturation,
                            filter = filterEnum,
                            rotation = storedRotation
                        )
                    } else {
                        com.safescan.data.EditorState(rotation = storedRotation)
                    }

                    val processedPreview = rotatedBase?.let { com.safescan.domain.ImageProcessor.apply(it, restoredState) } ?: baseImage

                    withContext(Dispatchers.Main) {
                        editingSlotId.value = null
                        editingJpgIndex.value = index
                        editingBitmapOriginal.value = rotatedBase
                        editingBitmapPreview.value = processedPreview
                        editorState.value = restoredState
                        isEditing.value = true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun commitActiveEditorChangesSuspend(
        processed: Bitmap? = editingBitmapPreview.value,
        slotId: String? = editingSlotId.value,
        index: Int? = editingJpgIndex.value,
        currentState: com.safescan.data.EditorState = editorState.value
    ) {
        val originalFullRes = editingBitmapOriginal.value
        val targetBitmap = if (originalFullRes != null && !originalFullRes.isRecycled) {
            try {
                com.safescan.domain.ImageProcessor.apply(originalFullRes, currentState)
            } catch (e: Exception) {
                processed
            }
        } else {
            processed
        }
        if (targetBitmap != null && !targetBitmap.isRecycled) {
            val processedBmp = targetBitmap
            val docId = openedDocumentId ?: ("doc_" + System.currentTimeMillis()).also { openedDocumentId = it }
            slotId?.let { sId ->
                captureToSlot(processedBmp, sId)
                
                // Sync to persistent library JSON
                val slotCorners = slots.value.find { it.id == sId }?.corners
                saveDocumentUseCase.updatePageEdits(
                    docId = docId,
                    pageId = sId,
                    filter = currentState.filter.name,
                    brightness = currentState.brightness,
                    contrast = currentState.contrast,
                    sharpness = currentState.sharpness,
                    saturation = currentState.saturation,
                    rotation = currentState.rotation,
                    corners = slotCorners,
                    newPreview = processedBmp
                )
                saveDocumentStateOffline(docId)
            }
            index?.let { idx ->
                val file = capturedJpgFiles.getOrNull(idx)
                if (file != null) {
                    try {
                        val out = java.io.FileOutputStream(file)
                        processedBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.value.toInt(), out)
                        out.flush()
                        out.close()
                    } catch (e: Exception) {}
                }
                val pageId = if (idx < slots.value.size) slots.value[idx].id else "p${idx + 1}"
                val corners = jpgCorners[idx]
                saveDocumentUseCase.updatePageEdits(
                    docId = docId,
                    pageId = pageId,
                    filter = currentState.filter.name,
                    brightness = currentState.brightness,
                    contrast = currentState.contrast,
                    sharpness = currentState.sharpness,
                    saturation = currentState.saturation,
                    rotation = currentState.rotation,
                    corners = corners,
                    newPreview = processedBmp
                )
                saveDocumentStateOffline(docId)
            }
            withContext(Dispatchers.Main) {
                imageUpdateTick.value = System.currentTimeMillis()
            }
        }
    }

    fun commitActiveEditorChanges() {
        val processed = editingBitmapPreview.value
        val slotId = editingSlotId.value
        val index = editingJpgIndex.value
        val currentState = editorState.value
        
        viewModelScope.launch(Dispatchers.IO) {
            commitActiveEditorChangesSuspend(processed, slotId, index, currentState)
        }
    }

    fun saveAndNext() {
        // Save current changes
        commitActiveEditorChanges()
        
        // Attempt to open the next image
        val currentJpgIndex = editingJpgIndex.value
        if (currentJpgIndex != null && currentJpgIndex + 1 < capturedJpgFiles.size) {
            // Document flow: load next page
            openEditorForJpg(currentJpgIndex + 1)
        } else {
            val currentSlotId = editingSlotId.value
            if (currentSlotId != null) {
                // Slots flow: find current index, try to open next slot
                val currentIndex = slots.value.indexOfFirst { it.id == currentSlotId }
                if (currentIndex >= 0 && currentIndex + 1 < slots.value.size) {
                    openEditor(slots.value[currentIndex + 1].id)
                } else {
                    closeEditor(save = false) // already saved
                }
            } else {
                closeEditor(save = false)
            }
        }
    }

    private var editingBitmapPreviewSource: Bitmap? = null

    private fun getOrCreatePreviewSource(original: Bitmap): Bitmap {
        if (original.isRecycled) return original
        val currentSource = editingBitmapPreviewSource
        if (currentSource != null && !currentSource.isRecycled) {
            return currentSource
        }
        val maxDim = 1280
        return try {
            val src = if (original.width <= maxDim && original.height <= maxDim) {
                original
            } else {
                val scale = maxDim.toFloat() / Math.max(original.width, original.height)
                val w = (original.width * scale).toInt().coerceAtLeast(1)
                val h = (original.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(original, w, h, true)
            }
            editingBitmapPreviewSource = src
            src
        } catch (e: Exception) {
            original
        }
    }

    private fun clearPreviewSource() {
        editingBitmapPreviewSource?.let {
            if (!it.isRecycled && it != editingBitmapOriginal.value) {
                try { it.recycle() } catch (e: Exception) {}
            }
        }
        editingBitmapPreviewSource = null
    }

    fun closeEditor(save: Boolean) {
        if (save) {
            commitActiveEditorChanges()
        }
        isEditing.value = false
        editingSlotId.value = null
        editingJpgIndex.value = null
        editingBitmapOriginal.value = null
        editingBitmapPreview.value = null
        clearPreviewSource()
        recognizedText.value = null
        isOcrRunning.value = false
    }

    fun updateEditorState(newState: com.safescan.data.EditorState) {
        editorState.value = newState
        applyEdits()
    }

    fun applyFilterToAllPages(filterType: com.safescan.data.FilterType) {
        updateEditorState(editorState.value.copy(filter = filterType))

        viewModelScope.launch(Dispatchers.IO) {
            val docId = openedDocumentId
            val existingDoc = if (docId != null) saveDocumentUseCase.getDocument(docId) else null
            val pageMetaMap = existingDoc?.pages?.associateBy { it.id } ?: emptyMap()
            val currentSlots = slots.value
            val isFlat = wizardWarp.value == "Flat" || wizardWarp.value == "Flat Crop Only"

            if (currentSlots.isNotEmpty()) {
                val updatedSlots = currentSlots.map { slot ->
                    val origBmp = getFullResBitmap(slot.id, isOriginal = true)
                        ?: getFullResBitmap(slot.id, isOriginal = false)
                        ?: slot.bitmap

                    if (origBmp != null) {
                        val corners = slot.corners
                        val baseImage = if (corners != null && corners.size == 4) {
                            val quad = Quadrilateral(corners[0], corners[1], corners[2], corners[3])
                            try {
                                documentScanner.cropAndTransform(origBmp, quad, currentMode.value.name, flatCrop = isFlat)
                            } catch (e: Exception) { origBmp }
                        } else {
                            origBmp
                        }

                        val pageMeta = pageMetaMap[slot.id]
                        val brightness = pageMeta?.brightness ?: 0f
                        val contrast = pageMeta?.contrast ?: 1.0f
                        val sharpness = pageMeta?.sharpness ?: 0f
                        val saturation = pageMeta?.saturation ?: 0f
                        val rotation = pageMeta?.rotation ?: 0

                        val processed = com.safescan.domain.ImageProcessor.apply(
                            baseImage,
                            com.safescan.data.EditorState(
                                filter = filterType,
                                brightness = brightness,
                                contrast = contrast,
                                sharpness = sharpness,
                                saturation = saturation,
                                rotation = rotation
                            )
                        )

                        imageCacheHelper.put("${slot.id}_processed", processed)
                        val processedPath = saveHighResToDisk(processed, slot.id, "processed")
                        val thumb = generateThumbnail(processed, 360)

                        if (docId != null) {
                            saveDocumentUseCase.updatePageEdits(
                                docId = docId,
                                pageId = slot.id,
                                filter = filterType.name,
                                brightness = brightness,
                                contrast = contrast,
                                sharpness = sharpness,
                                saturation = saturation,
                                rotation = rotation,
                                corners = corners,
                                newPreview = processed
                            )
                        }

                        slot.copy(bitmap = thumb, bitmapPath = processedPath ?: slot.bitmapPath)
                    } else {
                        slot
                    }
                }

                withContext(Dispatchers.Main) {
                    slots.value = updatedSlots
                }
            }

            if (capturedJpgFiles.isNotEmpty()) {
                capturedJpgFiles.forEachIndexed { idx, file ->
                    try {
                        val rawBmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        val origBmp = originalJpgBitmaps[idx] ?: rawBmp
                        if (origBmp != null) {
                            val corners = jpgCorners[idx]
                            val baseBmp = if (corners != null && corners.size == 4) {
                                val quad = Quadrilateral(corners[0], corners[1], corners[2], corners[3])
                                try {
                                    documentScanner.cropAndTransform(origBmp, quad, currentMode.value.name, flatCrop = isFlat)
                                } catch (e: Exception) { rawBmp ?: origBmp }
                            } else {
                                rawBmp ?: origBmp
                            }
                            if (baseBmp != null) {
                                val processed = com.safescan.domain.ImageProcessor.apply(
                                    baseBmp,
                                    com.safescan.data.EditorState(filter = filterType)
                                )
                                val out = java.io.FileOutputStream(file)
                                processed.compress(Bitmap.CompressFormat.JPEG, jpegQuality.value.toInt(), out)
                                out.flush()
                                out.close()
                            }
                        }
                    } catch (e: Exception) {
                        DiagnosticsLogger.error("Failed to apply filter to captured JPG $idx: ${e.message}")
                    }
                }
            }
        }
    }

    fun applyAutoEnhance() {
        editorHandler.applyAutoEnhance(
            editingBitmapOriginal = editingBitmapOriginal,
            editingBitmapPreview = editingBitmapPreview,
            editorState = editorState,
            recognizedText = recognizedText,
            scope = viewModelScope
        )
    }

    fun runOcrOnCurrentBitmap() {
        ocrHandler.runOcrOnCurrentBitmap(editingBitmapPreview.value, isOcrRunning, recognizedText, viewModelScope)
    }

    fun runBarcodeOnCurrentBitmap() {
        ocrHandler.runBarcodeOnCurrentBitmap(editingBitmapPreview.value, isBarcodeRunning, recognizedText, viewModelScope)
    }

    fun exportPdf(
        context: android.content.Context,
        clearSession: Boolean = false,
        customTitle: String? = null,
        customPageSize: String? = null,
        customOrientation: String? = null,
        customQuality: Float? = null,
        customDpi: Float? = null,
        customWarp: String? = null,
        customFilter: String? = null,
        customCardLayout: String? = null,
        onResult: (java.io.File?) -> Unit
    ) {
        exportHelper.exportPdf(
            scope = viewModelScope,
            context = context,
            clearSession = clearSession,
            customTitle = customTitle,
            customPageSize = customPageSize,
            customOrientation = customOrientation,
            customQuality = customQuality,
            customDpi = customDpi,
            customWarp = customWarp,
            customFilter = customFilter,
            customCardLayout = customCardLayout,
            isEditing = isEditing.value,
            openedDocumentId = openedDocumentId,
            initialDocumentTitle = initialDocumentTitle,
            pageSize = pageSize.value,
            pdfOrientation = pdfOrientation.value,
            jpegQuality = jpegQuality.value,
            dpi = dpi.value,
            wizardWarp = wizardWarp.value,
            currentMode = currentMode.value,
            slots = slots.value,
            capturedJpgFiles = capturedJpgFiles,
            originalJpgBitmaps = originalJpgBitmaps,
            jpgCorners = jpgCorners,
            cachedPdfFile = cachedPdfFile,
            lastExportPdfHash = lastExportPdfHash,
            editingJpgIndex = editingJpgIndex.value,
            editingSlotId = editingSlotId.value,
            editorState = editorState.value,
            recognizedText = recognizedText.value,
            commitActiveEditorChanges = { commitActiveEditorChanges() },
            commitActiveEditorChangesSuspend = ::commitActiveEditorChangesSuspend,
            getOrGenerateDocumentTitle = { docId -> getOrGenerateDocumentTitle(docId) },
            reloadSavedDocuments = { reloadSavedDocuments() },
            onDocumentIdAssigned = { assignedId -> openedDocumentId = assignedId },
            onSessionCleared = {
                capturedJpgFiles.clear()
                originalJpgBitmaps.clear()
                jpgCorners.clear()
                cachedPdfFile = null
                lastExportPdfHash = 0
                openedDocumentId = null
                initialDocumentTitle = null
            },
            onCacheExportedPdf = { pdfFile, hash ->
                cachedPdfFile = pdfFile
                lastExportPdfHash = hash
            },
            onResult = onResult
        )
    }

    fun saveDocumentOnly(onResult: (Boolean) -> Unit) {
        exportHelper.saveDocumentOnly(
            scope = viewModelScope,
            context = context,
            openedDocumentId = openedDocumentId,
            capturedJpgFiles = capturedJpgFiles,
            originalJpgBitmaps = originalJpgBitmaps,
            jpgCorners = jpgCorners,
            slots = slots.value,
            currentMode = currentMode.value,
            isEditing = isEditing.value,
            editingJpgIndex = editingJpgIndex.value,
            editingSlotId = editingSlotId.value,
            editorState = editorState.value,
            recognizedText = recognizedText.value,
            wizardWarp = wizardWarp.value,
            getOrGenerateDocumentTitle = { docId -> getOrGenerateDocumentTitle(docId) },
            reloadSavedDocuments = { reloadSavedDocuments() },
            onDocumentIdAssigned = { assignedId -> openedDocumentId = assignedId },
            endSession = { endSession() },
            onResult = onResult
        )
    }

    fun loadDocumentIntoSlots(doc: com.safescan.data.DocumentMetadata) {
        slotHandler.loadDocumentIntoSlots(
            doc = doc,
            scope = viewModelScope,
            uiState = _uiState,
            isDocumentOpenedFromLibrary = isDocumentOpenedFromLibrary,
            slots = slots,
            capturedJpgFiles = capturedJpgFiles,
            originalJpgBitmaps = originalJpgBitmaps,
            jpgCorners = jpgCorners,
            saveHighResToDisk = { bmp, pageId, type -> saveHighResToDisk(bmp, pageId, type) },
            generateThumbnail = { bmp, maxDim -> generateThumbnail(bmp, maxDim) },
            onDocumentOpened = { id, title ->
                openedDocumentId = id
                initialDocumentTitle = title
            }
        )
    }

    fun deleteDocument(docId: String) {
        slotHandler.deleteDocument(docId, viewModelScope) { reloadSavedDocuments() }
    }

    fun renameDocument(docId: String, newTitle: String) {
        slotHandler.renameDocument(
            docId = docId,
            newTitle = newTitle,
            scope = viewModelScope,
            openedDocumentId = openedDocumentId,
            onTitleUpdated = { initialDocumentTitle = it },
            reloadSavedDocuments = { reloadSavedDocuments() }
        )
    }

    private fun applyEdits() {
        editingJob?.cancel()
        editingJob = viewModelScope.launch(Dispatchers.IO) {
            val original = editingBitmapOriginal.value
            if (original == null || original.isRecycled) return@launch
            val state = editorState.value
            kotlinx.coroutines.delay(35) // ~35ms debounce for smooth slider drag
            if (original.isRecycled) return@launch
            val previewSource = getOrCreatePreviewSource(original)
            if (previewSource.isRecycled) return@launch
            try {
                val processed = com.safescan.domain.ImageProcessor.apply(previewSource, state)
                if (!processed.isRecycled) {
                    editingBitmapPreview.value = processed
                }
            } catch (e: Exception) {
                DiagnosticsLogger.error("Failed to apply edits: ${e.message}")
            }
        }
    }

    private var lastCaptureTimestampMs = 0L

    fun onCapture(
        bitmap: Bitmap, 
        isNativeScanned: Boolean = false, 
        forceSkipEditor: Boolean = false,
        isGalleryImport: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTimestampMs < 500L && !forceSkipEditor && !isGalleryImport && !isNativeScanned) {
            DiagnosticsLogger.info("[Capture] Ignored duplicate capture request within ${now - lastCaptureTimestampMs}ms")
            return
        }
        lastCaptureTimestampMs = now

        _uiState.update { it.copy(isLoading = true, error = null) }
        
        viewModelScope.launch(Dispatchers.IO) {
            ScannerDebugLogger.logEnter("ScannerViewModel.onCapture")

            // Save the raw captured JPG asynchronously in background to avoid blocking capture pipeline (skipped for gallery imports to avoid duplicate files)
            if (saveJpg.value && !isGalleryImport) {
                val quality = jpegQuality.value.toInt()
                viewModelScope.launch(Dispatchers.IO) {
                    val savedFile = saveDocumentUseCase.saveJpgToScans(bitmap, quality)
                    if (savedFile != null) {
                        DiagnosticsLogger.info("[Save] Raw captured JPG saved to Scans folder: ${savedFile.absolutePath}")
                    }
                }
            }
            
            // Dynamically scale the image based on our negotiated CameraHardwareConfig constraints (supporting Fast, Standard, High, and high-megapixel modes)
            val currentModeVal = currentMode.value
            val hdModeStr = hdMode.value
            val captureSettings = com.safescan.scanner.CameraHardwareConfig.getCaptureSettings(null, currentModeVal, hdModeStr)
            val maxResolution = kotlin.math.max(captureSettings.targetSize.width.toFloat(), captureSettings.targetSize.height.toFloat())
            
            val ratio = kotlin.math.min(maxResolution / bitmap.width, maxResolution / bitmap.height)
            val resizedBitmap = if (ratio < 1) {
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap, 
                    (bitmap.width * ratio).toInt(), 
                    (bitmap.height * ratio).toInt(), 
                    true
                )
            } else bitmap

            val processedBitmap = if (shadowRemove.value) {
                try {
                    com.safescan.domain.ImageProcessor.autoEnhance(resizedBitmap)
                } catch (e: Exception) {
                    resizedBitmap
                }
            } else {
                resizedBitmap
            }

            val isAutoCropOff = !autoCrop.value
            var slotId = selectedSlotId.value ?: slots.value.firstOrNull { it.bitmap == null }?.id
            if (slotId == null) {
                var candidateIndex = slots.value.size + 1
                var candidateId = "p$candidateIndex"
                while (slots.value.any { it.id == candidateId }) {
                    candidateIndex++
                    candidateId = "p$candidateIndex"
                }
                slots.value = slots.value + Slot(candidateId, "Page $candidateIndex")
                slotId = candidateId
            }

            ScannerDebugLogger.logCapture(slotId ?: "unknown")
            val ratioStr = "${processedBitmap.width}:${processedBitmap.height}"
            ScannerDebugLogger.logOrientation(ratioStr, pageSize.value)

            if (isNativeScanned || isAutoCropOff) {
                if (slotId != null) {
                    captureToSlot(processedBitmap, slotId, isCapture = true)
                    selectedSlotId.value = null
                }
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        scannedBitmap = null,
                        lastCapturedThumbnail = processedBitmap,
                        capturedCount = it.capturedCount + 1,
                        error = null
                    )
                }

                if (!forceSkipEditor && !batchScan.value && slotId != null) {
                    withContext(Dispatchers.Main) {
                        openEditor(slotId)
                    }
                }
            } else {
                val isFlat = wizardWarp.value == "Flat" || wizardWarp.value == "Flat Crop Only"
                when (val result = scannerEngine.scanDocument(processedBitmap, flatCrop = isFlat)) {
                    is com.safescan.core.AppResult.Success -> {
                        if (slotId != null) {
                            captureToSlot(result.data.bitmap, slotId, isCapture = true, corners = result.data.corners, originalBitmap = processedBitmap)
                            selectedSlotId.value = null
                        }
                        
                        _uiState.update { 
                            it.copy(
                                isLoading = false,
                                scannedBitmap = null,
                                lastCapturedThumbnail = result.data.bitmap,
                                capturedCount = it.capturedCount + 1,
                                error = null
                            )
                        }

                        if (!forceSkipEditor && !batchScan.value && slotId != null) {
                            withContext(Dispatchers.Main) {
                                openEditor(slotId)
                            }
                        }
                    }
                    is com.safescan.core.AppResult.Error -> {
                        if (result.message == "CORNERS_NOT_FOUND") {
                            // Fallback to manual crop! Place uncropped image in the slot
                            if (slotId != null) {
                                captureToSlot(processedBitmap, slotId, isCapture = true)
                                selectedSlotId.value = null
                            }

                            _uiState.update { 
                                it.copy(
                                    isLoading = false,
                                    scannedBitmap = null,
                                    lastCapturedThumbnail = processedBitmap,
                                    capturedCount = it.capturedCount + 1,
                                    error = null // Clear error since we gracefully fall back to manual crop
                                )
                            }

                            if (slotId != null) {
                                withContext(Dispatchers.Main) {
                                    if (!forceSkipEditor && !batchScan.value) {
                                        android.widget.Toast.makeText(context, "No document found. Opening manual crop.", android.widget.Toast.LENGTH_SHORT).show()
                                        openCrop(slotId)
                                    } else {
                                        android.widget.Toast.makeText(context, "No document found on some pages.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = result.message
                                )
                            }
                        }
                    }
                }
            }
            ScannerDebugLogger.logExit("ScannerViewModel.onCapture")
        }
    }

    fun toggleEngine(type: ScannerEngineType) {
        scannerEngine.engineType = type
        _uiState.update { it.copy(currentEngine = type) }
    }

    fun rotateEditingBitmap(degrees: Float) {
        val original = editingBitmapOriginal.value ?: return
        if (original.isRecycled) return
        try {
            val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            editingBitmapOriginal.value = rotated
            clearPreviewSource()
            val currentRot = editorState.value.rotation
            val newRot = (currentRot + degrees.toInt()) % 360
            editorState.value = editorState.value.copy(rotation = if (newRot < 0) newRot + 360 else newRot)
            applyEdits()
        } catch (e: Exception) {
            DiagnosticsLogger.error("Failed to rotate editing bitmap: ${e.message}")
        }
    }

    fun moveCapturedJpgFile(fromIndex: Int, toIndex: Int) {
        slotHandler.moveCapturedJpgFile(
            fromIndex = fromIndex,
            toIndex = toIndex,
            capturedJpgFiles = capturedJpgFiles,
            originalJpgBitmaps = originalJpgBitmaps,
            jpgCorners = jpgCorners,
            slots = slots,
            openedDocumentId = openedDocumentId,
            saveDocumentStateOffline = { saveDocumentStateOffline(it) }
        )
    }

    fun moveSlot(fromIndex: Int, toIndex: Int) {
        slotHandler.moveSlot(
            fromIndex = fromIndex,
            toIndex = toIndex,
            slots = slots,
            openedDocumentId = openedDocumentId,
            saveDocumentStateOffline = { saveDocumentStateOffline(it) }
        )
    }

    override fun onCleared() {
        super.onCleared()
        editingJob?.cancel()

        // Clear and recycle all bitmaps in the imageCacheHelper to prevent native OOM
        imageCacheHelper.clearAndRecycle()

        // Recycle originalJpgBitmaps
        try {
            for (bitmap in originalJpgBitmaps.values) {
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            originalJpgBitmaps.clear()
        } catch (e: Exception) {
            Log.e("ScannerViewModel", "Failed to clear originalJpgBitmaps in onCleared", e)
        }

        // Recycle croppingBitmap
        try {
            croppingBitmap.value?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            croppingBitmap.value = null
        } catch (e: Exception) {
            Log.e("ScannerViewModel", "Failed to clear croppingBitmap in onCleared", e)
        }

        // Recycle editingBitmapOriginal and editingBitmapPreview
        try {
            editingBitmapOriginal.value?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            editingBitmapOriginal.value = null
        } catch (e: Exception) {
            Log.e("ScannerViewModel", "Failed to clear editingBitmapOriginal in onCleared", e)
        }

        try {
            editingBitmapPreview.value?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            editingBitmapPreview.value = null
        } catch (e: Exception) {
            Log.e("ScannerViewModel", "Failed to clear editingBitmapPreview in onCleared", e)
        }

        // Recycle scannedBitmap
        try {
            _uiState.value.scannedBitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            _uiState.update { it.copy(scannedBitmap = null) }
        } catch (e: Exception) {
            Log.e("ScannerViewModel", "Failed to clear scannedBitmap in onCleared", e)
        }
    }
}
