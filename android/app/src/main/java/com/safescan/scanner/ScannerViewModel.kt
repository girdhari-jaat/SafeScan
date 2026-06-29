package com.safescan.scanner

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.pow
import dagger.hilt.android.lifecycle.HiltViewModel
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import com.safescan.data.SettingsRepository
import com.safescan.data.ScannerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    // IMPROVEMENT: Injecting all engines and repositories via Hilt DI
    private val scannerEngine: DocumentScannerEngine,
    private val settingsRepository: SettingsRepository,
    private val edgeDetectionEngine: com.safescan.scanner.EdgeDetectionEngine,
    private val pdfExporter: com.safescan.domain.PdfExporter,
    private val documentRepository: com.safescan.data.DocumentRepository
) : ViewModel() {

    // IMPROVEMENT: Using com.safescan.data.ScannerUiState with isAutoRunning
    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    val currentMode: StateFlow<ScannerMode> = settingsRepository.scannerModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScannerMode.CARD)
        
    val autoCrop: StateFlow<Boolean> = settingsRepository.autoCropFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        
    val flashOn: StateFlow<Boolean> = settingsRepository.flashOnFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        
    val dpi: StateFlow<Float> = settingsRepository.dpiFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 300f)
        
    val jpegQuality: StateFlow<Float> = settingsRepository.jpegQualityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 80f)
        
    val pdfFilename: StateFlow<String> = settingsRepository.pdfFilenameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Scan_Document")
        
    val pageSize: StateFlow<String> = settingsRepository.pageSizeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "A4")

    val doubleFocusEnabled: StateFlow<Boolean> = settingsRepository.doubleFocusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val saveJpg: StateFlow<Boolean> = settingsRepository.saveJpgFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoPdf: StateFlow<Boolean> = settingsRepository.autoPdfFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val batchScan: StateFlow<Boolean> = settingsRepository.batchScanFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val showGrid: StateFlow<Boolean> = settingsRepository.showGridFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val clickSound: StateFlow<Boolean> = settingsRepository.clickSoundFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoOrientation: StateFlow<Boolean> = settingsRepository.autoOrientationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val shadowRemove: StateFlow<Boolean> = settingsRepository.shadowRemoveFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoRotation: StateFlow<Boolean> = settingsRepository.autoRotationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val defaultFilter: StateFlow<String> = settingsRepository.defaultFilterFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "original")

    val uiLanguage: StateFlow<String> = settingsRepository.uiLanguageFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "en")

    val liveDetect: StateFlow<Boolean> = settingsRepository.liveDetectFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val batterySaver: StateFlow<Boolean> = settingsRepository.batterySaverFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val usePhoneCamera: StateFlow<Boolean> = settingsRepository.usePhoneCameraFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hdMode: StateFlow<String> = settingsRepository.hdModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Standard")

    val capturedJpgFiles = androidx.compose.runtime.mutableStateListOf<java.io.File>()

    val slots: MutableStateFlow<List<Slot>> = MutableStateFlow(emptyList())
    val selectedSlotId: MutableStateFlow<String?> = MutableStateFlow(null)

    val isEditing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val editingSlotId: MutableStateFlow<String?> = MutableStateFlow(null)
    val editingBitmapOriginal: MutableStateFlow<Bitmap?> = MutableStateFlow(null)
    val editingBitmapPreview: MutableStateFlow<Bitmap?> = MutableStateFlow(null)
    val editorState: MutableStateFlow<com.safescan.data.EditorState> = MutableStateFlow(com.safescan.data.EditorState())

    // OCR & Text Recognition States
    private val ocrEngine = com.safescan.ocr.OcrEngine()
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
                slots.value = when (mode) {
                    ScannerMode.CARD -> listOf(
                        Slot("front", "Front"),
                        Slot("back", "Back")
                    )
                    ScannerMode.DOCUMENT -> listOf(
                        Slot("p1", "Page 1"),
                        Slot("p2", "Page 2"),
                        Slot("p3", "Page 3"),
                        Slot("p4", "Page 4")
                    )
                    ScannerMode.GRID -> (1..8).map {
                        Slot(it.toString(), "Slot $it")
                    }
                }
                selectedSlotId.value = null
                capturedJpgFiles.clear()
            }
        }
    }

    fun reloadSavedDocuments() {
        viewModelScope.launch(Dispatchers.IO) {
            val docs = documentRepository.getDocuments()
            withContext(Dispatchers.Main) {
                savedDocuments.value = docs
            }
        }
    }

    // IMPROVEMENT: Added async detectEdges runner updating isAutoRunning state Flow
    fun detectEdges(bitmap: Bitmap, onResult: (List<com.safescan.android.scanner.Point>?) -> Unit) {
        _uiState.update { it.copy(isAutoRunning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val points = edgeDetectionEngine.detectEdges(bitmap)
            _uiState.update { it.copy(isAutoRunning = false) }
            withContext(Dispatchers.Main) {
                onResult(points)
            }
        }
    }

    fun switchMode(mode: ScannerMode) {
        viewModelScope.launch {
            settingsRepository.setScannerMode(mode)
        }
    }

    fun toggleAutoCrop(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoCrop(enabled)
        }
    }

    fun toggleFlash(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setFlashOn(enabled)
        }
    }

    fun toggleDoubleFocus(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDoubleFocus(enabled)
        }
    }

    fun toggleSaveJpg(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSaveJpg(enabled)
        }
    }

    fun toggleAutoPdf(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoPdf(enabled)
        }
    }

    fun toggleBatchScan(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBatchScan(enabled)
        }
    }

    fun toggleShowGrid(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowGrid(enabled)
        }
    }

    fun toggleClickSound(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setClickSound(enabled)
        }
    }

    fun toggleAutoOrientation(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoOrientation(enabled)
        }
    }

    fun toggleShadowRemove(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShadowRemove(enabled)
        }
    }

    fun toggleAutoRotation(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoRotation(enabled)
        }
    }

    fun setDefaultFilter(filter: String) {
        viewModelScope.launch {
            settingsRepository.setDefaultFilter(filter)
        }
    }

    fun setUiLanguage(language: String) {
        viewModelScope.launch {
            settingsRepository.setUiLanguage(language)
        }
    }

    fun toggleLiveDetect(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLiveDetect(enabled)
        }
    }

    fun toggleBatterySaver(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBatterySaver(enabled)
        }
    }

    fun toggleUsePhoneCamera(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUsePhoneCamera(enabled)
        }
    }

    fun setHdMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.setHdMode(mode)
        }
    }
    
    fun setDpi(value: Float) {
        viewModelScope.launch { settingsRepository.setDpi(value) }
    }
    
    fun setJpegQuality(value: Float) {
        viewModelScope.launch { settingsRepository.setJpegQuality(value) }
    }
    
    fun setPdfFilename(value: String) {
        viewModelScope.launch { settingsRepository.setPdfFilename(value) }
    }
    
    fun setPageSize(value: String) {
        viewModelScope.launch { settingsRepository.setPageSize(value) }
    }

    fun onSlotClick(slotId: String) {
        selectedSlotId.value = slotId
    }

    fun captureToSlot(bitmap: Bitmap, slotId: String) {
        val currentSlots = slots.value.toMutableList()
        val index = currentSlots.indexOfFirst { it.id == slotId }
        if (index != -1) {
            currentSlots[index] = currentSlots[index].copy(
                bitmap = bitmap
            )
            slots.value = currentSlots
            
            // Sync with capturedJpgFiles if it exists
            if (index < capturedJpgFiles.size) {
                val file = capturedJpgFiles[index]
                try {
                    val out = java.io.FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.value.toInt(), out)
                    out.flush()
                    out.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun clearSlot(slotId: String) {
        val currentSlots = slots.value.toMutableList()
        val index = currentSlots.indexOfFirst { it.id == slotId }
        if (index != -1) {
            currentSlots[index] = currentSlots[index].copy(
                bitmap = null
            )
            slots.value = currentSlots
            
            // Sync with capturedJpgFiles if it exists
            if (index < capturedJpgFiles.size) {
                try {
                    capturedJpgFiles[index].delete()
                } catch (e: Exception) {}
                capturedJpgFiles.removeAt(index)
            }
        }
        if (selectedSlotId.value == slotId) {
            selectedSlotId.value = null
        }
    }

    fun clearJpgAt(index: Int) {
        if (index < capturedJpgFiles.size) {
            try {
                capturedJpgFiles[index].delete()
            } catch (e: Exception) {}
            capturedJpgFiles.removeAt(index)
            
            // Also sync back to slots if it corresponds to a slot
            if (index < slots.value.size) {
                val currentSlots = slots.value.toMutableList()
                currentSlots[index] = currentSlots[index].copy(bitmap = null)
                slots.value = currentSlots
            }
        }
    }

    fun openCrop(slotId: String) {
        val slot = slots.value.find { it.id == slotId }
        if (slot?.bitmap != null) {
            croppingSlotId.value = slotId
            croppingJpgIndex.value = null
            croppingBitmap.value = slot.bitmap
            isCropping.value = true
        }
    }

    fun openCropForJpg(index: Int) {
        val file = capturedJpgFiles.getOrNull(index) ?: return
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
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

    fun applyCrop(quad: com.safescan.android.scanner.Quadrilateral) {
        viewModelScope.launch(Dispatchers.IO) {
            croppingBitmap.value?.let { bmp ->
                val tl = quad.topLeft
                val tr = quad.topRight
                val br = quad.bottomRight
                val bottomLeft = quad.bottomLeft

                val widthA = kotlin.math.sqrt((br.x - bottomLeft.x).pow(2.0) + (br.y - bottomLeft.y).pow(2.0))
                val widthB = kotlin.math.sqrt((tr.x - tl.x).pow(2.0) + (tr.y - tl.y).pow(2.0))
                val maxWidth = kotlin.math.max(widthA, widthB).toInt().coerceAtLeast(1)

                val heightA = kotlin.math.sqrt((tr.x - br.x).pow(2.0) + (tr.y - br.y).pow(2.0))
                val heightB = kotlin.math.sqrt((tl.x - bottomLeft.x).pow(2.0) + (tl.y - bottomLeft.y).pow(2.0))
                val maxHeight = kotlin.math.max(heightA, heightB).toInt().coerceAtLeast(1)

                val matrix = android.graphics.Matrix()
                val srcPoints = floatArrayOf(
                    tl.x.toFloat(), tl.y.toFloat(),
                    tr.x.toFloat(), tr.y.toFloat(),
                    br.x.toFloat(), br.y.toFloat(),
                    bottomLeft.x.toFloat(), bottomLeft.y.toFloat()
                )
                val dstPoints = floatArrayOf(
                    0f, 0f,
                    maxWidth.toFloat() - 1, 0f,
                    maxWidth.toFloat() - 1, maxHeight.toFloat() - 1,
                    0f, maxHeight.toFloat() - 1
                )
                matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

                val cropped = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(cropped)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(bmp, matrix, paint)

                croppingSlotId.value?.let { slotId ->
                    captureToSlot(cropped, slotId)
                }
                croppingJpgIndex.value?.let { index ->
                    val file = capturedJpgFiles.getOrNull(index)
                    if (file != null) {
                        try {
                            val out = java.io.FileOutputStream(file)
                            cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality.value.toInt(), out)
                            out.flush()
                            out.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                closeCrop(true)
            }
        }
    }

    fun openEditor(slotId: String) {
        val slot = slots.value.find { it.id == slotId }
        if (slot?.bitmap != null) {
            editingSlotId.value = slotId
            editingJpgIndex.value = null
            editingBitmapOriginal.value = slot.bitmap
            editingBitmapPreview.value = slot.bitmap
            editorState.value = com.safescan.data.EditorState()
            isEditing.value = true
        }
    }

    fun openEditorForJpg(index: Int) {
        val file = capturedJpgFiles.getOrNull(index) ?: return
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                editingSlotId.value = null
                editingJpgIndex.value = index
                editingBitmapOriginal.value = bitmap
                editingBitmapPreview.value = bitmap
                editorState.value = com.safescan.data.EditorState()
                isEditing.value = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun closeEditor(save: Boolean) {
        if (save) {
            editingBitmapPreview.value?.let { processed ->
                editingSlotId.value?.let { slotId ->
                    captureToSlot(processed, slotId)
                }
                editingJpgIndex.value?.let { index ->
                    val file = capturedJpgFiles.getOrNull(index)
                    if (file != null) {
                        try {
                            val out = java.io.FileOutputStream(file)
                            processed.compress(Bitmap.CompressFormat.JPEG, jpegQuality.value.toInt(), out)
                            out.flush()
                            out.close()
                        } catch (e: Exception) {}
                    }
                }
            }
        }
        isEditing.value = false
        editingSlotId.value = null
        editingJpgIndex.value = null
        editingBitmapOriginal.value = null
        editingBitmapPreview.value = null
        recognizedText.value = null
        isOcrRunning.value = false
    }

    fun updateEditorState(newState: com.safescan.data.EditorState) {
        editorState.value = newState
        applyEdits()
    }

    fun applyAutoEnhance() {
        viewModelScope.launch(Dispatchers.IO) {
            editingBitmapOriginal.value?.let { bmp ->
                val enhanced = com.safescan.domain.ImageProcessor.autoEnhance(bmp)
                editingBitmapPreview.value = enhanced
                editorState.value = com.safescan.data.EditorState()
                recognizedText.value = null // reset OCR if image changes
            }
        }
    }

    fun runOcrOnCurrentBitmap() {
        val bmp = editingBitmapPreview.value ?: return
        isOcrRunning.value = true
        recognizedText.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = ocrEngine.recognizeText(bmp)
            withContext(Dispatchers.Main) {
                isOcrRunning.value = false
                when (result) {
                    is com.safescan.core.AppResult.Success -> {
                        recognizedText.value = result.data.joinToString("\n")
                    }
                    is com.safescan.core.AppResult.Error -> {
                        recognizedText.value = "Error: ${result.message}"
                    }
                }
            }
        }
    }

    fun runBarcodeOnCurrentBitmap() {
        val bmp = editingBitmapPreview.value ?: return
        isBarcodeRunning.value = true
        recognizedText.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = ocrEngine.scanQR(bmp)
            withContext(Dispatchers.Main) {
                isBarcodeRunning.value = false
                when (result) {
                    is com.safescan.core.AppResult.Success -> {
                        recognizedText.value = result.data ?: "No QR/Barcode found."
                    }
                    is com.safescan.core.AppResult.Error -> {
                        recognizedText.value = "Error: ${result.message}"
                    }
                }
            }
        }
    }

    fun exportPdf(context: android.content.Context, onResult: (java.io.File?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Also save the captured pages and metadata persistently as a document
                val docId = "doc_" + System.currentTimeMillis()
                val title = pdfFilename.value
                val pagesData = if (capturedJpgFiles.isNotEmpty()) {
                    capturedJpgFiles.mapIndexed { idx, file ->
                        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        Triple("p$idx", bmp, bmp)
                    }
                } else {
                    slots.value.filter { it.bitmap != null }.map { slot ->
                        Triple(slot.id, slot.bitmap!!, slot.bitmap!!)
                    }
                }
                
                if (pagesData.isNotEmpty()) {
                    documentRepository.saveDocument(docId, title, currentMode.value.name, pagesData)
                    reloadSavedDocuments()
                }

                // IMPROVEMENT: Using injected pdfExporter to keep a clean Singleton architecture
                if (autoPdf.value) {
                    val slotsToExport = if (capturedJpgFiles.isNotEmpty()) {
                        capturedJpgFiles.mapIndexed { idx, file ->
                            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            Slot("p$idx", "Page ${idx + 1}", bmp)
                        }
                    } else {
                        slots.value
                    }
                    val result = pdfExporter.exportCardsToPdf(slotsToExport, pdfFilename.value, currentMode.value, pageSize.value)
                    withContext(Dispatchers.Main) {
                        capturedJpgFiles.clear()
                        onResult(result.getOrNull())
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        capturedJpgFiles.clear()
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(null)
                }
            }
        }
    }

    fun loadDocumentIntoSlots(doc: com.safescan.data.DocumentMetadata) {
        viewModelScope.launch(Dispatchers.IO) {
            val loadedSlots = doc.pages.map { page ->
                val bmp = documentRepository.loadOriginalBitmap(doc.id, page.id)
                Slot(page.id, "Page ${page.id}", bmp)
            }
            withContext(Dispatchers.Main) {
                val mode = try {
                    ScannerMode.valueOf(doc.mode)
                } catch (e: Exception) {
                    ScannerMode.DOCUMENT
                }
                settingsRepository.setScannerMode(mode)
                // Let collect trigger but instantly override slots with actual persistent files
                slots.value = loadedSlots
            }
        }
    }

    fun deleteDocument(docId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            documentRepository.deleteDocument(docId)
            reloadSavedDocuments()
        }
    }

    private fun applyEdits() {
        viewModelScope.launch(Dispatchers.IO) {
            val original = editingBitmapOriginal.value ?: return@launch
            val state = editorState.value
            val processed = com.safescan.domain.ImageProcessor.apply(original, state)
            editingBitmapPreview.value = processed
        }
    }

    fun onCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        
        // Save the raw captured JPG immediately to Scans folder if saveJpg is ON
        if (saveJpg.value) {
            val savedFile = documentRepository.saveJpgToScans(bitmap, jpegQuality.value.toInt())
            if (savedFile != null) {
                capturedJpgFiles.add(savedFile)
            }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            // Compress the image to avoid 9MB size
            val maxResolution = 1920f
            val ratio = kotlin.math.min(maxResolution / bitmap.width, maxResolution / bitmap.height)
            val resizedBitmap = if (ratio < 1) {
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap, 
                    (bitmap.width * ratio).toInt(), 
                    (bitmap.height * ratio).toInt(), 
                    true
                )
            } else bitmap

            when (val result = scannerEngine.scanDocument(resizedBitmap)) {
                is com.safescan.core.AppResult.Success -> {
                    val slotId = selectedSlotId.value ?: slots.value.firstOrNull { it.bitmap == null }?.id
                    if (slotId != null) {
                        captureToSlot(result.data, slotId)
                        selectedSlotId.value = null
                    }
                    
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            scannedBitmap = null,
                            error = null
                        )
                    }

                    if (!batchScan.value && slotId != null) {
                        withContext(Dispatchers.Main) {
                            openEditor(slotId)
                        }
                    }
                }
                is com.safescan.core.AppResult.Error -> {
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

    fun toggleEngine(type: ScannerEngineType) {
        scannerEngine.engineType = type
        _uiState.update { it.copy(currentEngine = type) }
    }
}
