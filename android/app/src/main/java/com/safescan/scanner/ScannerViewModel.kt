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
    private val edgeDetectionEngine: com.safescan.scanner.EdgeDetectionEngine,
    private val tfLiteEdgeDetectionEngine: com.safescan.scanner.TFLiteEdgeDetectionEngine,
    private val pdfExporter: com.safescan.domain.PdfExporter,
    private val documentRepository: com.safescan.data.DocumentRepository,
    val documentScanner: DocumentScanner
) : ViewModel() {

    // IMPROVEMENT: Using ScannerUiState with isAutoRunning
    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    val isDocumentDetected = MutableStateFlow(false)

    private val captureMutex = kotlinx.coroutines.sync.Mutex()

    val currentMode: StateFlow<ScannerMode> = settingsRepository.scannerModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScannerMode.CARD)
        
    val autoCapture: StateFlow<Boolean> = settingsRepository.autoCaptureFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleAutoCapture() {
        viewModelScope.launch {
            settingsRepository.toggleAutoCapture()
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

    val capturedJpgFiles = androidx.compose.runtime.mutableStateListOf<java.io.File>()
    var openedDocumentId: String? = null
    private var initialDocumentTitle: String? = null
    val originalJpgBitmaps = mutableMapOf<Int, Bitmap>()
    val jpgCorners = mutableMapOf<Int, List<com.safescan.domain.model.Point>>()

    // High-Performance LRU (Least Recently Used) Cache for high-res Bitmaps in RAM to prevent OOM
    private val highResCache = object : android.util.LruCache<String, Bitmap>(8) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            Log.d("ScannerViewModel", "Disk-Backed Hybrid LRU Cache evicted high-res bitmap for key: $key")
        }
    }

    init {
        viewModelScope.launch {
            autoCapture.collect { enabled ->
                _uiState.update { it.copy(isAutoCaptureEnabled = enabled) }
            }
        }
    }

    fun getFullResBitmap(slotId: String, isOriginal: Boolean = false): Bitmap? {
        val cacheKey = if (isOriginal) "${slotId}_original" else "${slotId}_processed"
        val cached = highResCache.get(cacheKey)
        if (cached != null) {
            return cached
        }

        val slot = slots.value.find { it.id == slotId }
        val path = if (isOriginal) slot?.originalBitmapPath else slot?.bitmapPath
        if (path != null) {
            val file = java.io.File(path)
            if (file.exists()) {
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        highResCache.put(cacheKey, bitmap)
                        return bitmap
                    }
                } catch (e: Exception) {
                    Log.e("ScannerViewModel", "Failed to load full-res bitmap from path: $path", e)
                }
            }
        }
        return null
    }

    private fun saveHighResToDisk(bitmap: Bitmap, slotId: String, suffix: String): String? {
        val dir = java.io.File(context.cacheDir, "temp_scans")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = java.io.File(dir, "${slotId}_${suffix}.jpg")
        return try {
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            val sizeKb = file.length() / 1024
            ScannerDebugLogger.logSaveFullImage(sizeKb)
            file.absolutePath
        } catch (e: Exception) {
            Log.e("ScannerViewModel", "Failed to save high-res bitmap to disk", e)
            null
        }
    }

    private fun generateThumbnail(bitmap: Bitmap, maxDimension: Int = 360): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val ratio = kotlin.math.min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        return if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (width * ratio).toInt(),
                (height * ratio).toInt(),
                true
            )
        } else {
            bitmap
        }
    }

    private fun generateDefaultTitle(mode: ScannerMode): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val timestamp = sdf.format(java.util.Date())
        return when (mode) {
            ScannerMode.DOCUMENT -> "Doc_$timestamp"
            ScannerMode.CARD, ScannerMode.GRID -> "Card_$timestamp"
        }
    }

    private fun resolveDynamicFilename(pattern: String, mode: ScannerMode): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val timestamp = sdf.format(java.util.Date())
        
        val trimmed = pattern.trim()
        if (trimmed.isEmpty() || 
            trimmed.equals("Doc+Date+Time", ignoreCase = true) || 
            trimmed.equals("Card+Date+Time", ignoreCase = true) || 
            trimmed.equals("Doc_yyyyMMdd_HHmm", ignoreCase = true) || 
            trimmed.equals("Card_yyyyMMdd_HHmm", ignoreCase = true) || 
            trimmed.equals("Scan_Document", ignoreCase = true)) {
            return when (mode) {
                ScannerMode.DOCUMENT -> "Doc_$timestamp"
                ScannerMode.CARD, ScannerMode.GRID -> "Card_$timestamp"
            }
        }
        
        var resolved = pattern
        if (resolved.contains("Doc+Date+Time", ignoreCase = true)) {
            resolved = resolved.replace("Doc+Date+Time", "Doc_$timestamp", ignoreCase = true)
        }
        if (resolved.contains("Card+Date+Time", ignoreCase = true)) {
            resolved = resolved.replace("Card+Date+Time", "Card_$timestamp", ignoreCase = true)
        }
        if (resolved.contains("Date+Time", ignoreCase = true)) {
            resolved = resolved.replace("Date+Time", timestamp, ignoreCase = true)
        }
        return resolved
    }

    private fun getOrGenerateDocumentTitle(docId: String?): String {
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
        val newTitle = resolveDynamicFilename(pdfFilename.value, currentMode.value)
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
    private val ocrEngine = com.safescan.scanner.OcrEngine(context)
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
                    ScannerMode.DOCUMENT -> emptyList()
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
    private var stableFrameCount = 0
    private var lastQuadPoints: List<com.safescan.domain.model.Point>? = null
    private val STABLE_FRAME_THRESHOLD = 3
    private val STABILITY_TOLERANCE = 30.0
    var isFocusing = false

    fun onDocumentDetected(points: List<com.safescan.domain.model.Point>?, sharpness: Double = 0.0) {
        ScannerDebugLogger.logEnter("ScannerViewModel.onDocumentDetected")
        if (isFocusing) {
            ScannerDebugLogger.logExit("ScannerViewModel.onDocumentDetected")
            return
        }
        
        // Use mutable points variable for smoothing
        var processedPoints = points

        isDocumentDetected.value = (processedPoints != null && processedPoints.size == 4)
        if (!autoCapture.value || processedPoints == null || processedPoints.size != 4) {
            stableFrameCount = 0
            lastQuadPoints = null
            ScannerDebugLogger.logExit("ScannerViewModel.onDocumentDetected")
            return
        }

        // Smoothing: Average with last frame
        if (lastQuadPoints != null) {
            processedPoints = averageCorners(lastQuadPoints!!, processedPoints!!)
        }

        if (processedPoints.size == 4) {
            ScannerDebugLogger.logLiveEdgePoints(
                processedPoints[0].toString(),
                processedPoints[1].toString(),
                processedPoints[2].toString(),
                processedPoints[3].toString()
            )
        }

        // Stability Check
        // Relaxed settings for Auto Capture
        val threshold = if (autoCapture.value) 2 else 3
        if (lastQuadPoints != null && isStable(lastQuadPoints!!, processedPoints)) {
            stableFrameCount++
        } else {
            stableFrameCount = 1
        }
        lastQuadPoints = processedPoints

        ScannerDebugLogger.logStability(stableFrameCount)

        val isSharp = sharpness > 20.0
        val trigger = stableFrameCount >= threshold && isSharp
        ScannerDebugLogger.logAutoCap(inBox = true, sharpness = sharpness, stable = stableFrameCount, trigger = trigger)

        if (trigger) {
            stableFrameCount = 0
            lastQuadPoints = null
            triggerAutoCapture()
        }
        ScannerDebugLogger.logExit("ScannerViewModel.onDocumentDetected")
    }

    private fun averageCorners(p1: List<com.safescan.domain.model.Point>, p2: List<com.safescan.domain.model.Point>): List<com.safescan.domain.model.Point> {
        val result = mutableListOf<com.safescan.domain.model.Point>()
        for (i in 0..3) {
            result.add(com.safescan.domain.model.Point(
                (p1[i].x + p2[i].x) / 2f,
                (p1[i].y + p2[i].y) / 2f
            ))
        }
        return result
    }

    private fun isStable(p1: List<com.safescan.domain.model.Point>, p2: List<com.safescan.domain.model.Point>): Boolean {
        var totalDist = 0.0
        for (i in 0..3) {
            val dx = p1[i].x - p2[i].x
            val dy = p1[i].y - p2[i].y
            totalDist += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return totalDist < STABILITY_TOLERANCE
    }

    private fun triggerAutoCapture() {
        // Trigger capture via event or directly if we have a callback
        isFocusing = true
        _autoCaptureEvent.tryEmit(Unit)
    }

    private val _autoCaptureEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val autoCaptureEvent = _autoCaptureEvent.asSharedFlow()

    fun detectEdges(bitmap: Bitmap, onResult: (List<Point>?) -> Unit) {
        if (bitmap.isRecycled) {
            Log.e("ScannerViewModel", "detectEdges: Provided bitmap is recycled!")
            onResult(null)
            return
        }

        _uiState.update { it.copy(isAutoRunning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var points: List<Point>? = null
            try {
                if (!bitmap.isRecycled) {
                    points = edgeDetectionEngine.detectEdges(bitmap)
                    Log.d("ScannerViewModel", "detectEdges: Successfully detected corners using OpenCV")
                }
            } catch (e: Throwable) {
                Log.e("ScannerViewModel", "detectEdges: OpenCV detection failed", e)
            }

            _uiState.update { it.copy(isAutoRunning = false) }

            withContext(Dispatchers.Main) {
                try {
                    onResult(points)
                } catch (e: Throwable) {
                    Log.e("ScannerViewModel", "detectEdges: Callback onResult failed", e)
                }
            }
        }
    }

    fun detectEdgesWithTFLite(bitmap: Bitmap, onResult: (List<Point>?) -> Unit) {
        if (bitmap.isRecycled) {
            Log.e("ScannerViewModel", "detectEdgesWithTFLite: Provided bitmap is recycled!")
            onResult(null)
            return
        }

        _uiState.update { it.copy(isAutoRunning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var points: List<Point>? = null
            try {
                if (!bitmap.isRecycled) {
                    points = tfLiteEdgeDetectionEngine.detectEdges(bitmap)
                    Log.d("ScannerViewModel", "detectEdgesWithTFLite: Successfully detected corners using TFLite")
                }
            } catch (e: Throwable) {
                Log.e("ScannerViewModel", "detectEdgesWithTFLite: TFLite detection failed", e)
            }

            _uiState.update { it.copy(isAutoRunning = false) }

            withContext(Dispatchers.Main) {
                try {
                    onResult(points)
                } catch (e: Throwable) {
                    Log.e("ScannerViewModel", "detectEdgesWithTFLite: Callback onResult failed", e)
                }
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
            DiagnosticsLogger.info("Auto Crop toggled: $enabled")
        }
    }

    fun cycleFlashMode() {
        viewModelScope.launch {
            val nextMode = when (flashMode.value) {
                com.safescan.data.FlashMode.OFF -> com.safescan.data.FlashMode.AUTO
                com.safescan.data.FlashMode.AUTO -> com.safescan.data.FlashMode.ON
                com.safescan.data.FlashMode.ON -> com.safescan.data.FlashMode.TORCH
                com.safescan.data.FlashMode.TORCH -> com.safescan.data.FlashMode.OFF
            }
            settingsRepository.setFlashMode(nextMode)
            DiagnosticsLogger.info("Flash mode cycled to: ${nextMode.name}")
        }
    }

    fun toggleFlash(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setFlashOn(enabled)
            DiagnosticsLogger.info("Flash toggled: $enabled")
        }
    }

    fun toggleDoubleFocus(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDoubleFocus(enabled)
            DiagnosticsLogger.info("Double Focus toggled: $enabled")
        }
    }

    fun setFocusMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.setFocusMode(mode)
            DiagnosticsLogger.info("Focus Mode set to: $mode")
        }
    }

    fun toggleSaveJpg(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSaveJpg(enabled)
            DiagnosticsLogger.info("Save Raw JPG toggled: $enabled")
        }
    }

    fun toggleAutoPdf(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoPdf(enabled)
            DiagnosticsLogger.info("Auto-PDF generation toggled: $enabled")
        }
    }

    fun toggleBatchScan(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBatchScan(enabled)
            DiagnosticsLogger.info("Batch Scan toggled: $enabled")
        }
    }

    fun toggleShowGrid(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowGrid(enabled)
            DiagnosticsLogger.info("Show Grid Lines toggled: $enabled")
        }
    }

    fun toggleClickSound(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setClickSound(enabled)
            DiagnosticsLogger.info("Capture shutter sound toggled: $enabled")
        }
    }

    fun toggleAutoOrientation(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoOrientation(enabled)
            DiagnosticsLogger.info("Auto Orientation toggled: $enabled")
        }
    }

    fun toggleShadowRemove(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShadowRemove(enabled)
            DiagnosticsLogger.info("Shadow Removal toggled: $enabled")
        }
    }

    fun toggleAutoRotation(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoRotation(enabled)
            DiagnosticsLogger.info("Auto Rotation toggled: $enabled")
        }
    }

    fun setDefaultFilter(filter: String) {
        viewModelScope.launch {
            settingsRepository.setDefaultFilter(filter)
            DiagnosticsLogger.info("Default Filter set to: $filter")
        }
    }

    fun setUiLanguage(language: String) {
        viewModelScope.launch {
            settingsRepository.setUiLanguage(language)
            DiagnosticsLogger.info("UI Language set to: $language")
        }
    }

    fun setVibrateOnCapture(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVibrateOnCapture(enabled)
            DiagnosticsLogger.info("Vibrate On Capture toggled: $enabled")
        }
    }

    fun setSaveToGallery(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSaveToGallery(enabled)
            DiagnosticsLogger.info("Save To Gallery toggled: $enabled")
        }
    }

    fun toggleLiveDetect(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLiveDetect(enabled)
            DiagnosticsLogger.info("Live Edge Detection toggled: $enabled")
        }
    }

    fun toggleBatterySaver(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBatterySaver(enabled)
            DiagnosticsLogger.info("Battery Saver toggled: $enabled")
        }
    }

    fun toggleUsePhoneCamera(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUsePhoneCamera(enabled)
            DiagnosticsLogger.info("Use Phone Camera toggled: $enabled")
        }
    }

    fun toggleUseNativeScanner(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUseNativeScanner(enabled)
            DiagnosticsLogger.info("ML Kit Native Scanner toggled: $enabled")
        }
    }

    fun setHdMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.setHdMode(mode)
            DiagnosticsLogger.info("Capture Quality set to: $mode")
        }
    }
    
    fun setDpi(value: Float) {
        viewModelScope.launch {
            settingsRepository.setDpi(value)
            DiagnosticsLogger.info("Export DPI resolution set to: ${value.toInt()}")
        }
    }
    
    fun setJpegQuality(value: Float) {
        viewModelScope.launch {
            settingsRepository.setJpegQuality(value)
            DiagnosticsLogger.info("Export JPEG Quality set to: ${value.toInt()}%")
        }
    }
    
    fun setPdfFilename(value: String) {
        viewModelScope.launch {
            settingsRepository.setPdfFilename(value)
            DiagnosticsLogger.info("Default PDF filename set to: '$value'")
        }
    }

    fun setWizardDontShowAgain(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWizardDontShowAgain(enabled)
            DiagnosticsLogger.info("Wizard Don't Show Again set to: $enabled")
        }
    }

    fun setWizardWarp(warp: String) {
        viewModelScope.launch {
            settingsRepository.setWizardWarp(warp)
            DiagnosticsLogger.info("Wizard Warp set to: $warp")
        }
    }

    fun setWizardRotation(rotation: String) {
        viewModelScope.launch {
            settingsRepository.setWizardRotation(rotation)
            DiagnosticsLogger.info("Wizard Rotation set to: $rotation")
        }
    }

    fun setWizardManualCrop(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWizardManualCrop(enabled)
            DiagnosticsLogger.info("Wizard Manual Crop set to: $enabled")
        }
    }
    
    fun setPageSize(value: String) {
        viewModelScope.launch {
            settingsRepository.setPageSize(value)
            DiagnosticsLogger.info("Export Page Size set to: $value")
        }
    }

    fun setPdfOrientation(value: String) {
        viewModelScope.launch {
            settingsRepository.setPdfOrientation(value)
            DiagnosticsLogger.info("Export PDF Orientation set to: $value")
        }
    }

    fun saveImageToGallery(context: android.content.Context, bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "SafeScan_${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DCIM + "/SafeScan")
            }
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Image saved to DCIM/SafeScan", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("ScannerViewModel", "Failed to save image", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun savePdfToPublicDocuments(context: android.content.Context, sourceFile: java.io.File) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOCUMENTS + "/SafeScan")
            }
            val uri = resolver.insert(android.provider.MediaStore.Files.getContentUri("external"), contentValues)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        java.io.FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "PDF saved to Documents/SafeScan", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("ScannerViewModel", "Failed to save PDF", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to save PDF", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to save PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun onSlotClick(slotId: String) {
        selectedSlotId.value = slotId
    }

    fun captureToSlot(bitmap: Bitmap, slotId: String, isCapture: Boolean = false, corners: List<Point>? = null) {
        DiagnosticsLogger.info("Processing captured image for slot $slotId...")
        if (openedDocumentId == null) {
            openedDocumentId = "doc_" + System.currentTimeMillis()
        }
        val docId = openedDocumentId!!

        val currentSlots = slots.value.toMutableList()
        val index = currentSlots.indexOfFirst { it.id == slotId }
        if (index != -1) {
            val existing = currentSlots[index]

            // Save high-res processed bitmap to disk
            val processedPath = saveHighResToDisk(bitmap, slotId, "processed")
            highResCache.put("${slotId}_processed", bitmap)
            if (processedPath != null) {
                ScannerDebugLogger.logSaveThumbnail(processedPath)
            }

            // Save high-res original bitmap to disk if it is a new capture, or reuse existing
            var origPath = existing.originalBitmapPath
            if (isCapture || origPath == null) {
                origPath = saveHighResToDisk(bitmap, slotId, "original")
                highResCache.put("${slotId}_original", bitmap)
            }

            // Generate lightweight thumbnail
            val thumbnail = generateThumbnail(bitmap, 360)

            currentSlots[index] = existing.copy(
                bitmap = thumbnail,
                originalBitmap = null, // No high-res original in RAM!
                bitmapPath = processedPath,
                originalBitmapPath = origPath,
                corners = corners ?: existing.corners
            )
            slots.value = currentSlots
            DiagnosticsLogger.info("Slot $slotId loaded with compressed thumbnail & disk paths.")
            
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

            // Auto-save the document state offline immediately to ensure persistent metadata.json exists
            saveDocumentStateOffline(docId)
        }
    }

    private fun saveDocumentStateOffline(docId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val title = getOrGenerateDocumentTitle(docId)
                val tempBitmapsToRecycle = mutableListOf<Bitmap>()
                val pagesData = if (capturedJpgFiles.isNotEmpty()) {
                    capturedJpgFiles.mapIndexed { idx, file ->
                        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            tempBitmapsToRecycle.add(bmp)
                        }
                        val originalBmp = originalJpgBitmaps[idx] ?: bmp
                        val corners = jpgCorners[idx]
                        com.safescan.data.PageSaveData("p$idx", originalBmp, bmp, corners)
                    }
                } else {
                    slots.value.filter { it.bitmap != null }.map { slot ->
                        val fullResProcessed = getFullResBitmap(slot.id, isOriginal = false) ?: slot.bitmap!!
                        val fullResOriginal = getFullResBitmap(slot.id, isOriginal = true) ?: fullResProcessed
                        
                        com.safescan.data.PageSaveData(
                            id = slot.id,
                            originalBitmap = fullResOriginal,
                            previewBitmap = fullResProcessed,
                            corners = slot.corners
                        )
                    }
                }
                
                if (pagesData.isNotEmpty()) {
                    documentRepository.saveDocument(docId, title, currentMode.value.name, pagesData)
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
            }
        }
    }

    fun clearSlot(slotId: String) {
        val currentSlots = slots.value.toMutableList()
        val index = currentSlots.indexOfFirst { it.id == slotId }
        if (index != -1) {
            currentSlots[index] = currentSlots[index].copy(
                bitmap = null,
                originalBitmap = null,
                corners = null
            )
            slots.value = currentSlots
            
            // Sync with capturedJpgFiles if it exists
            if (index < capturedJpgFiles.size) {
                try {
                    capturedJpgFiles[index].delete()
                } catch (e: Exception) {}
                capturedJpgFiles.removeAt(index)
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
            
            originalJpgBitmaps.remove(index)
            jpgCorners.remove(index)
            
            // Also sync back to slots if it corresponds to a slot
            if (index < slots.value.size) {
                val currentSlots = slots.value.toMutableList()
                currentSlots[index] = currentSlots[index].copy(
                    bitmap = null,
                    originalBitmap = null,
                    corners = null
                )
                slots.value = currentSlots
            }

            openedDocumentId?.let { saveDocumentStateOffline(it) }
        }
        checkIfEmptyAndDelete()
    }

    fun endSession() {
        val mode = currentMode.value
        slots.value = when (mode) {
            com.safescan.data.ScannerMode.CARD -> listOf(
                com.safescan.data.Slot("front", "Front"),
                com.safescan.data.Slot("back", "Back")
            )
            com.safescan.data.ScannerMode.DOCUMENT -> emptyList()
            com.safescan.data.ScannerMode.GRID -> (1..8).map {
                com.safescan.data.Slot(it.toString(), "Slot $it")
            }
        }
        selectedSlotId.value = null
        openedDocumentId = null
        initialDocumentTitle = null
        isDocumentOpenedFromLibrary.value = false
        capturedJpgFiles.clear()
        originalJpgBitmaps.clear()
        jpgCorners.clear()
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
            croppingBitmap.value = getFullResBitmap(slotId, isOriginal = true) ?: getFullResBitmap(slotId, isOriginal = false) ?: slot.bitmap
            isCropping.value = true
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
                val cropped = documentScanner.cropAndTransform(bmp, quad, currentMode.value.name)
                val cornersList = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)

                croppingSlotId.value?.let { slotId ->
                    val currentSlots = slots.value.toMutableList()
                    val index = currentSlots.indexOfFirst { it.id == slotId }
                    if (index != -1) {
                        val existing = currentSlots[index]
                        
                        // Save high-res to disk
                        val processedPath = saveHighResToDisk(cropped, slotId, "processed")
                        highResCache.put("${slotId}_processed", cropped)
                        
                        var origPath = existing.originalBitmapPath
                        if (origPath == null) {
                            origPath = saveHighResToDisk(bmp, slotId, "original")
                        }
                        highResCache.put("${slotId}_original", bmp)

                        // Generate lightweight thumbnail
                        val thumbnail = generateThumbnail(cropped, 360)

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
                                cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality.value.toInt(), out)
                                out.flush()
                                out.close()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    
                    // Sync to persistent library JSON if we are editing a saved document
                    openedDocumentId?.let { docId ->
                        documentRepository.updatePageEdits(
                            docId = docId,
                            pageId = slotId,
                            filter = "original",
                            brightness = 0f,
                            contrast = 1.0f,
                            sharpness = 0f,
                            rotation = 0,
                            corners = cornersList,
                            newPreview = cropped
                        )
                    }
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
                if (andNext) {
                    if (currentSlotId != null) {
                        val currentSlots = slots.value
                        val currentIndex = currentSlots.indexOfFirst { it.id == currentSlotId }
                        val nextIndex = currentIndex + 1
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
            val fullRes = getFullResBitmap(slotId, isOriginal = false) ?: slot.bitmap
            if (fullRes != null) {
                editingSlotId.value = slotId
                editingJpgIndex.value = null
                editingBitmapOriginal.value = fullRes
                editingBitmapPreview.value = fullRes
                editorState.value = com.safescan.data.EditorState()
                isEditing.value = true
            }
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

    fun commitActiveEditorChanges() {
        editingBitmapPreview.value?.let { processed ->
            editingSlotId.value?.let { slotId ->
                captureToSlot(processed, slotId)
                
                // Sync to persistent library JSON if we are editing a saved document
                openedDocumentId?.let { docId ->
                    val currentState = editorState.value
                    documentRepository.updatePageEdits(
                        docId = docId,
                        pageId = slotId,
                        filter = currentState.filter.name,
                        brightness = currentState.brightness,
                        contrast = currentState.contrast,
                        sharpness = currentState.sharpness,
                        saturation = currentState.saturation,
                        rotation = 0,
                        corners = null,
                        newPreview = processed
                    )
                }
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

    fun closeEditor(save: Boolean) {
        if (save) {
            commitActiveEditorChanges()
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
        DiagnosticsLogger.info("Starting Text Recognition (OCR) off-thread...")
        viewModelScope.launch(Dispatchers.IO) {
            val result = ocrEngine.recognizeText(bmp)
            withContext(Dispatchers.Main) {
                isOcrRunning.value = false
                when (result) {
                    is com.safescan.core.AppResult.Success -> {
                        recognizedText.value = result.data.joinToString("\n")
                        DiagnosticsLogger.info("OCR completed successfully. Recognized ${result.data.size} lines.")
                    }
                    is com.safescan.core.AppResult.Error -> {
                        recognizedText.value = "Error: ${result.message}"
                        DiagnosticsLogger.error("OCR recognition error: ${result.message}")
                    }
                }
            }
        }
    }

    fun runBarcodeOnCurrentBitmap() {
        val bmp = editingBitmapPreview.value ?: return
        isBarcodeRunning.value = true
        recognizedText.value = null
        DiagnosticsLogger.info("Scanning for Barcode/QR Code...")
        viewModelScope.launch(Dispatchers.IO) {
            val result = ocrEngine.scanQR(bmp)
            withContext(Dispatchers.Main) {
                isBarcodeRunning.value = false
                when (result) {
                    is com.safescan.core.AppResult.Success -> {
                        recognizedText.value = result.data ?: "No QR/Barcode found."
                        DiagnosticsLogger.info("QR/Barcode scan completed: ${result.data}")
                    }
                    is com.safescan.core.AppResult.Error -> {
                        recognizedText.value = "Error: ${result.message}"
                        DiagnosticsLogger.error("QR/Barcode scan error: ${result.message}")
                    }
                }
            }
        }
    }

    fun exportPdf(context: android.content.Context, clearSession: Boolean = false, onResult: (java.io.File?) -> Unit) {
        if (isEditing.value) {
            commitActiveEditorChanges()
        }
        DiagnosticsLogger.info("Starting PDF/Document assembly pipeline...")
        // FIX: FINAL LEAK
        val tempBitmapsToRecycle = mutableListOf<Bitmap>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Also save the captured pages and metadata persistently as a document
                val docId = openedDocumentId ?: ("doc_" + System.currentTimeMillis())
                if (openedDocumentId == null) {
                    openedDocumentId = docId
                }
                val title = getOrGenerateDocumentTitle(docId)
                val pagesData = if (capturedJpgFiles.isNotEmpty()) {
                    capturedJpgFiles.mapIndexed { idx, file ->
                        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            tempBitmapsToRecycle.add(bmp)
                        }
                        val originalBmp = originalJpgBitmaps[idx] ?: bmp
                        val corners = jpgCorners[idx]
                        com.safescan.data.PageSaveData("p$idx", originalBmp, bmp, corners)
                    }
                } else {
                    slots.value.filter { it.bitmap != null }.map { slot ->
                        // Step D: Sequential Document Assembly: Lazy-load the full resolution original and processed bitmaps from disk!
                        val fullResProcessed = getFullResBitmap(slot.id, isOriginal = false) ?: slot.bitmap!!
                        val fullResOriginal = getFullResBitmap(slot.id, isOriginal = true) ?: fullResProcessed
                        
                        com.safescan.data.PageSaveData(
                            id = slot.id,
                            originalBitmap = fullResOriginal,
                            previewBitmap = fullResProcessed,
                            corners = slot.corners
                        )
                    }
                }
                
                if (pagesData.isNotEmpty()) {
                    documentRepository.saveDocument(docId, title, currentMode.value.name, pagesData)
                    DiagnosticsLogger.info("Saved document meta of ${pagesData.size} pages securely offline.")
                    reloadSavedDocuments()
                } else {
                    documentRepository.deleteDocument(docId)
                    reloadSavedDocuments()
                }

                // IMPROVEMENT: Using injected pdfExporter to keep a clean Singleton architecture
                if (autoPdf.value) {
                    val slotsToExport = if (capturedJpgFiles.isNotEmpty()) {
                        capturedJpgFiles.mapIndexed { idx, file ->
                            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            if (bmp != null) {
                                tempBitmapsToRecycle.add(bmp)
                            }
                            Slot("p$idx", "Page ${idx + 1}", bmp)
                        }
                    } else {
                        // For slots, we should pass slots with full resolution processed bitmaps!
                        slots.value.filter { it.bitmap != null }.map { slot ->
                            val fullResProcessed = getFullResBitmap(slot.id, isOriginal = false) ?: slot.bitmap!!
                            slot.copy(bitmap = fullResProcessed)
                        }
                    }
                    DiagnosticsLogger.info("Exporting document to PDF at ${pageSize.value} layout off-thread...")
                    val result = pdfExporter.exportCardsToPdf(slotsToExport, title, currentMode.value, pageSize.value, pdfOrientation.value)
                    withContext(Dispatchers.Main) {
                        if (clearSession) {
                            capturedJpgFiles.clear()
                            originalJpgBitmaps.clear()
                            jpgCorners.clear()
                        }
                        DiagnosticsLogger.info("PDF document generated successfully.")
                        onResult(result.getOrNull())
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (clearSession) {
                            capturedJpgFiles.clear()
                            originalJpgBitmaps.clear()
                            jpgCorners.clear()
                        }
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                DiagnosticsLogger.error("PDF Export Pipeline error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResult(null)
                }
            } finally {
                // FIX: FINAL LEAK
                for (bmp in tempBitmapsToRecycle) {
                    if (!bmp.isRecycled) {
                        val isOriginal = originalJpgBitmaps.values.any { it === bmp } || 
                                         slots.value.any { it.bitmap === bmp }
                        if (!isOriginal) {
                            bmp.recycle()
                        }
                    }
                }
                tempBitmapsToRecycle.clear()
            }
        }
    }

    fun saveDocumentOnly(onResult: (Boolean) -> Unit) {
        DiagnosticsLogger.info("Starting offline Document save pipeline...")
        val tempBitmapsToRecycle = mutableListOf<Bitmap>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val docId = openedDocumentId ?: ("doc_" + System.currentTimeMillis())
                if (openedDocumentId == null) {
                    openedDocumentId = docId
                }
                val title = getOrGenerateDocumentTitle(docId)
                val pagesData = if (capturedJpgFiles.isNotEmpty()) {
                    capturedJpgFiles.mapIndexed { idx, file ->
                        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            tempBitmapsToRecycle.add(bmp)
                        }
                        val originalBmp = originalJpgBitmaps[idx] ?: bmp
                        val corners = jpgCorners[idx]
                        com.safescan.data.PageSaveData("p$idx", originalBmp, bmp, corners)
                    }
                } else {
                    slots.value.filter { it.bitmap != null }.map { slot ->
                        val fullResProcessed = getFullResBitmap(slot.id, isOriginal = false) ?: slot.bitmap!!
                        val fullResOriginal = getFullResBitmap(slot.id, isOriginal = true) ?: fullResProcessed
                        
                        com.safescan.data.PageSaveData(
                            id = slot.id,
                            originalBitmap = fullResOriginal,
                            previewBitmap = fullResProcessed,
                            corners = slot.corners
                        )
                    }
                }
                
                if (pagesData.isNotEmpty()) {
                    documentRepository.saveDocument(docId, title, currentMode.value.name, pagesData)
                    DiagnosticsLogger.info("Saved document meta of ${pagesData.size} pages securely offline.")
                    reloadSavedDocuments()
                    withContext(Dispatchers.Main) {
                        endSession()
                        onResult(true)
                    }
                } else {
                    documentRepository.deleteDocument(docId)
                    reloadSavedDocuments()
                    withContext(Dispatchers.Main) {
                        endSession()
                        onResult(false)
                    }
                }
            } catch (e: Exception) {
                DiagnosticsLogger.error("Document Save Only error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResult(false)
                }
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
                tempBitmapsToRecycle.clear()
            }
        }
    }

    fun loadDocumentIntoSlots(doc: com.safescan.data.DocumentMetadata) {
        isDocumentOpenedFromLibrary.value = true
        openedDocumentId = doc.id
        initialDocumentTitle = doc.title
        capturedJpgFiles.clear()
        originalJpgBitmaps.clear()
        jpgCorners.clear()
        viewModelScope.launch(Dispatchers.IO) {
            val loadedSlots = doc.pages.map { page ->
                val originalBmp = documentRepository.loadOriginalBitmap(doc.id, page.id)
                val previewBmp = documentRepository.loadPreviewBitmap(doc.id, page.id) ?: originalBmp
                
                var originalPath: String? = null
                var processedPath: String? = null
                
                if (originalBmp != null) {
                    originalPath = saveHighResToDisk(originalBmp, page.id, "original")
                    highResCache.put("${page.id}_original", originalBmp)
                }
                if (previewBmp != null) {
                    processedPath = saveHighResToDisk(previewBmp, page.id, "processed")
                    highResCache.put("${page.id}_processed", previewBmp)
                }

                // Generate downsampled lightweight thumbnail
                val thumbnail = previewBmp?.let { generateThumbnail(it, 360) }

                Slot(
                    id = page.id,
                    label = "Page ${page.id}",
                    bitmap = thumbnail,
                    originalBitmap = null,
                    corners = page.corners,
                    bitmapPath = processedPath,
                    originalBitmapPath = originalPath
                )
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

    fun renameDocument(docId: String, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            documentRepository.renameDocument(docId, newTitle)
            reloadSavedDocuments()
            if (openedDocumentId == docId) {
                initialDocumentTitle = newTitle
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

    fun onCapture(bitmap: Bitmap, isNativeScanned: Boolean = false, forceSkipEditor: Boolean = false) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        
        // Save the raw captured JPG immediately to Scans folder if saveJpg is ON
        if (saveJpg.value) {
            val savedFile = documentRepository.saveJpgToScans(bitmap, jpegQuality.value.toInt())
            if (savedFile != null) {
                capturedJpgFiles.add(savedFile)
            }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            ScannerDebugLogger.logEnter("ScannerViewModel.onCapture")
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

            var processedBitmap = if (shadowRemove.value) {
                try {
                    com.safescan.domain.ImageProcessor.autoEnhance(resizedBitmap)
                } catch (e: Exception) {
                    resizedBitmap
                }
            } else {
                resizedBitmap
            }

            val isAutoCropOff = !autoCrop.value
            captureMutex.withLock {
                var slotId = selectedSlotId.value ?: slots.value.firstOrNull { it.bitmap == null }?.id
                if (slotId == null && currentMode.value == ScannerMode.DOCUMENT) {
                    val newId = "p${slots.value.size + 1}"
                    slots.value = slots.value + Slot(newId, "Page ${slots.value.size + 1}")
                    slotId = newId
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
                    when (val result = scannerEngine.scanDocument(processedBitmap)) {
                        is com.safescan.core.AppResult.Success -> {
                            if (slotId != null) {
                                captureToSlot(result.data.bitmap, slotId, isCapture = true, corners = result.data.corners)
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
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        editingBitmapOriginal.value = rotated
        applyEdits()
    }

    fun moveCapturedJpgFile(fromIndex: Int, toIndex: Int) {
        if (fromIndex in capturedJpgFiles.indices && toIndex in capturedJpgFiles.indices) {
            val file = capturedJpgFiles.removeAt(fromIndex)
            capturedJpgFiles.add(toIndex, file)

            // Reorder maps to stay in sync
            val maxIndex = maxOf(
                capturedJpgFiles.size,
                originalJpgBitmaps.keys.maxOrNull() ?: 0,
                jpgCorners.keys.maxOrNull() ?: 0
            ) + 2

            // Reorder originalJpgBitmaps
            val originalBmpList = (0..maxIndex).map { originalJpgBitmaps[it] }.toMutableList()
            if (fromIndex in originalBmpList.indices && toIndex in originalBmpList.indices) {
                val item = originalBmpList.removeAt(fromIndex)
                originalBmpList.add(toIndex, item)
                originalJpgBitmaps.clear()
                originalBmpList.forEachIndexed { idx, bmp ->
                    if (bmp != null) {
                        originalJpgBitmaps[idx] = bmp
                    }
                }
            }

            // Reorder jpgCorners
            val cornersList = (0..maxIndex).map { jpgCorners[it] }.toMutableList()
            if (fromIndex in cornersList.indices && toIndex in cornersList.indices) {
                val item = cornersList.removeAt(fromIndex)
                cornersList.add(toIndex, item)
                jpgCorners.clear()
                cornersList.forEachIndexed { idx, list ->
                    if (list != null) {
                        jpgCorners[idx] = list
                    }
                }
            }

            // Also update the underlying slots list if they are in sync
            val currentSlots = slots.value.toMutableList()
            if (fromIndex in currentSlots.indices && toIndex in currentSlots.indices) {
                val slot = currentSlots.removeAt(fromIndex)
                currentSlots.add(toIndex, slot)
                slots.value = currentSlots
            }

            openedDocumentId?.let { saveDocumentStateOffline(it) }
        }
    }

    fun moveSlot(fromIndex: Int, toIndex: Int) {
        val currentSlots = slots.value.toMutableList()
        if (fromIndex in currentSlots.indices && toIndex in currentSlots.indices) {
            val slot = currentSlots.removeAt(fromIndex)
            currentSlots.add(toIndex, slot)
            slots.value = currentSlots

            openedDocumentId?.let { saveDocumentStateOffline(it) }
        }
    }
}
