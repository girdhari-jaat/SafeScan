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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val scannerEngine: DocumentScannerEngine,
    private val settingsRepository: SettingsRepository,
    private val edgeDetectionEngine: com.safescan.scanner.EdgeDetectionEngine,
    private val pdfExporter: com.safescan.domain.PdfExporter,
    private val documentRepository: com.safescan.data.DocumentRepository,
    private val mlKitObjectDetector: com.safescan.scanner.MLKitObjectDetector,
    private val mlKitDocumentScanner: com.safescan.scanner.MLKitDocumentScanner,
    private val localMLEngine: com.safescan.android.ml.local.LocalMLEngine
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

    private val _editingBatchIndex = MutableStateFlow<Int?>(null)
    val editingBatchIndex: StateFlow<Int?> = _editingBatchIndex

    // Metadata cache for adjustments
    private val imageMetadata = mutableMapOf<String, org.json.JSONObject>()

    private fun getMetadataForFile(file: java.io.File): org.json.JSONObject {
        val metaFile = java.io.File(file.parent, "${file.name}.meta.json")
        if (imageMetadata.containsKey(file.absolutePath)) {
            return imageMetadata[file.absolutePath]!!
        }
        if (metaFile.exists()) {
            try {
                val json = org.json.JSONObject(metaFile.readText())
                imageMetadata[file.absolutePath] = json
                return json
            } catch (e: Exception) { e.printStackTrace() }
        }
        val newJson = org.json.JSONObject()
        imageMetadata[file.absolutePath] = newJson
        return newJson
    }

    private fun saveMetadataForFile(file: java.io.File, json: org.json.JSONObject) {
        val metaFile = java.io.File(file.parent, "${file.name}.meta.json")
        try {
            metaFile.writeText(json.toString())
            imageMetadata[file.absolutePath] = json
        } catch (e: Exception) { e.printStackTrace() }
    }

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

    val isCropping: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isSettingsOpen: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isGridViewVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val croppingSlotId: MutableStateFlow<String?> = MutableStateFlow(null)
    val croppingBitmap: MutableStateFlow<Bitmap?> = MutableStateFlow(null)

    val savedDocuments: MutableStateFlow<List<com.safescan.data.DocumentMetadata>> = MutableStateFlow(emptyList())
    
    private val _liveDetectionPoints = MutableStateFlow<List<com.safescan.android.scanner.Point>?>(null)
    val liveDetectionPoints: StateFlow<List<com.safescan.android.scanner.Point>?> = _liveDetectionPoints.asStateFlow()

    private val _liveDetectionResolution = MutableStateFlow<Pair<Int, Int>?>(null)
    val liveDetectionResolution: StateFlow<Pair<Int, Int>?> = _liveDetectionResolution.asStateFlow()

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

    fun importFromUri(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    val file = java.io.File(context.filesDir, "captured_${System.currentTimeMillis()}.jpg")
                    java.io.FileOutputStream(file).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    withContext(Dispatchers.Main) {
                        capturedJpgFiles.add(file)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateLiveDetectionPoints(points: List<com.safescan.android.scanner.Point>?, width: Int = 0, height: Int = 0) {
        _liveDetectionPoints.value = points
        if (width > 0 && height > 0) {
            _liveDetectionResolution.value = width to height
        }
    }

    // IMPROVEMENT: Added async detectEdges runner updating isAutoRunning state Flow
    fun detectEdges(imageProxy: androidx.camera.core.ImageProxy, onResult: (List<com.safescan.android.scanner.Point>?, Int, Int) -> Unit) {
        _uiState.update { it.copy(isAutoRunning = true) }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val width = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val height = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        
        viewModelScope.launch(Dispatchers.IO) {
            val points = if (_uiState.value.currentEngine == ScannerEngineType.MLKIT) {
                mlKitObjectDetector.detectDocumentEdges(imageProxy)
            } else {
                null // Local edge detection might need bitmap conversion
            }
            _uiState.update { it.copy(isAutoRunning = false) }
            withContext(Dispatchers.Main) {
                onResult(points, width, height)
            }
        }
    }

    fun detectEdges(bitmap: Bitmap, onResult: (List<com.safescan.android.scanner.Point>?) -> Unit) {
        _uiState.update { it.copy(isAutoRunning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val points = scannerEngine.detectCorners(bitmap)
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
            val appLocale: androidx.core.os.LocaleListCompat = androidx.core.os.LocaleListCompat.forLanguageTags(language)
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
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
        }
        if (selectedSlotId.value == slotId) {
            selectedSlotId.value = null
        }
    }

    fun openCrop(slotId: String) {
        val slot = slots.value.find { it.id == slotId }
        if (slot?.bitmap != null) {
            croppingSlotId.value = slotId
            croppingBitmap.value = slot.bitmap
            isCropping.value = true
        }
    }

    fun closeCrop(save: Boolean) {
        isCropping.value = false
        if (!save) {
            croppingSlotId.value = null
            croppingBitmap.value = null
        }
    }

    fun applyCrop(quad: com.safescan.android.scanner.Quadrilateral) {
        val index = _editingBatchIndex.value
        if (index != null && index < capturedJpgFiles.size) {
            val file = capturedJpgFiles[index]
            val json = getMetadataForFile(file)
            val cropJson = org.json.JSONObject().apply {
                put("tl_x", quad.topLeft.x)
                put("tl_y", quad.topLeft.y)
                put("tr_x", quad.topRight.x)
                put("tr_y", quad.topRight.y)
                put("br_x", quad.bottomRight.x)
                put("br_y", quad.bottomRight.y)
                put("bl_x", quad.bottomLeft.x)
                put("bl_y", quad.bottomLeft.y)
            }
            json.put("crop", cropJson)
            saveMetadataForFile(file, json)
        }

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
            editingBitmapOriginal.value = slot.bitmap
            editingBitmapPreview.value = slot.bitmap
            editorState.value = com.safescan.data.EditorState()
            isEditing.value = true
        }
    }

    fun openEditorFromBatch(index: Int) {
        if (index >= 0 && index < capturedJpgFiles.size) {
            val file = capturedJpgFiles[index]
            _editingBatchIndex.value = index
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    val json = getMetadataForFile(file)
                    val rotation = json.optInt("rotation", 0)
                    
                    var processed = bitmap
                    if (rotation != 0) {
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(rotation.toFloat())
                        processed = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    }

                    withContext(Dispatchers.Main) {
                        editingBitmapOriginal.value = processed
                        editingBitmapPreview.value = processed
                        editorState.value = com.safescan.data.EditorState()
                        isEditing.value = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun rotateEditingBitmap() {
        val index = _editingBatchIndex.value
        if (index != null && index < capturedJpgFiles.size) {
            val file = capturedJpgFiles[index]
            val json = getMetadataForFile(file)
            val currentRotation = json.optInt("rotation", 0)
            json.put("rotation", (currentRotation + 90) % 360)
            saveMetadataForFile(file, json)
        }

        editingBitmapPreview.value?.let { bmp ->
            val matrix = android.graphics.Matrix()
            matrix.postRotate(90f)
            val rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            editingBitmapPreview.value = rotated
            
            editingBitmapOriginal.value?.let { orig ->
                val matrixOrig = android.graphics.Matrix()
                matrixOrig.postRotate(90f)
                editingBitmapOriginal.value = android.graphics.Bitmap.createBitmap(orig, 0, 0, orig.width, orig.height, matrixOrig, true)
            }
        }
    }

    fun closeEditor(save: Boolean) {
        if (save) {
            editingBitmapPreview.value?.let { processed ->
                viewModelScope.launch(Dispatchers.IO) {
                    val index = _editingBatchIndex.value
                    if (index != null && index < capturedJpgFiles.size) {
                        // Editing a batch file
                        val file = capturedJpgFiles[index]
                        try {
                            java.io.FileOutputStream(file).use { out ->
                                processed.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            withContext(Dispatchers.Main) {
                                capturedJpgFiles[index] = file // Trigger refresh
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else if (editingSlotId.value != null) {
                        // Editing a slot in memory
                        withContext(Dispatchers.Main) {
                            captureToSlot(processed, editingSlotId.value!!)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        finishCloseEditor()
                    }
                }
            } ?: finishCloseEditor()
        } else {
            finishCloseEditor()
        }
    }

    private fun finishCloseEditor() {
        isEditing.value = false
        editingSlotId.value = null
        editingBitmapOriginal.value = null
        editingBitmapPreview.value = null
        _editingBatchIndex.value = null
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
                    var finalBitmap = result.data

                    // 1. Shadow Removal (if enabled)
                    if (shadowRemove.value) {
                        finalBitmap = com.safescan.domain.ImageProcessor.removeShadows(finalBitmap)
                    }

                    // 2. Auto Rotation (if enabled)
                    if (autoRotation.value) {
                        // Very simple heuristic: if width > height, rotate 90 deg (assuming portrait is preferred)
                        // In a real app, you might use OCR or ML to detect orientation
                        if (finalBitmap.width > finalBitmap.height) {
                            val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                            finalBitmap = android.graphics.Bitmap.createBitmap(finalBitmap, 0, 0, finalBitmap.width, finalBitmap.height, matrix, true)
                        }
                    }

                    // 3. Auto Orientation (if enabled)
                    if (autoOrientation.value) {
                        // Heuristic: check if the document needs to be flipped or normalized
                        // This can be expanded with more advanced image analysis
                    }

                    // 4. Default Filter (if enabled and not original)
                    val filter = defaultFilter.value
                    if (filter != "original") {
                        finalBitmap = when (filter) {
                            "magic" -> com.safescan.domain.ImageProcessor.autoEnhance(finalBitmap)
                            "grayscale" -> {
                                val state = com.safescan.data.EditorState(filter = com.safescan.data.FilterType.GRAYSCALE)
                                com.safescan.domain.ImageProcessor.apply(finalBitmap, state)
                            }
                            "threshold" -> {
                                val state = com.safescan.data.EditorState(filter = com.safescan.data.FilterType.BLACK_WHITE)
                                com.safescan.domain.ImageProcessor.apply(finalBitmap, state)
                            }
                            else -> finalBitmap
                        }
                    }

                    val slotId = selectedSlotId.value ?: slots.value.firstOrNull { it.bitmap == null }?.id
                    if (slotId != null) {
                        captureToSlot(finalBitmap, slotId)
                        selectedSlotId.value = null
                    }
                    
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            scannedBitmap = finalBitmap,
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
