package com.safescan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safescan.data.FilterType
import com.safescan.scanner.ScannerViewModel
import java.util.Locale
import com.safescan.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: ScannerViewModel) {
    val editorState by viewModel.editorState.collectAsState()
    val editingBitmap by viewModel.editingBitmapPreview.collectAsState()
    val recognizedText by viewModel.recognizedText.collectAsState()
    val isOcrRunning by viewModel.isOcrRunning.collectAsState()
    val isBarcodeRunning by viewModel.isBarcodeRunning.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(id = R.string.edit_image),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        editingBitmap?.let {
                            Text(
                                "${it.width} x ${it.height}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeEditor(save = false) }) {
                        Icon(Icons.Default.Close, stringResource(id = R.string.cancel))
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.closeEditor(save = true) }) {
                        Text(stringResource(id = R.string.save), fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Immersive Image Preview
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                editingBitmap?.let { bmp ->
                    com.safescan.ui.ZoomableImage(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Preview",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Tools Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 16.dp)
            ) {
                // Filter Carousel
                Text(
                    "Filters",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
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

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionChip(
                        icon = Icons.Default.AutoFixHigh,
                        label = "Auto",
                        onClick = { viewModel.applyAutoEnhance() },
                        modifier = Modifier.weight(1f)
                    )
                    ActionChip(
                        icon = Icons.Default.Crop,
                        label = "Crop",
                        onClick = {
                            val slotId = viewModel.editingSlotId.value
                            val jpgIndex = viewModel.editingJpgIndex.value
                            viewModel.closeEditor(save = true)
                            if (slotId != null) viewModel.openCrop(slotId)
                            else if (jpgIndex != null) viewModel.openCropForJpg(jpgIndex)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ActionChip(
                        icon = Icons.Default.TextFields,
                        label = "OCR",
                        isLoading = isOcrRunning,
                        onClick = { viewModel.runOcrOnCurrentBitmap() },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Adjustment Sliders (Collapsed or condensed)
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AdjustmentSlider(
                        label = stringResource(id = R.string.brightness),
                        value = editorState.brightness,
                        valueRange = -100f..100f,
                        onValueChange = { viewModel.updateEditorState(editorState.copy(brightness = it)) }
                    )
                    AdjustmentSlider(
                        label = stringResource(id = R.string.contrast),
                        value = editorState.contrast,
                        valueRange = 0.5f..3.0f,
                        onValueChange = { viewModel.updateEditorState(editorState.copy(contrast = it)) }
                    )
                }
            }
        }
    }
}

@Composable
fun FilterItem(
    filterType: FilterType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(70.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Gray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when(filterType) {
                        FilterType.GRAYSCALE -> Icons.Default.InvertColors
                        FilterType.BLACK_WHITE -> Icons.Default.GridGoldenratio
                        FilterType.MAGIC_COLOR -> Icons.Default.AutoAwesome
                        FilterType.COLOR -> Icons.Default.Palette
                        else -> Icons.Default.Filter
                    },
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = filterType.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
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
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = if (valueRange.endInclusive > 10) value.toInt().toString() else String.format("%.1f", value),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.height(32.dp)
        )
    }
}
