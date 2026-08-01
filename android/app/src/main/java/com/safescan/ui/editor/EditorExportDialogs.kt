package com.safescan.ui.editor

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safescan.utils.PageConfig

@Composable
fun EditorExportPopoverCard(
    exportFolderSelected: String,
    onOpenSettings: () -> Unit,
    onOpenPdf: () -> Unit,
    onSharePdf: () -> Unit,
    onExportPdf: () -> Unit,
    onPrintPdf: () -> Unit
) {
    val context = LocalContext.current
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
            Text(
                text = "PDF export",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

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

            PopoverMenuItem(
                icon = Icons.Default.Settings,
                text = "PDF Export Dialog",
                onClick = onOpenSettings
            )
            PopoverMenuItem(
                icon = Icons.Default.Visibility,
                text = "Open",
                onClick = onOpenPdf
            )
            PopoverMenuItem(
                icon = Icons.Default.Share,
                text = "Share",
                onClick = onSharePdf
            )
            PopoverMenuItem(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                text = "Export",
                onClick = onExportPdf
            )
            PopoverMenuItem(
                icon = Icons.Default.Print,
                text = "Print",
                onClick = onPrintPdf
            )
        }
    }
}

@Composable
fun PopoverMenuItem(
    icon: ImageVector,
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
fun EditorPdfSettingsDialog(
    pdfFilename: String,
    pageSize: String,
    pdfOrientation: String,
    jpegQuality: Float,
    onSetPdfFilename: (String) -> Unit,
    onSetPageSize: (String) -> Unit,
    onSetPdfOrientation: (String) -> Unit,
    onSetJpegQuality: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Select PDF configuration preferences:")

                OutlinedTextField(
                    value = pdfFilename,
                    onValueChange = onSetPdfFilename,
                    label = { Text("File Name Template") },
                    modifier = Modifier.fillMaxWidth()
                )

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
                        PageConfig.ALL_PAGE_SIZES.forEach { size ->
                            DropdownMenuItem(
                                text = { Text(size) },
                                onClick = {
                                    onSetPageSize(size)
                                    pageSizeExpanded = false
                                }
                            )
                        }
                    }
                }

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
                                    onSetPdfOrientation(option)
                                    orientationExpanded = false
                                }
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    val qualityPercent = jpegQuality.coerceIn(60f, 100f).toInt()
                    Text(
                        text = "JPEG/Image Quality: $qualityPercent%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Slider(
                        value = jpegQuality.coerceIn(60f, 100f),
                        onValueChange = onSetJpegQuality,
                        valueRange = 60f..100f,
                        steps = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Ok")
            }
        }
    )
}

@Composable
fun EditorOcrResultDialog(
    text: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}
