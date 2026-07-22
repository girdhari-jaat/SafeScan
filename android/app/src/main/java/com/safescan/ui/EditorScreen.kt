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
import com.safescan.R
import com.safescan.data.FilterType
import com.safescan.data.ScannerMode
import com.safescan.scanner.ScannerViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: ScannerViewModel) {
    val editorState by viewModel.editorState.collectAsState()
    val editingBitmap by viewModel.editingBitmapPreview.collectAsState()
    val recognizedText by viewModel.recognizedText.collectAsState()
    val isOcrRunning by viewModel.isOcrRunning.collectAsState()
    val context = LocalContext.current

    // State for controlling panels
    var activePanel by remember { mutableStateOf<String?>("filters") } // "filters", "adjustments", null
    var showExportPopover by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var exportFolderSelected by remember { mutableStateOf("Internal Storage / Documents / SafeScan") }

    // Settings State
    val currentMode by viewModel.currentMode.collectAsState()
    val pageSize by viewModel.pageSize.collectAsState()
    val pdfFilename by viewModel.pdfFilename.collectAsState()
    val pdfOrientation by viewModel.pdfOrientation.collectAsState()
    val jpegQuality by viewModel.jpegQuality.collectAsState()
    val autoPdf by viewModel.autoPdf.collectAsState()

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Back/Cancel Button
                    IconButton(
                        onClick = { viewModel.closeEditor(save = false) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.cancel),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 2. Save Image (to gallery) Button
                    IconButton(
                        onClick = { editingBitmap?.let { viewModel.saveImageToGallery(context, it) } },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Save Image",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // 3. PDF Export Options Button
                    IconButton(
                        onClick = { showExportPopover = !showExportPopover },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "PDF Export Options",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // 4. Delete Page Button
                    IconButton(
                        onClick = {
                            viewModel.closeEditor(save = false)
                            Toast.makeText(context, "Page Deleted", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Page",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    // 5. Done / Save to slot Button
                    IconButton(
                        onClick = { viewModel.closeEditor(save = true) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(id = R.string.save),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // 6. Next Button
                    IconButton(
                        onClick = { viewModel.saveAndNext() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Image Canvas
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

                        // Info Overlay
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
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

                // Sub-panels
                AnimatedVisibility(
                    visible = activePanel != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 4.dp,
                        shadowElevation = 4.dp
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (activePanel == "filters") {
                                Text(
                                    text = "Enhancement Filters",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(FilterType.values()) { filterType ->
                                        FilterItem(
                                            filterType = filterType,
                                            isSelected = editorState.filter == filterType,
                                            onClick = { viewModel.updateEditorState(editorState.copy(filter = filterType)) }
                                        )
                                    }
                                }
                            } else if (activePanel == "adjustments") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Manual Adjustments",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        TextButton(
                                            onClick = {
                                                viewModel.updateEditorState(
                                                    editorState.copy(
                                                        brightness = 0f,
                                                        contrast = 1.0f,
                                                        sharpness = 0.0f,
                                                        saturation = 0f
                                                    )
                                                )
                                            },
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Text("Reset", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            AdjustmentSlider(
                                                label = stringResource(id = R.string.brightness),
                                                value = editorState.brightness,
                                                valueRange = -50f..50f, // Reduced brightness range to prevent overexposure
                                                onValueChange = { viewModel.updateEditorState(editorState.copy(brightness = it)) }
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            AdjustmentSlider(
                                                label = stringResource(id = R.string.contrast),
                                                value = editorState.contrast,
                                                valueRange = 0.5f..3.0f,
                                                onValueChange = { viewModel.updateEditorState(editorState.copy(contrast = it)) }
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            AdjustmentSlider(
                                                label = stringResource(id = R.string.sharpness),
                                                value = editorState.sharpness,
                                                valueRange = 0.0f..3.0f,
                                                onValueChange = { viewModel.updateEditorState(editorState.copy(sharpness = it)) }
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            AdjustmentSlider(
                                                label = stringResource(id = R.string.saturation),
                                                value = editorState.saturation,
                                                valueRange = -100f..100f,
                                                onValueChange = { viewModel.updateEditorState(editorState.copy(saturation = it)) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom toolbar matching OSS design and capabilities
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Crop
                        BottomToolbarItem(
                            icon = Icons.Default.Crop,
                            label = "Crop",
                            onClick = {
                                val slotId = viewModel.editingSlotId.value
                                val jpgIndex = viewModel.editingJpgIndex.value
                                viewModel.closeEditor(save = true)
                                if (slotId != null) viewModel.openCrop(slotId)
                                else if (jpgIndex != null) viewModel.openCropForJpg(jpgIndex)
                            }
                        )
                        // Rotate Left
                        BottomToolbarItem(
                            icon = Icons.AutoMirrored.Filled.RotateLeft,
                            label = "Rotate L",
                            onClick = { viewModel.rotateEditingBitmap(-90f) }
                        )
                        // Rotate Right
                        BottomToolbarItem(
                            icon = Icons.AutoMirrored.Filled.RotateRight,
                            label = "Rotate R",
                            onClick = { viewModel.rotateEditingBitmap(90f) }
                        )
                        // Magic / Filter
                        BottomToolbarItem(
                            icon = Icons.Default.AutoFixHigh,
                            label = "Filter",
                            selected = activePanel == "filters",
                            onClick = { activePanel = if (activePanel == "filters") null else "filters" }
                        )
                        // Adjustments
                        BottomToolbarItem(
                            icon = Icons.Default.Tune,
                            label = "Adjust",
                            selected = activePanel == "adjustments",
                            onClick = { activePanel = if (activePanel == "adjustments") null else "adjustments" }
                        )
                        // OCR / Text Recognizer
                        BottomToolbarItem(
                            icon = Icons.Default.TextFields,
                            label = "OCR",
                            onClick = { viewModel.runOcrOnCurrentBitmap() }
                        )
                    }
                }
            }

            // Floating Custom Popover Card (Styled exactly like the OSS Svelte screenshot!)
            AnimatedVisibility(
                visible = showExportPopover,
                enter = fadeIn() + slideInVertically { -20 },
                exit = fadeOut() + slideOutVertically { -20 },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                    modifier = Modifier
                        .width(280.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(16.dp)
                        ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // Header
                        Text(
                            text = "PDF export",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Export folder section
                        Text(
                            text = "Export folder",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = exportFolderSelected,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                Toast.makeText(context, "Scanning local directories...", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Change Directory",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // Menu items
                        PopoverMenuItem(
                            icon = Icons.Default.Settings,
                            text = "PDF export settings",
                            onClick = {
                                showExportPopover = false
                                showSettingsDialog = true
                            }
                        )
                        PopoverMenuItem(
                            icon = Icons.Default.Visibility,
                            text = "Open",
                            onClick = {
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
                            }
                        )
                        PopoverMenuItem(
                            icon = Icons.Default.Share,
                            text = "Share",
                            onClick = {
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
                                            context.startActivity(android.content.Intent.createChooser(intent, "Share Document PDF"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Error sharing file", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        )
                        PopoverMenuItem(
                            icon = Icons.AutoMirrored.Filled.ExitToApp,
                            text = "Export",
                            onClick = {
                                showExportPopover = false
                                viewModel.exportPdf(context) { file ->
                                    if (file != null) {
                                        viewModel.savePdfToPublicDocuments(context, file)
                                    } else {
                                        Toast.makeText(context, "Export Failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        PopoverMenuItem(
                            icon = Icons.Default.Print,
                            text = "Print",
                            onClick = {
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
        }
    }

    // Modal Dialog for PDF Settings
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Select PDF configuration preferences:")
                    
                    // 1. File Naming Template
                    OutlinedTextField(
                        value = pdfFilename,
                        onValueChange = { viewModel.setPdfFilename(it) },
                        label = { Text("File Name Template") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 2. Page Size
                    var pageSizeExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = pageSize,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("Page Size") },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Page Size",
                                    modifier = Modifier.clickable { pageSizeExpanded = !pageSizeExpanded }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { pageSizeExpanded = true }
                        )
                        DropdownMenu(
                            expanded = pageSizeExpanded,
                            onDismissRequest = { pageSizeExpanded = false }
                        ) {
                            com.safescan.utils.PageConfig.ALL_PAGE_SIZES.forEach { size ->
                                DropdownMenuItem(
                                    text = { Text(size) },
                                    onClick = {
                                        viewModel.setPageSize(size)
                                        pageSizeExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // 3. Page Orientation
                    var orientationExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = pdfOrientation,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("Page Orientation") },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Orientation",
                                    modifier = Modifier.clickable { orientationExpanded = !orientationExpanded }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { orientationExpanded = true }
                        )
                        DropdownMenu(
                            expanded = orientationExpanded,
                            onDismissRequest = { orientationExpanded = false }
                        ) {
                            listOf("Auto", "Portrait", "Landscape").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        viewModel.setPdfOrientation(option)
                                        orientationExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // 4. JPEG Quality Slider (60% to 100% discrete, matching Settings)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val qualityPercent = jpegQuality.coerceIn(60f, 100f).toInt()
                        Text(
                            text = "JPEG/Image Quality: $qualityPercent%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Slider(
                            value = jpegQuality.coerceIn(60f, 100f),
                            onValueChange = { viewModel.setJpegQuality(it) },
                            valueRange = 60f..100f,
                            steps = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Ok")
                }
            }
        )
    }

    // Recognized OCR Text Dialog
    recognizedText?.let { text ->
        AlertDialog(
            onDismissRequest = { viewModel.recognizedText.value = null },
            title = { Text("Recognized Text (OCR)") },
            text = {
                Box(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(text)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.recognizedText.value = null }) {
                    Text("Dismiss")
                }
            }
        )
    }
}

@Composable
fun BottomToolbarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PopoverMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun FilterItem(
    filterType: FilterType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val displayName = when (filterType) {
        FilterType.COLOR -> "Original"
        FilterType.MAGIC_COLOR -> "Magic"
        FilterType.PAPER -> "Paper"
        FilterType.CARD -> "Card"
        FilterType.BLACK_WHITE -> "B&W"
        FilterType.GRAYSCALE -> "Gray"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .border(
                width = 2.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayName,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

@Composable
fun AdjustmentSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = String.format("%.1f", value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
