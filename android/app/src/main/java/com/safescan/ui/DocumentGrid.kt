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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentGridView(
    viewModel: ScannerViewModel,
    onDismiss: () -> Unit
) {
    val slots by viewModel.slots.collectAsState()
    val capturedJpgs = viewModel.capturedJpgFiles
    val context = androidx.compose.ui.platform.LocalContext.current
    
    val pagesCount = if (capturedJpgs.isNotEmpty()) capturedJpgs.size else slots.count { it.bitmap != null }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Document Grid", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("$pagesCount Pages Captured", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Button(
                    onClick = {
                        onDismiss()
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
                                    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.export_share_pdf)))
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Sharing error", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                if (!viewModel.autoPdf.value) {
                                    android.widget.Toast.makeText(context, "Document saved to Library", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Export Failed", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("COMPILE PDF DOCUMENT", fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (pagesCount == 0) {
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
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), CircleShape)
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
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
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
                                                imageVector = Icons.Default.ArrowBack,
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
                                                imageVector = Icons.Default.ArrowForward,
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
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), CircleShape)
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
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
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
                                                imageVector = Icons.Default.ArrowBack,
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
                                                imageVector = Icons.Default.ArrowForward,
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
