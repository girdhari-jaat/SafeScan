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

    // Date/Time for TopBar
    val formattedDateTime = remember {
        val sdfDate = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val sdfTime = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        val now = Date()
        Pair(sdfDate.format(now), sdfTime.format(now).uppercase())
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = formattedDateTime.first,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = formattedDateTime.second,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeEditor(save = false) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(id = R.string.cancel))
                    }
                },
                actions = {
                    // PDF Icon to trigger Popover
                    IconButton(onClick = { showExportPopover = !showExportPopover }) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "PDF Export Options",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Delete Icon
                    IconButton(onClick = {
                        viewModel.closeEditor(save = false)
                        Toast.makeText(context, "Page Deleted", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Page")
                    }
                    // Save Button
                    IconButton(onClick = { viewModel.closeEditor(save = true) }) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(id = R.string.save), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
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
                        .background(Color.Black),
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
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${bmp.width} x ${bmp.height}",
                                color = Color.White,
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
                                        .maxHeight(220.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = "Manual Adjustments",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    AdjustmentSlider(
                                        label = stringResource(id = R.string.brightness),
                                        value = editorState.brightness,
                                        valueRange = -100f..100f,
                                        onValueChange = { viewModel.updateEditorState(editorState.copy(brightness = it)) }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    AdjustmentSlider(
                                        label = stringResource(id = R.string.contrast),
                                        value = editorState.contrast,
                                        valueRange = 0.5f..3.0f,
                                        onValueChange = { viewModel.updateEditorState(editorState.copy(contrast = it)) }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
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
                            icon = Icons.Default.RotateLeft,
                            label = "Rotate L",
                            onClick = { viewModel.rotateEditingBitmap(-90f) }
                        )
                        // Rotate Right
                        BottomToolbarItem(
                            icon = Icons.Default.RotateRight,
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
                            icon = Icons.Default.ExitToApp,
                            text = "Export",
                            onClick = {
                                showExportPopover = false
                                viewModel.exportPdf(context) { file ->
                                    if (file != null) {
                                        Toast.makeText(context, "PDF Exported successfully to Documents!", Toast.LENGTH_LONG).show()
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
                                                        val input = java.io.FileInputStream(file)
                                                        val output = java.io.FileOutputStream(destination?.fileDescriptor)
                                                        input.copyTo(output)
                                                        input.close()
                                                        output.close()
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
                        PopoverMenuItem(
                            icon = Icons.Default.PictureInPicture,
                            text = "Preview",
                            onClick = {
                                showExportPopover = false
                                Toast.makeText(context, "Generating Document Preview...", Toast.LENGTH_SHORT).show()
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
            title = { Text("PDF export settings", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Select PDF configuration preferences:")
                    OutlinedTextField(
                        value = pageSize,
                        onValueChange = { viewModel.setPageSize(it) },
                        label = { Text("Page Size (e.g. A4, Letter)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = currentMode.name,
                        onValueChange = { },
                        enabled = false,
                        label = { Text("Scanner Mode (Read-only)") },
                        modifier = Modifier.fillMaxWidth()
                    )
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
        FilterType.AUTO -> "Auto"
        FilterType.GRAYSCALE -> "Grayscale"
        FilterType.BLACK_WHITE -> "B&W"
        FilterType.MAGIC_COLOR -> "Magic"
        FilterType.PHOTO -> "Photo"
        FilterType.CARD -> "Card"
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
