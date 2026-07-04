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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.safescan.data.DocumentMetadata
import com.safescan.scanner.ScannerViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: ScannerViewModel,
    onStartScan: () -> Unit,
    onOpenDocument: (DocumentMetadata) -> Unit
) {
    val context = LocalContext.current
    var savedFiles by remember { mutableStateOf(emptyList<File>()) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var docToDelete by remember { mutableStateOf<DocumentMetadata?>(null) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Original Docs, 1: Exported PDFs

    val savedDocs by viewModel.savedDocuments.collectAsState()

    // Helper to reload saved PDF files
    val reloadFiles = {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val files = dir?.listFiles { file ->
            file.isFile && file.name.endsWith(".pdf", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
        savedFiles = files
        viewModel.reloadSavedDocuments()
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
                        reloadFiles()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
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
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(text = "New Scan", fontWeight = FontWeight.Bold)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Tab Segment
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Original Docs (${savedDocs.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Exported PDFs (${savedFiles.size})") }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (selectedTab == 0) {
                    // Tab 1: Original Saved Documents
                    if (savedDocs.isEmpty()) {
                        EmptyStateView(
                            emoji = "📁",
                            title = "No original documents saved",
                            description = "Once you capture files and export them, the complete original page images and edits are automatically saved here for future editing."
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(savedDocs, key = { it.id }) { doc ->
                                OriginalDocumentCard(
                                    doc = doc,
                                    onClick = { onOpenDocument(doc) },
                                    onDelete = { docToDelete = doc }
                                )
                            }
                        }
                    }
                } else {
                    // Tab 2: Exported PDF Files
                    if (savedFiles.isEmpty()) {
                        EmptyStateView(
                            emoji = "📄",
                            title = "No exported PDFs yet",
                            description = "Tap 'New Scan' button to capture and compile cards or multi-page paper sheets into standard offline PDF documents."
                        )
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
            }
        }

        // PDF Deletion Confirmation Dialog
        fileToDelete?.let { file ->
            AlertDialog(
                onDismissRequest = { fileToDelete = null },
                title = { Text(text = "Delete PDF File?") },
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

        // Original Document Deletion Confirmation Dialog
        docToDelete?.let { doc ->
            AlertDialog(
                onDismissRequest = { docToDelete = null },
                title = { Text(text = "Delete Original Document?") },
                text = { Text(text = "Are you sure you want to delete '${doc.title}' along with all original page images? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteDocument(doc.id)
                            Toast.makeText(context, "Deleted original document", Toast.LENGTH_SHORT).show()
                            docToDelete = null
                        }
                    ) {
                        Text(text = "Delete", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { docToDelete = null }) {
                        Text(text = "Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun EmptyStateView(
    emoji: String,
    title: String,
    description: String
) {
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
            Text(emoji, fontSize = 48.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun OriginalDocumentCard(
    doc: DocumentMetadata,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val formattedDate = remember(doc) {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        sdf.format(Date(doc.createdAt))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
            // Folder Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = doc.title,
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
                        text = "${doc.pages.size} pages",
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

            // Open/Edit button
            IconButton(onClick = onClick) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
            }

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
            }
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
            // PDF Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Red.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color.Red)
            }

            Spacer(modifier = Modifier.width(16.dp))

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

            // Share button
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(20.dp))
            }

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
            }
        }
    }
}
