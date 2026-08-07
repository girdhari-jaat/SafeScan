package com.safescan.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.safescan.core.DiagnosticsLogger
import com.safescan.data.EditorState
import com.safescan.data.FilterType
import com.safescan.data.PageSaveData
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import com.safescan.domain.ImageProcessor
import com.safescan.domain.model.Point
import com.safescan.domain.model.Quadrilateral
import com.safescan.domain.usecase.ExportPdfUseCase
import com.safescan.domain.usecase.SaveDocumentUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Objects

class ScannerExportHelper(
    private val saveDocumentUseCase: SaveDocumentUseCase,
    private val exportPdfUseCase: ExportPdfUseCase,
    private val documentScanner: DocumentScanner,
    private val imageCacheHelper: ImageCacheHelper
) {

    suspend fun buildPagesData(
        context: Context,
        docId: String,
        tempBitmapsToRecycle: MutableList<Bitmap>,
        overrideFilter: String? = null,
        capturedJpgFiles: List<File>,
        originalJpgBitmaps: Map<Int, Bitmap>,
        jpgCorners: Map<Int, List<Point>>,
        slots: List<Slot>,
        isEditing: Boolean,
        editingJpgIndex: Int?,
        editingSlotId: String?,
        editorState: EditorState,
        recognizedText: String? = null,
        wizardWarp: String,
        currentMode: ScannerMode,
        openedDocumentId: String?
    ): List<PageSaveData> {
        val existingDoc = saveDocumentUseCase.getDocument(docId)
        val existingMetaMap = existingDoc?.pages?.associateBy { it.id } ?: emptyMap()

        val isFlatWarp = wizardWarp == "Flat" || wizardWarp == "Flat Crop Only"

        return if (capturedJpgFiles.isNotEmpty()) {
            capturedJpgFiles.mapIndexed { idx, file ->
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                val originalBmp = originalJpgBitmaps[idx] ?: bmp
                val corners = jpgCorners[idx]
                val pageId = if (idx < slots.size) slots[idx].id else "p${idx + 1}"
                val existingPage = existingMetaMap[pageId]
                    ?: existingDoc?.pages?.find { p -> p.id == pageId || (existingDoc.pages.size == 1 && idx == 0) || (p.id.startsWith("p") && p.id.drop(1) == (idx + 1).toString()) }
                    ?: existingDoc?.pages?.getOrNull(idx)

                val isEditingThisJpg = isEditing && editingJpgIndex == idx
                val currentEdits = if (isEditingThisJpg) editorState else null

                val finalFilterStr = overrideFilter ?: currentEdits?.filter?.name ?: existingPage?.filter ?: "COLOR"
                val rotation = currentEdits?.rotation ?: existingPage?.rotation ?: 0
                val cornersList = corners ?: existingPage?.corners

                var processedBmp: Bitmap? = if (originalBmp != null && cornersList != null && cornersList.size == 4) {
                    val quad = Quadrilateral(cornersList[0], cornersList[1], cornersList[2], cornersList[3])
                    try {
                        documentScanner.cropAndTransform(originalBmp, quad, currentMode.name, flatCrop = isFlatWarp)
                    } catch (e: Exception) { originalBmp }
                } else {
                    originalBmp
                }

                if (processedBmp != null && rotation != 0) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(processedBmp, 0, 0, processedBmp.width, processedBmp.height, matrix, true)
                    if (rotated !== processedBmp && processedBmp !== originalBmp && processedBmp !== bmp) processedBmp.recycle()
                    processedBmp = rotated
                }

                if (processedBmp != null) {
                    val filterEnum = try { FilterType.valueOf(finalFilterStr) } catch (e: Exception) { FilterType.COLOR }
                    val state = EditorState(
                        brightness = currentEdits?.brightness ?: existingPage?.brightness ?: 0f,
                        contrast = currentEdits?.contrast ?: existingPage?.contrast ?: 1.0f,
                        sharpness = currentEdits?.sharpness ?: existingPage?.sharpness ?: 0f,
                        saturation = currentEdits?.saturation ?: existingPage?.saturation ?: 0f,
                        filter = filterEnum
                    )
                    val applied = ImageProcessor.apply(processedBmp, state)
                    if (applied !== processedBmp && processedBmp !== originalBmp && processedBmp !== bmp) processedBmp.recycle()
                    processedBmp = applied
                }

                var tempPreviewFile: File? = null
                if (processedBmp != null) {
                    val tempPath = imageCacheHelper.saveHighResToDisk(processedBmp, pageId, "build_temp_${System.currentTimeMillis()}")
                    if (tempPath != null) tempPreviewFile = File(tempPath)
                    if (processedBmp !== originalBmp && processedBmp !== bmp) {
                        processedBmp.recycle()
                    }
                }
                if (bmp != null && bmp !== originalBmp && !bmp.isRecycled) {
                    bmp.recycle()
                }

                PageSaveData(
                    id = pageId,
                    originalFile = file,
                    previewFile = tempPreviewFile,
                    originalBitmap = originalBmp,
                    previewBitmap = null,
                    corners = cornersList,
                    filter = finalFilterStr,
                    brightness = currentEdits?.brightness ?: existingPage?.brightness ?: 0f,
                    contrast = currentEdits?.contrast ?: existingPage?.contrast ?: 1.0f,
                    sharpness = currentEdits?.sharpness ?: existingPage?.sharpness ?: 0f,
                    saturation = currentEdits?.saturation ?: existingPage?.saturation ?: 0f,
                    rotation = rotation,
                    recognizedText = if (isEditingThisJpg) recognizedText else existingPage?.recognizedText
                )
            }
        } else {
            slots.filter { it.bitmap != null }.map { slot ->
                val slotIdx = slots.indexOf(slot)

                val existingPage = existingMetaMap[slot.id]
                    ?: existingDoc?.pages?.find { p -> p.id == slot.id || (p.id.startsWith("p") && p.id.drop(1) == (slotIdx + 1).toString()) }
                    ?: existingDoc?.pages?.getOrNull(slotIdx)

                val isEditingThisSlot = isEditing && editingSlotId == slot.id
                val currentEdits = if (isEditingThisSlot) editorState else null

                val finalFilterStr = overrideFilter ?: currentEdits?.filter?.name ?: existingPage?.filter ?: "COLOR"
                val rotation = currentEdits?.rotation ?: existingPage?.rotation ?: 0
                val cornersList = slot.corners ?: existingPage?.corners

                val originalRes = imageCacheHelper.getFullResBitmap(slot.id, isOriginal = true, slots = slots, openedDocumentId = openedDocumentId)
                    ?: imageCacheHelper.getFullResBitmap(existingPage?.id ?: slot.id, isOriginal = true, slots = slots, openedDocumentId = openedDocumentId)

                var processedBmp: Bitmap? = if (originalRes != null && cornersList != null && cornersList.size == 4) {
                    val quad = Quadrilateral(cornersList[0], cornersList[1], cornersList[2], cornersList[3])
                    try {
                        documentScanner.cropAndTransform(originalRes, quad, currentMode.name, flatCrop = isFlatWarp)
                    } catch (e: Exception) { originalRes }
                } else {
                    originalRes ?: imageCacheHelper.getFullResBitmap(slot.id, isOriginal = false, slots = slots, openedDocumentId = openedDocumentId) ?: slot.bitmap
                }

                if (processedBmp != null && rotation != 0) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(processedBmp, 0, 0, processedBmp.width, processedBmp.height, matrix, true)
                    if (rotated !== processedBmp && processedBmp !== originalRes && processedBmp !== slot.bitmap) processedBmp.recycle()
                    processedBmp = rotated
                }

                if (processedBmp != null) {
                    val filterEnum = try { FilterType.valueOf(finalFilterStr) } catch (e: Exception) { FilterType.COLOR }
                    val state = EditorState(
                        brightness = currentEdits?.brightness ?: existingPage?.brightness ?: 0f,
                        contrast = currentEdits?.contrast ?: existingPage?.contrast ?: 1.0f,
                        sharpness = currentEdits?.sharpness ?: existingPage?.sharpness ?: 0f,
                        saturation = currentEdits?.saturation ?: existingPage?.saturation ?: 0f,
                        filter = filterEnum
                    )
                    val applied = ImageProcessor.apply(processedBmp, state)
                    if (applied !== processedBmp && processedBmp !== originalRes && processedBmp !== slot.bitmap) processedBmp.recycle()
                    processedBmp = applied
                }

                var tempPreviewFile: File? = null
                if (processedBmp != null) {
                    val tempPath = imageCacheHelper.saveHighResToDisk(processedBmp, slot.id, "build_temp_${System.currentTimeMillis()}")
                    if (tempPath != null) tempPreviewFile = File(tempPath)
                    if (processedBmp !== originalRes && processedBmp !== slot.bitmap) {
                        processedBmp.recycle()
                    }
                }

                PageSaveData(
                    id = slot.id,
                    originalFile = null,
                    previewFile = tempPreviewFile,
                    originalBitmap = originalRes,
                    previewBitmap = null,
                    corners = cornersList,
                    filter = finalFilterStr,
                    brightness = currentEdits?.brightness ?: existingPage?.brightness ?: 0f,
                    contrast = currentEdits?.contrast ?: existingPage?.contrast ?: 1.0f,
                    sharpness = currentEdits?.sharpness ?: existingPage?.sharpness ?: 0f,
                    saturation = currentEdits?.saturation ?: existingPage?.saturation ?: 0f,
                    rotation = rotation,
                    recognizedText = if (isEditingThisSlot) recognizedText else existingPage?.recognizedText
                )
            }
        }
    }

    fun exportPdf(
        scope: CoroutineScope,
        context: Context,
        clearSession: Boolean = false,
        customTitle: String? = null,
        customPageSize: String? = null,
        customOrientation: String? = null,
        customQuality: Float? = null,
        customDpi: Float? = null,
        customWarp: String? = null,
        customFilter: String? = null,
        customCardLayout: String? = null,
        isEditing: Boolean,
        openedDocumentId: String?,
        initialDocumentTitle: String?,
        pageSize: String,
        pdfOrientation: String,
        jpegQuality: Float,
        dpi: Float,
        wizardWarp: String,
        currentMode: ScannerMode,
        slots: List<Slot>,
        capturedJpgFiles: MutableList<File>,
        originalJpgBitmaps: MutableMap<Int, Bitmap>,
        jpgCorners: MutableMap<Int, List<Point>>,
        cachedPdfFile: File?,
        lastExportPdfHash: Int,
        editingJpgIndex: Int?,
        editingSlotId: String?,
        editorState: EditorState,
        recognizedText: String? = null,
        commitActiveEditorChanges: () -> Unit,
        commitActiveEditorChangesSuspend: suspend () -> Unit,
        getOrGenerateDocumentTitle: (String?) -> String,
        reloadSavedDocuments: suspend () -> Unit,
        onDocumentIdAssigned: (String) -> Unit,
        onSessionCleared: () -> Unit,
        onCacheExportedPdf: (File?, Int) -> Unit,
        onResult: (File?) -> Unit
    ) {
        if (isEditing) {
            commitActiveEditorChanges()
        }

        val docIdForHash = openedDocumentId ?: "new_doc"
        val titleForHash = customTitle ?: getOrGenerateDocumentTitle(docIdForHash)
        val targetPageSize = customPageSize ?: pageSize
        val targetOrientation = customOrientation ?: pdfOrientation
        val targetQuality = customQuality ?: jpegQuality
        val targetDpi = customDpi ?: dpi
        val targetCardLayout = customCardLayout ?: "2x4"

        val currentHash = Objects.hash(
            slots.hashCode(),
            capturedJpgFiles.toList().hashCode(),
            titleForHash,
            targetPageSize,
            targetOrientation,
            targetQuality,
            targetDpi,
            customWarp,
            customFilter,
            targetCardLayout
        )

        if (!clearSession && cachedPdfFile != null && cachedPdfFile.exists() && lastExportPdfHash == currentHash) {
            DiagnosticsLogger.info("Using cached PDF file: ${cachedPdfFile.name}")
            onResult(cachedPdfFile)
            return
        }

        DiagnosticsLogger.info("Starting PDF/Document assembly pipeline...")
        val tempBitmapsToRecycle = mutableListOf<Bitmap>()
        val tempFilesToDelete = mutableListOf<String>()
        var pagesData: List<PageSaveData>? = null
        scope.launch(Dispatchers.IO) {
            try {
                if (isEditing) {
                    commitActiveEditorChangesSuspend()
                }

                val docId = openedDocumentId ?: ("doc_" + System.currentTimeMillis())
                if (openedDocumentId == null) {
                    onDocumentIdAssigned(docId)
                }
                val title = customTitle ?: getOrGenerateDocumentTitle(docId)
                pagesData = buildPagesData(
                    context = context,
                    docId = docId,
                    tempBitmapsToRecycle = tempBitmapsToRecycle,
                    overrideFilter = null,
                    capturedJpgFiles = capturedJpgFiles,
                    originalJpgBitmaps = originalJpgBitmaps,
                    jpgCorners = jpgCorners,
                    slots = slots,
                    isEditing = isEditing,
                    editingJpgIndex = editingJpgIndex,
                    editingSlotId = editingSlotId,
                    editorState = editorState,
                    recognizedText = recognizedText,
                    wizardWarp = wizardWarp,
                    currentMode = currentMode,
                    openedDocumentId = docId
                )

                val pagesDataRef = pagesData
                if (pagesDataRef != null && pagesDataRef.isNotEmpty()) {
                    saveDocumentUseCase.saveDocument(docId, title, currentMode.name, pagesDataRef)
                    DiagnosticsLogger.info("Saved document meta of ${pagesDataRef.size} pages securely offline.")
                    reloadSavedDocuments()
                } else {
                    saveDocumentUseCase.deleteDocument(docId)
                    reloadSavedDocuments()
                }

                val savedDoc = saveDocumentUseCase.getDocument(docId)
                val effectiveWarp = customWarp ?: wizardWarp
                val isFlatWarp = effectiveWarp == "Flat" || effectiveWarp == "Flat Crop Only"
                val overrideFilterEnum = customFilter?.let {
                    try {
                        val parsed = FilterType.valueOf(it)
                        if (parsed == FilterType.COLOR) null else parsed
                    } catch (e: Exception) { null }
                }

                val slotsToExport = if (capturedJpgFiles.isNotEmpty()) {
                    capturedJpgFiles.mapIndexed { idx, file ->
                        val pageId = if (idx < slots.size) slots[idx].id else "p${idx + 1}"
                        val pageMeta = savedDoc?.pages?.find { it.id == pageId } ?: savedDoc?.pages?.getOrNull(idx)
                        val rawBmp = BitmapFactory.decodeFile(file.absolutePath)
                        val origBmp = originalJpgBitmaps[idx] ?: rawBmp
                        val corners = jpgCorners[idx] ?: pageMeta?.corners
                        var finalBmp = if (origBmp != null && corners != null && corners.size == 4) {
                            val quad = Quadrilateral(corners[0], corners[1], corners[2], corners[3])
                            try {
                                documentScanner.cropAndTransform(origBmp, quad, currentMode.name, flatCrop = isFlatWarp)
                            } catch (e: Exception) { rawBmp ?: origBmp }
                        } else {
                            rawBmp ?: origBmp
                        }
                        val rotation = pageMeta?.rotation ?: 0
                        if (finalBmp != null && rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            val rotated = Bitmap.createBitmap(finalBmp, 0, 0, finalBmp.width, finalBmp.height, matrix, true)
                            if (rotated !== finalBmp && finalBmp !== origBmp && finalBmp !== rawBmp) finalBmp.recycle()
                            finalBmp = rotated
                        }
                        val activeFilter = overrideFilterEnum ?: try { pageMeta?.filter?.let { FilterType.valueOf(it) } ?: FilterType.COLOR } catch (e: Exception) { FilterType.COLOR }
                        val state = EditorState(
                            brightness = pageMeta?.brightness ?: 0f,
                            contrast = pageMeta?.contrast ?: 1f,
                            sharpness = pageMeta?.sharpness ?: 0f,
                            saturation = pageMeta?.saturation ?: 0f,
                            rotation = rotation,
                            filter = activeFilter
                        )
                        if (finalBmp != null) {
                            val processed = ImageProcessor.apply(finalBmp, state)
                            if (processed !== finalBmp && finalBmp !== origBmp && finalBmp !== rawBmp) finalBmp.recycle()
                            finalBmp = processed
                        }

                        var tempPath: String? = null
                        if (finalBmp != null) {
                            tempPath = imageCacheHelper.saveHighResToDisk(finalBmp, pageId, "export_temp_${System.currentTimeMillis()}")
                            if (tempPath != null) tempFilesToDelete.add(tempPath)
                            if (finalBmp !== origBmp && finalBmp !== rawBmp) {
                                finalBmp.recycle()
                            }
                        }
                        if (rawBmp != null && rawBmp !== origBmp && !rawBmp.isRecycled) {
                            rawBmp.recycle()
                        }
                        Slot(pageId, "Page ${idx + 1}", bitmap = null, bitmapPath = tempPath)
                    }
                } else {
                    slots.filter { it.bitmap != null }.map { slot ->
                        val slotIdx = slots.indexOf(slot)
                        val pageMeta = savedDoc?.pages?.find { it.id == slot.id }
                            ?: savedDoc?.pages?.find { p -> p.id.startsWith("p") && p.id.drop(1) == (slotIdx + 1).toString() }
                            ?: savedDoc?.pages?.getOrNull(slotIdx)
                        val effectivePageId = pageMeta?.id ?: slot.id

                        val originalRes = imageCacheHelper.getFullResBitmap(slot.id, isOriginal = true, slots = slots, openedDocumentId = docId)
                            ?: imageCacheHelper.getFullResBitmap(effectivePageId, isOriginal = true, slots = slots, openedDocumentId = docId)

                        val corners = slot.corners ?: pageMeta?.corners
                        val rotation = pageMeta?.rotation ?: 0

                        var highResBmp: Bitmap? = if (originalRes != null && corners != null && corners.size == 4) {
                            val quad = Quadrilateral(corners[0], corners[1], corners[2], corners[3])
                            try {
                                documentScanner.cropAndTransform(originalRes, quad, currentMode.name, flatCrop = isFlatWarp)
                            } catch (e: Exception) { originalRes }
                        } else {
                            originalRes ?: imageCacheHelper.getFullResBitmap(slot.id, isOriginal = true, slots = slots, openedDocumentId = docId) ?: imageCacheHelper.getFullResBitmap(slot.id, isOriginal = false, slots = slots, openedDocumentId = docId) ?: slot.bitmap
                        }

                        if (highResBmp != null && rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            val rotated = Bitmap.createBitmap(highResBmp, 0, 0, highResBmp.width, highResBmp.height, matrix, true)
                            if (rotated !== highResBmp && highResBmp !== originalRes && highResBmp !== slot.bitmap) highResBmp.recycle()
                            highResBmp = rotated
                        }

                        if (highResBmp != null) {
                            val filterEnum = overrideFilterEnum ?: try { pageMeta?.filter?.let { FilterType.valueOf(it) } ?: FilterType.COLOR } catch (e: Exception) { FilterType.COLOR }
                            val state = EditorState(
                                brightness = pageMeta?.brightness ?: 0f,
                                contrast = pageMeta?.contrast ?: 1f,
                                sharpness = pageMeta?.sharpness ?: 0f,
                                saturation = pageMeta?.saturation ?: 0f,
                                filter = filterEnum
                            )
                            val processed = ImageProcessor.apply(highResBmp, state)
                            if (processed !== highResBmp && highResBmp !== originalRes && highResBmp !== slot.bitmap) highResBmp.recycle()
                            highResBmp = processed
                        }

                        var tempPath: String? = null
                        if (highResBmp != null) {
                            tempPath = imageCacheHelper.saveHighResToDisk(highResBmp, effectivePageId, "export_temp_${System.currentTimeMillis()}")
                            if (tempPath != null) tempFilesToDelete.add(tempPath)
                            if (highResBmp !== originalRes && highResBmp !== slot.bitmap) {
                                highResBmp.recycle()
                            }
                        }

                        if (originalRes != null && originalRes !== slot.bitmap && !originalRes.isRecycled) {
                            originalRes.recycle()
                        }

                        slot.copy(bitmap = null, bitmapPath = tempPath ?: slot.bitmapPath)
                    }
                }

                DiagnosticsLogger.info("Exporting document to PDF at $targetPageSize layout off-thread...")
                val result = exportPdfUseCase.exportCardsToPdf(
                    slotsToExport,
                    title,
                    currentMode,
                    targetPageSize,
                    targetOrientation,
                    dpi = targetDpi,
                    jpegQuality = targetQuality,
                    cardLayout = targetCardLayout
                )
                withContext(Dispatchers.Main) {
                    if (clearSession) {
                        onSessionCleared()
                    } else {
                        onCacheExportedPdf(result.getOrNull(), currentHash)
                    }
                    DiagnosticsLogger.info("PDF document generated successfully.")
                    onResult(result.getOrNull())
                }
            } catch (e: Exception) {
                DiagnosticsLogger.error("PDF Export Pipeline error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResult(null)
                }
            } finally {
                for (bmp in tempBitmapsToRecycle) {
                    if (!bmp.isRecycled) {
                        val isOriginal = originalJpgBitmaps.values.any { it === bmp } ||
                                         slots.any { it.bitmap === bmp }
                        if (!isOriginal) {
                            bmp.recycle()
                        }
                    }
                }
                tempBitmapsToRecycle.clear()

                tempFilesToDelete.forEach { path ->
                    try {
                        val file = File(path)
                        if (file.exists()) file.delete()
                    } catch (e: Exception) {
                        DiagnosticsLogger.error("Failed to delete temp export file: $path")
                    }
                }
                tempFilesToDelete.clear()
                pagesData?.forEach { page ->
                    page.previewFile?.let { if (it.exists()) it.delete() }
                }
            }
        }
    }

    fun saveDocumentOnly(
        scope: CoroutineScope,
        context: Context,
        openedDocumentId: String?,
        currentMode: ScannerMode,
        capturedJpgFiles: List<File>,
        originalJpgBitmaps: Map<Int, Bitmap>,
        jpgCorners: Map<Int, List<Point>>,
        slots: List<Slot>,
        isEditing: Boolean,
        editingJpgIndex: Int?,
        editingSlotId: String?,
        editorState: EditorState,
        recognizedText: String? = null,
        wizardWarp: String,
        getOrGenerateDocumentTitle: (String?) -> String,
        reloadSavedDocuments: suspend () -> Unit,
        onDocumentIdAssigned: (String) -> Unit,
        endSession: () -> Unit,
        onResult: (Boolean) -> Unit
    ) {
        DiagnosticsLogger.info("Starting offline Document save pipeline...")
        val tempBitmapsToRecycle = mutableListOf<Bitmap>()
        var pagesData: List<PageSaveData>? = null
        scope.launch(Dispatchers.IO) {
            try {
                val docId = openedDocumentId ?: ("doc_" + System.currentTimeMillis())
                if (openedDocumentId == null) {
                    onDocumentIdAssigned(docId)
                }
                val title = getOrGenerateDocumentTitle(docId)
                pagesData = buildPagesData(
                    context = context,
                    docId = docId,
                    tempBitmapsToRecycle = tempBitmapsToRecycle,
                    overrideFilter = null,
                    capturedJpgFiles = capturedJpgFiles,
                    originalJpgBitmaps = originalJpgBitmaps,
                    jpgCorners = jpgCorners,
                    slots = slots,
                    isEditing = isEditing,
                    editingJpgIndex = editingJpgIndex,
                    editingSlotId = editingSlotId,
                    editorState = editorState,
                    recognizedText = recognizedText,
                    wizardWarp = wizardWarp,
                    currentMode = currentMode,
                    openedDocumentId = docId
                )

                val pagesDataRef = pagesData
                if (pagesDataRef != null && pagesDataRef.isNotEmpty()) {
                    saveDocumentUseCase.saveDocument(docId, title, currentMode.name, pagesDataRef)
                    DiagnosticsLogger.info("Saved document meta of ${pagesDataRef.size} pages securely offline.")
                    reloadSavedDocuments()
                    withContext(Dispatchers.Main) {
                        endSession()
                        onResult(true)
                    }
                } else {
                    saveDocumentUseCase.deleteDocument(docId)
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
                                         slots.any { it.bitmap === bmp }
                        if (!isOriginal) {
                            bmp.recycle()
                        }
                    }
                }
                tempBitmapsToRecycle.clear()
                pagesData?.forEach { page ->
                    page.previewFile?.let { if (it.exists()) it.delete() }
                }
            }
        }
    }
}
