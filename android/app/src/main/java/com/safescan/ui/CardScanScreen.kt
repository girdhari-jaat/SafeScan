package com.safescan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.safescan.scanner.ScannerViewModel
import com.safescan.data.ScannerMode
import com.safescan.R

@Composable
fun CardScanScreen(
    viewModel: ScannerViewModel,
    onClose: () -> Unit
) {
    val currentMode by viewModel.currentMode.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).background(Color.Transparent)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (currentMode == ScannerMode.CARD) "ID Card Scanner" else "Grid Scanner",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            // IMPROVEMENT: Added a localized/resource string for Close Content Description
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close, 
                    contentDescription = context.getString(R.string.close), 
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // IMPROVEMENT: Disabled Export & Share PDF if there are no scanned slots
        val hasScans = slots.any { it.bitmap != null }
        Button(
            onClick = {
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
                            android.widget.Toast.makeText(context, "File Sharing Error", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        android.widget.Toast.makeText(context, "Export Failed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enabled = hasScans,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(context.getString(R.string.export_share_pdf))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // IMPROVEMENT: If no slots are loaded or all slots are empty, display a placeholder empty state
        if (slots.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = context.getString(R.string.no_scans_yet),
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (currentMode == ScannerMode.CARD) 2 else 4),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(slots) { index, slot ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(85.6f / 53.98f)
                            .background(Color.DarkGray)
                            .clickable { viewModel.openCrop(slot.id) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (slot.bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = androidx.compose.ui.graphics.asImageBitmap(slot.bitmap),
                                contentDescription = "Slot ${index + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = slot.label,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
