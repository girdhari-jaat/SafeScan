package com.safescan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.safescan.data.FilterType

enum class ExportAction {
    SHARE, SAVE, PRINT
}

data class ExportOptions(
    val title: String,
    val pageSize: String,
    val orientation: String,
    val quality: Float,
    val warp: String,
    val filter: FilterType,
    val action: ExportAction
)

@Composable
fun ExportModalDialog(
    initialTitle: String,
    initialPageSize: String = "A4",
    initialOrientation: String = "Auto",
    initialQuality: Float = 90f,
    initialWarp: String = "Perspective",
    initialFilter: FilterType = FilterType.COLOR,
    onDismiss: () -> Unit,
    onConfirmExport: (ExportOptions) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var pageSize by remember { mutableStateOf(initialPageSize) }
    var orientation by remember { mutableStateOf(initialOrientation) }
    var quality by remember { mutableFloatStateOf(initialQuality.coerceIn(60f, 100f)) }
    var warp by remember { mutableStateOf(initialWarp) }
    var selectedFilter by remember { mutableStateOf(initialFilter) }

    var expandedPageSize by remember { mutableStateOf(false) }
    var expandedFilter by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Export PDF Options",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 1. Document Name (Editable Field)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Document Name",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                // 2. Page Size & Document Filter (Dropdown Selectors in one Row)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Page Size",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val pageSizes = com.safescan.utils.PageConfig.ALL_PAGE_SIZES
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { expandedPageSize = true }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = pageSize,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            DropdownMenu(
                                expanded = expandedPageSize,
                                onDismissRequest = { expandedPageSize = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            ) {
                                pageSizes.forEach { size ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = size,
                                                fontSize = 13.sp,
                                                fontWeight = if (size.equals(pageSize, ignoreCase = true)) FontWeight.Bold else FontWeight.Normal,
                                                color = if (size.equals(pageSize, ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            pageSize = size
                                            expandedPageSize = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Document Filter",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val filters = listOf(
                            FilterType.COLOR to "Original",
                            FilterType.MAGIC_COLOR to "Magic",
                            FilterType.PAPER to "Paper",
                            FilterType.CARD to "Card",
                            FilterType.BLACK_WHITE to "B&W",
                            FilterType.GRAYSCALE to "Gray"
                        )
                        val currentFilterLabel = filters.find { it.first == selectedFilter }?.second ?: "Original"

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { expandedFilter = true }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentFilterLabel,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            DropdownMenu(
                                expanded = expandedFilter,
                                onDismissRequest = { expandedFilter = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            ) {
                                filters.forEach { (fType, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = label,
                                                fontSize = 13.sp,
                                                fontWeight = if (selectedFilter == fType) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selectedFilter == fType) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            selectedFilter = fType
                                            expandedFilter = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Page Orientation
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Page Orientation",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Auto", "Portrait", "Landscape").forEach { orient ->
                            val selected = orientation.equals(orient, ignoreCase = true)
                            OptionChip(
                                label = orient,
                                selected = selected,
                                modifier = Modifier.weight(1f),
                                onClick = { orientation = orient }
                            )
                        }
                    }
                }

                // 5. Perspective Warp / Crop Options
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Perspective Warp / Crop",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Perspective", "Flat").forEach { mode ->
                            val selected = warp.equals(mode, ignoreCase = true)
                            OptionChip(
                                label = mode,
                                selected = selected,
                                modifier = Modifier.weight(1f),
                                onClick = { warp = mode }
                            )
                        }
                    }
                }

                // 6. JPEG Quality Slider (60%, 70%, 80%, 90%, 100%)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "JPEG Quality",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${quality.coerceIn(60f, 100f).toInt()}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = quality.coerceIn(60f, 100f),
                        onValueChange = { quality = it },
                        valueRange = 60f..100f,
                        steps = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Action Buttons: Share & Save
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onConfirmExport(
                                ExportOptions(
                                    title = title,
                                    pageSize = pageSize,
                                    orientation = orientation,
                                    quality = quality,
                                    warp = warp,
                                    filter = selectedFilter,
                                    action = ExportAction.SHARE
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            onConfirmExport(
                                ExportOptions(
                                    title = title,
                                    pageSize = pageSize,
                                    orientation = orientation,
                                    quality = quality,
                                    warp = warp,
                                    filter = selectedFilter,
                                    action = ExportAction.SAVE
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save PDF", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

