package com.safescan.ui

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onStartScan: () -> Unit
) {
    val context = LocalContext.current
    var savedFiles by remember { mutableStateOf(emptyList<File>()) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Helper to reload saved files
    val reloadFiles = {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val files = dir?.listFiles { file ->
            file.isFile && file.name.endsWith(".pdf", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
        savedFiles = files
    }

    LaunchedEffect(Unit) {
        reloadFiles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "SafeScan Documents",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                actions = {
                    // Refresh button
                    IconButton(onClick = {
                        isRefreshing = true
                        reloadFiles()
                        isRefreshing = false
                    }) {
                        Text("🔄", fontSize = 18.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onStartScan,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Text("➕", fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                Text(text = "New Scan", fontWeight = FontWeight.Bold)
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (savedFiles.isEmpty()) {
                // Empty state view
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📄", fontSize = 48.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = "No saved documents yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Tap 'New Scan' button to capture and convert your cards, books, or documents to PDF files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(savedFiles, key = { it.absolutePath }) { file ->
                        DocumentItemCard(
                            file = file,
                            onShare = {
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share PDF"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error sharing PDF", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDelete = {
                                fileToDelete = file
                            }
                        )
                    }
                }
            }
        }

        // Deletion Confirmation Dialog
        fileToDelete?.let { file ->
            AlertDialog(
                onDismissRequest = { fileToDelete = null },
                title = { Text(text = "Delete Document?") },
                text = { Text(text = "Are you sure you want to delete '${file.name}'? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            try {
                                if (file.delete()) {
                                    Toast.makeText(context, "Deleted successfully", Toast.LENGTH_SHORT).show()
                                    reloadFiles()
                                } else {
                                    Toast.makeText(context, "Failed to delete file", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                fileToDelete = null
                            }
                        }
                    ) {
                        Text(text = "Delete", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fileToDelete = null }) {
                        Text(text = "Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun DocumentItemCard(
    file: File,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val formattedSize = remember(file) {
        Formatter.formatShortFileSize(context, file.length())
    }
    val formattedDate = remember(file) {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        sdf.format(Date(file.lastModified()))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onShare() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // PDF Icon Indicator
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Red.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("📕", fontSize = 24.sp)
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text Metadata block
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = file.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formattedSize,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formattedDate,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Share Action button
            IconButton(onClick = onShare) {
                Text("📤", fontSize = 18.sp)
            }

            // Delete Action button
            IconButton(onClick = onDelete) {
                Text("🗑️", fontSize = 18.sp)
            }
        }
    }
}
