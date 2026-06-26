package com.safescan.scanner

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val openCvScanner: com.safescan.android.scanner.OpenCVScanner
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

    val slots: MutableStateFlow<List<Slot>> = MutableStateFlow(emptyList())
    val selectedSlotId: MutableStateFlow<String?> = MutableStateFlow(null)

    val isEditing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val editingSlotId: MutableStateFlow<String?> = MutableStateFlow(null)
    val editingBitmapOriginal: MutableStateFlow<Bitmap?> = MutableStateFlow(null)
    val editingBitmapPreview: MutableStateFlow<Bitmap?> = MutableStateFlow(null)
    val editorState: MutableStateFlow<com.safescan.data.EditorState> = MutableStateFlow(com.safescan.data.EditorState())

    val isCropping: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val croppingSlotId: MutableStateFlow<String?> = MutableStateFlow(null)
    val croppingBitmap: MutableStateFlow<Bitmap?> = MutableStateFlow(null)

    init {
        _uiState.update { it.copy(currentEngine = scannerEngine.engineType) }
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
                    ScannerMode.BOOK -> listOf(
                        Slot("left", "Left Page"),
                        Slot("right", "Right Page")
                    )
                    ScannerMode.GRID -> (1..8).map {
                        Slot(it.toString(), "Slot $it")
                    }
                }
                selectedSlotId.value = null
            }
        }
    }

    // IMPROVEMENT: Added async detectEdges runner updating isAutoRunning state Flow
    fun detectEdges(bitmap: Bitmap, onResult: (List<org.opencv.core.Point>?) -> Unit) {
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
        viewModelScope.launch(Dispatchers.IO) {
            croppingBitmap.value?.let { bmp ->
                // IMPROVEMENT: Using injected openCvScanner instance to avoid instantiation overhead
                val cropped = openCvScanner.cropDocument(bmp, quad)
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

    fun closeEditor(save: Boolean) {
        if (save) {
            editingBitmapPreview.value?.let { processed ->
                editingSlotId.value?.let { slotId ->
                    captureToSlot(processed, slotId)
                }
            }
        }
        isEditing.value = false
        editingSlotId.value = null
        editingBitmapOriginal.value = null
        editingBitmapPreview.value = null
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
            }
        }
    }

    fun exportPdf(context: android.content.Context, onResult: (java.io.File?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // IMPROVEMENT: Using injected pdfExporter to keep a clean Singleton architecture
                val result = pdfExporter.exportCardsToPdf(slots.value, pdfFilename.value, currentMode.value)
                withContext(Dispatchers.Main) {
                    onResult(result.getOrNull())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(null)
                }
            }
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
        
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = scannerEngine.scanDocument(bitmap)) {
                is com.safescan.core.AppResult.Success -> {
                    val slotId = selectedSlotId.value ?: slots.value.firstOrNull { it.bitmap == null }?.id
                    if (slotId != null) {
                        captureToSlot(result.data, slotId)
                        selectedSlotId.value = null
                    }
                    
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            scannedBitmap = result.data,
                            error = null
                        )
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
