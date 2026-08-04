package com.safescan.scanner

import android.content.Context
import com.safescan.data.DocumentMetadata
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import com.safescan.domain.model.Point
import com.safescan.domain.usecase.SaveDocumentUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ScannerSlotHandler(
    private val context: Context,
    private val saveDocumentUseCase: SaveDocumentUseCase,
    private val settingsRepository: SettingsRepository,
    private val imageCacheHelper: ImageCacheHelper
) {

    fun loadDocumentIntoSlots(
        doc: DocumentMetadata,
        scope: CoroutineScope,
        uiState: MutableStateFlow<ScannerUiState>,
        isDocumentOpenedFromLibrary: MutableStateFlow<Boolean>,
        slots: MutableStateFlow<List<Slot>>,
        capturedJpgFiles: MutableList<File>,
        originalJpgBitmaps: MutableMap<Int, android.graphics.Bitmap>,
        jpgCorners: MutableMap<Int, List<Point>>,
        saveHighResToDisk: (android.graphics.Bitmap, String, String) -> String?,
        generateThumbnail: (android.graphics.Bitmap, Int) -> android.graphics.Bitmap,
        onDocumentOpened: (String, String) -> Unit
    ) {
        uiState.update { it.copy(isLoading = true, error = null) }
        isDocumentOpenedFromLibrary.value = true
        onDocumentOpened(doc.id, doc.title)
        capturedJpgFiles.clear()
        originalJpgBitmaps.clear()
        jpgCorners.clear()
        scope.launch(Dispatchers.IO) {
            val loadedSlots = doc.pages.map { page ->
                val originalBmp = saveDocumentUseCase.loadOriginalBitmap(doc.id, page.id)
                val previewBmp = saveDocumentUseCase.loadPreviewBitmap(doc.id, page.id) ?: originalBmp
                
                var originalPath: String? = null
                var processedPath: String? = null
                
                if (originalBmp != null) {
                    originalPath = saveHighResToDisk(originalBmp, page.id, "original")
                    imageCacheHelper.put("${page.id}_original", originalBmp)
                }
                if (previewBmp != null) {
                    if (previewBmp === originalBmp && originalPath != null) {
                        val procFile = File(context.cacheDir, "temp_scans/${page.id}_processed.jpg")
                        try {
                            File(originalPath).copyTo(procFile, overwrite = true)
                            processedPath = procFile.absolutePath
                        } catch (e: Exception) {
                            processedPath = saveHighResToDisk(previewBmp, page.id, "processed")
                        }
                    } else {
                        processedPath = saveHighResToDisk(previewBmp, page.id, "processed")
                    }
                    imageCacheHelper.put("${page.id}_processed", previewBmp)
                }

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
                slots.value = loadedSlots
                uiState.update { it.copy(isLoading = false, error = null) }
            }
        }
    }

    fun deleteDocument(
        docId: String,
        scope: CoroutineScope,
        reloadSavedDocuments: () -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            saveDocumentUseCase.deleteDocument(docId)
            reloadSavedDocuments()
        }
    }

    fun renameDocument(
        docId: String,
        newTitle: String,
        scope: CoroutineScope,
        openedDocumentId: String?,
        onTitleUpdated: (String) -> Unit,
        reloadSavedDocuments: () -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            saveDocumentUseCase.renameDocument(docId, newTitle)
            reloadSavedDocuments()
            if (openedDocumentId == docId) {
                onTitleUpdated(newTitle)
            }
        }
    }

    fun moveCapturedJpgFile(
        fromIndex: Int,
        toIndex: Int,
        capturedJpgFiles: MutableList<File>,
        originalJpgBitmaps: MutableMap<Int, android.graphics.Bitmap>,
        jpgCorners: MutableMap<Int, List<Point>>,
        slots: MutableStateFlow<List<Slot>>,
        openedDocumentId: String?,
        saveDocumentStateOffline: (String) -> Unit
    ) {
        if (fromIndex in capturedJpgFiles.indices && toIndex in capturedJpgFiles.indices) {
            val file = capturedJpgFiles.removeAt(fromIndex)
            capturedJpgFiles.add(toIndex, file)

            val maxIndex = maxOf(
                capturedJpgFiles.size,
                originalJpgBitmaps.keys.maxOrNull() ?: 0,
                jpgCorners.keys.maxOrNull() ?: 0
            ) + 2

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

            val currentSlots = slots.value.toMutableList()
            if (fromIndex in currentSlots.indices && toIndex in currentSlots.indices) {
                val slot = currentSlots.removeAt(fromIndex)
                currentSlots.add(toIndex, slot)
                slots.value = currentSlots
            }

            openedDocumentId?.let { saveDocumentStateOffline(it) }
        }
    }

    fun moveSlot(
        fromIndex: Int,
        toIndex: Int,
        slots: MutableStateFlow<List<Slot>>,
        openedDocumentId: String?,
        saveDocumentStateOffline: (String) -> Unit
    ) {
        val currentSlots = slots.value.toMutableList()
        if (fromIndex in currentSlots.indices && toIndex in currentSlots.indices) {
            val slot = currentSlots.removeAt(fromIndex)
            currentSlots.add(toIndex, slot)
            slots.value = currentSlots

            openedDocumentId?.let { saveDocumentStateOffline(it) }
        }
    }
}
