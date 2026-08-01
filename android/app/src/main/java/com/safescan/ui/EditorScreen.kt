package com.safescan.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalView
import com.safescan.R
import com.safescan.utils.HapticFeedbackHelper
import com.safescan.data.FilterType
import com.safescan.data.ScannerMode
import com.safescan.scanner.ScannerViewModel
import com.safescan.ui.editor.*
import java.text.SimpleDateFormat
import java.util.*

// ======================================================
// Editor Screen Main Entry Point
// ======================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: ScannerViewModel) {
    val context = LocalContext.current

    // ------------------------------------------------------
    // ViewModel State Collection
    // ------------------------------------------------------
    val editorState by viewModel.editorState.collectAsState()
    val editingBitmap by viewModel.editingBitmapPreview.collectAsState()
    val recognizedText by viewModel.recognizedText.collectAsState()
    val isOcrRunning by viewModel.isOcrRunning.collectAsState()

    // PDF Configuration States
    val currentMode by viewModel.currentMode.collectAsState()
    val pageSize by viewModel.pageSize.collectAsState()
    val pdfFilename by viewModel.pdfFilename.collectAsState()
    val pdfOrientation by viewModel.pdfOrientation.collectAsState()
    val jpegQuality by viewModel.jpegQuality.collectAsState()
    val dpi by viewModel.dpi.collectAsState()
    val autoPdf by viewModel.autoPdf.collectAsState()
    val wizardWarp by viewModel.wizardWarp.collectAsState()

    // ------------------------------------------------------
    // Local UI States & Panel Controls
    // ------------------------------------------------------
    var activePanel by remember { mutableStateOf<String?>("filters") } // "filters", "adjustments", null
    var applyAllFilters by remember { mutableStateOf(false) }
    var showExportPopover by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showExportModal by remember { mutableStateOf(false) }
    var exportFolderSelected by remember { mutableStateOf("Internal Storage / Documents / SafeScan") }

    Scaffold(
        topBar = {
            EditorTopBar(
                onClose = { viewModel.closeEditor(save = false) },
                onSaveImage = { editingBitmap?.let { viewModel.saveImageToGallery(context, it) } },
                onToggleExportPopover = { showExportPopover = !showExportPopover },
                onDeletePage = {
                    viewModel.closeEditor(save = false)
                    Toast.makeText(context, "Page Deleted", Toast.LENGTH_SHORT).show()
                },
                onSaveSlot = { viewModel.closeEditor(save = true) },
                onSaveNext = { viewModel.saveAndNext() }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ======================================================
                // Canvas & Preview
                // ======================================================
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    editingBitmap?.let { bmp ->
                        ZoomableImage(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Preview",
                            modifier = Modifier.fillMaxSize()
                        )

                        // Image Resolution Overlay Badge
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${bmp.width} x ${bmp.height}",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                // ======================================================
                // Filters & Adjustments Sub-Panels
                // ======================================================
                AnimatedVisibility(
                    visible = activePanel != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    EditorEnhancementPanel(
                        activePanel = activePanel,
                        editorState = editorState,
                        applyAllFilters = applyAllFilters,
                        onApplyAllToggled = { enabled ->
                            applyAllFilters = enabled
                            if (enabled) {
                                viewModel.applyFilterToAllPages(editorState.filter)
                            }
                        },
                        onFilterSelected = { filter ->
                            viewModel.updateEditorState(editorState.copy(filter = filter))
                            if (applyAllFilters) {
                                viewModel.applyFilterToAllPages(filter)
                            }
                        },
                        onEditorStateUpdate = { newState ->
                            viewModel.updateEditorState(newState)
                        }
                    )
                }

                // ======================================================
                // Bottom Action Bar Toolbar
                // ======================================================
                EditorBottomToolbar(
                    activePanel = activePanel,
                    onCropClick = {
                        val slotId = viewModel.editingSlotId.value
                        val jpgIndex = viewModel.editingJpgIndex.value
                        viewModel.closeEditor(save = true)
                        if (slotId != null) {
                            viewModel.openCrop(slotId)
                        } else if (jpgIndex != null) {
                            viewModel.openCropForJpg(jpgIndex)
                        }
                    },
                    onRotateLeftClick = { viewModel.rotateEditingBitmap(-90f) },
                    onRotateRightClick = { viewModel.rotateEditingBitmap(90f) },
                    onToggleFilter = { activePanel = if (activePanel == "filters") null else "filters" },
                    onToggleAdjust = { activePanel = if (activePanel == "adjustments") null else "adjustments" },
                    onOcrClick = { viewModel.runOcrOnCurrentBitmap() }
                )
            }

            // ======================================================
            // Floating Export Popover Card
            // ======================================================
            AnimatedVisibility(
                visible = showExportPopover,
                enter = fadeIn() + slideInVertically { -20 },
                exit = fadeOut() + slideOutVertically { -20 },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
            ) {
                EditorExportPopoverCard(
                    exportFolderSelected = exportFolderSelected,
                    onOpenSettings = {
                        showExportPopover = false
                        showExportModal = true
                    },
                    onOpenPdf = {
                        showExportPopover = false
                        viewModel.exportPdf(context) { file ->
                            if (file != null) {
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/pdf")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No PDF viewer found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onSharePdf = {
                        showExportPopover = false
                        viewModel.exportPdf(context) { file ->
                            if (file != null) {
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        android.content.Intent.createChooser(intent, "Share Document PDF")
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error sharing file", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onExportPdf = {
                        showExportPopover = false
                        viewModel.exportPdf(context) { file ->
                            if (file != null) {
                                viewModel.savePdfToPublicDocuments(context, file)
                            } else {
                                Toast.makeText(context, "Export Failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onPrintPdf = {
                        showExportPopover = false
                        viewModel.exportPdf(context) { file ->
                            if (file != null) {
                                val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE) as? android.print.PrintManager
                                if (printManager != null) {
                                    val jobName = "${file.name} Document"
                                    val printAdapter = object : android.print.PrintDocumentAdapter() {
                                        override fun onLayout(
                                            oldAttributes: android.print.PrintAttributes?,
                                            newAttributes: android.print.PrintAttributes,
                                            cancellationSignal: android.os.CancellationSignal?,
                                            callback: LayoutResultCallback,
                                            extras: android.os.Bundle?
                                        ) {
                                            if (cancellationSignal?.isCanceled == true) {
                                                callback.onLayoutCancelled()
                                                return
                                            }
                                            val info = android.print.PrintDocumentInfo.Builder(jobName)
                                                .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                                .build()
                                            callback.onLayoutFinished(info, true)
                                        }

                                        override fun onWrite(
                                            pages: Array<out android.print.PageRange>?,
                                            destination: android.os.ParcelFileDescriptor?,
                                            cancellationSignal: android.os.CancellationSignal?,
                                            callback: WriteResultCallback?
                                        ) {
                                            try {
                                                java.io.FileInputStream(file).use { input ->
                                                    java.io.FileOutputStream(destination?.fileDescriptor).use { output ->
                                                        input.copyTo(output)
                                                    }
                                                }
                                                callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                                            } catch (e: Exception) {
                                                callback?.onWriteFailed(e.message)
                                            }
                                        }
                                    }
                                    printManager.print(jobName, printAdapter, null)
                                } else {
                                    Toast.makeText(context, "Print service not available", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    // ======================================================
    // Dialogs
    // ======================================================

    // PDF Export Settings Dialog
    if (showSettingsDialog) {
        EditorPdfSettingsDialog(
            pdfFilename = pdfFilename,
            pageSize = pageSize,
            pdfOrientation = pdfOrientation,
            jpegQuality = jpegQuality,
            onSetPdfFilename = { viewModel.setPdfFilename(it) },
            onSetPageSize = { viewModel.setPageSize(it) },
            onSetPdfOrientation = { viewModel.setPdfOrientation(it) },
            onSetJpegQuality = { viewModel.setJpegQuality(it) },
            onDismiss = { showSettingsDialog = false }
        )
    }

    if (showExportModal) {
        ExportModalDialog(
            initialTitle = viewModel.getOrGenerateDocumentTitle(viewModel.openedDocumentId),
            initialPageSize = pageSize,
            initialOrientation = pdfOrientation,
            initialDpi = dpi,
            initialQuality = jpegQuality,
            initialWarp = wizardWarp,
            initialFilter = editorState.filter,
            onDismiss = { showExportModal = false },
            onConfirmExport = { options ->
                showExportModal = false
                viewModel.exportPdf(
                    context = context,
                    customTitle = options.title,
                    customPageSize = options.pageSize,
                    customOrientation = options.orientation,
                    customQuality = options.quality,
                    customDpi = options.dpi,
                    customWarp = options.warp,
                    customFilter = options.filter.name,
                    customCardLayout = options.cardLayout
                ) { file ->
                    if (file != null) {
                        when (options.action) {
                            ExportAction.SHARE -> {
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        android.content.Intent.createChooser(intent, "Share Document PDF")
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error sharing file", Toast.LENGTH_SHORT).show()
                                }
                            }
                            ExportAction.SAVE -> {
                                viewModel.savePdfToPublicDocuments(context, file)
                            }
                            ExportAction.PRINT -> {
                                val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE) as? android.print.PrintManager
                                if (printManager != null) {
                                    val jobName = "${file.name} Document"
                                    val printAdapter = object : android.print.PrintDocumentAdapter() {
                                        override fun onLayout(
                                            oldAttributes: android.print.PrintAttributes?,
                                            newAttributes: android.print.PrintAttributes,
                                            cancellationSignal: android.os.CancellationSignal?,
                                            callback: LayoutResultCallback,
                                            extras: android.os.Bundle?
                                        ) {
                                            if (cancellationSignal?.isCanceled == true) {
                                                callback.onLayoutCancelled()
                                                return
                                            }
                                            val info = android.print.PrintDocumentInfo.Builder(jobName)
                                                .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                                .build()
                                            callback.onLayoutFinished(info, true)
                                        }

                                        override fun onWrite(
                                            pages: Array<out android.print.PageRange>?,
                                            destination: android.os.ParcelFileDescriptor?,
                                            cancellationSignal: android.os.CancellationSignal?,
                                            callback: WriteResultCallback?
                                        ) {
                                            try {
                                                java.io.FileInputStream(file).use { input ->
                                                    java.io.FileOutputStream(destination?.fileDescriptor).use { output ->
                                                        input.copyTo(output)
                                                    }
                                                }
                                                callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                                            } catch (e: Exception) {
                                                callback?.onWriteFailed(e.message)
                                            }
                                        }
                                    }
                                    printManager.print(jobName, printAdapter, null)
                                } else {
                                    Toast.makeText(context, "Print service not available", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        Toast.makeText(context, "Export Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // OCR Text Result Dialog
    recognizedText?.let { text ->
        EditorOcrResultDialog(
            text = text,
            onDismiss = { viewModel.recognizedText.value = null }
        )
    }
}

