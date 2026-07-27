package com.safescan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.safescan.R
import com.safescan.scanner.ScannerViewModel
import com.safescan.data.Slot
import com.safescan.data.FilterType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentGridView(
    viewModel: ScannerViewModel,
    onDismiss: () -> Unit,
    onScanPage: (() -> Unit)? = null
) {
    val slots by viewModel.slots.collectAsState()
    val capturedJpgs = viewModel.capturedJpgFiles
    val context = androidx.compose.ui.platform.LocalContext.current
    var showExportModal by remember { mutableStateOf(false) }
    val pageSize by viewModel.pageSize.collectAsState()
    val pdfOrientation by viewModel.pdfOrientation.collectAsState()
    val jpegQuality by viewModel.jpegQuality.collectAsState()
    val wizardWarp by viewModel.wizardWarp.collectAsState()
    val defaultFilterStr by viewModel.defaultFilter.collectAsState()
    val initialFilter = try {
        FilterType.valueOf(defaultFilterStr.uppercase())
    } catch (e: Exception) {
        FilterType.COLOR
    }
    
    val pagesCount = if (capturedJpgs.isNotEmpty()) capturedJpgs.size else slots.count { it.bitmap != null }
    val documentTitle = viewModel.getOrGenerateDocumentTitle(viewModel.openedDocumentId)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(documentTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("$pagesCount Pages Captured", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (onScanPage != null) {
                                onScanPage()
                            } else {
                                viewModel.isDocumentOpenedFromLibrary.value = false
                                viewModel.isGridViewVisible.value = false
                                viewModel.selectedSlotId.value = null
                            }
                        }
                    ) {
                        Text(
                            text = "Scan Page",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.saveDocumentOnly { success ->
                                if (success) {
                                    android.widget.Toast.makeText(context, "Document saved offline", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Failed to save", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Text("SAVE DOCUMENT", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = { showExportModal = true },
                        modifier = Modifier
                            .weight(1.2f)
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("EXPORT PDF", fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                }
            }

            if (showExportModal) {
                ExportModalDialog(
                    initialTitle = documentTitle,
                    initialPageSize = pageSize,
                    initialOrientation = pdfOrientation,
                    initialQuality = jpegQuality,
                    initialWarp = wizardWarp,
                    initialFilter = initialFilter,
                    onDismiss = { showExportModal = false },
                    onConfirmExport = { options ->
                        showExportModal = false
                        viewModel.setWizardWarp(options.warp)
                        viewModel.setJpegQuality(options.quality)
                        viewModel.setDefaultFilter(options.filter.name)
                        viewModel.exportPdf(
                            context = context,
                            customTitle = options.title,
                            customPageSize = options.pageSize,
                            customOrientation = options.orientation,
                            customQuality = options.quality
                        ) { file ->
                            viewModel.endSession()
                            onDismiss()
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
                                            context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.export_share_pdf)))
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "Sharing error", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    ExportAction.SAVE -> {
                                        viewModel.savePdfToPublicDocuments(context, file)
                                        android.widget.Toast.makeText(context, "PDF Saved to Documents", android.widget.Toast.LENGTH_SHORT).show()
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
                                        }
                                    }
                                }
                            } else {
                                android.widget.Toast.makeText(context, "Export Failed", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
                }
            }
        }
    ) { paddingValues ->
        val uiState by viewModel.uiState.collectAsState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (pagesCount == 0) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No pages captured yet.", color = Color.Gray)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (capturedJpgs.isNotEmpty()) {
                        items(capturedJpgs.size) { idx ->
                            val file = capturedJpgs[idx]
                            Box(
                                modifier = Modifier
                                    .aspectRatio(0.72f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.DarkGray)
                                    .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            ) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = "Page ${idx + 1}",
                                    modifier = Modifier.fillMaxSize().clickable {
                                        viewModel.openEditorForJpg(idx)
                                    },
                                    contentScale = ContentScale.Crop
                                )
                                
                                // Edit/Crop button
                                IconButton(
                                    onClick = {
                                        viewModel.openCropForJpg(idx)
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .size(28.dp)
                                ) {
                                    Text("✂️", fontSize = 12.sp)
                                }
                                
                                // Delete/Clear button
                                IconButton(
                                    onClick = {
                                        viewModel.clearJpgAt(idx)
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Delete Page", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                                
                                // Index badge
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(8.dp)
                                        .size(24.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${idx + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                // Reorder buttons
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                        .padding(2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    if (idx > 0) {
                                        IconButton(
                                            onClick = {
                                                viewModel.moveCapturedJpgFile(idx, idx - 1)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Move Left",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    if (idx < capturedJpgs.size - 1) {
                                        IconButton(
                                            onClick = {
                                                viewModel.moveCapturedJpgFile(idx, idx + 1)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = "Move Right",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val activeSlotsList = slots.filter { it.bitmap != null }
                        items(activeSlotsList.size) { idx ->
                            val slot = activeSlotsList[idx]
                            Box(
                                modifier = Modifier
                                    .aspectRatio(0.72f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.DarkGray)
                                    .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            ) {
                                AsyncImage(
                                    model = slot.bitmapPath ?: slot.bitmap,
                                    contentDescription = slot.label,
                                    modifier = Modifier.fillMaxSize().clickable {
                                        viewModel.openEditor(slot.id)
                                    },
                                    contentScale = ContentScale.Crop
                                )
                                
                                // Edit/Crop button
                                IconButton(
                                    onClick = {
                                        viewModel.openCrop(slot.id)
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .size(28.dp)
                                ) {
                                    Text("✂️", fontSize = 12.sp)
                                }

                                // Delete/Clear button
                                IconButton(
                                    onClick = {
                                        viewModel.clearSlot(slot.id)
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Delete Page", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                                
                                // Index badge
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(8.dp)
                                        .size(24.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${idx + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                // Reorder buttons
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                        .padding(2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    if (idx > 0) {
                                        IconButton(
                                            onClick = {
                                                val originalFromIdx = slots.indexOfFirst { it.id == slot.id }
                                                val prevSlot = activeSlotsList[idx - 1]
                                                val originalToIdx = slots.indexOfFirst { it.id == prevSlot.id }
                                                if (originalFromIdx != -1 && originalToIdx != -1) {
                                                    viewModel.moveSlot(originalFromIdx, originalToIdx)
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Move Left",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    if (idx < activeSlotsList.size - 1) {
                                        IconButton(
                                            onClick = {
                                                val originalFromIdx = slots.indexOfFirst { it.id == slot.id }
                                                val nextSlot = activeSlotsList[idx + 1]
                                                val originalToIdx = slots.indexOfFirst { it.id == nextSlot.id }
                                                if (originalFromIdx != -1 && originalToIdx != -1) {
                                                    viewModel.moveSlot(originalFromIdx, originalToIdx)
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = "Move Right",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
