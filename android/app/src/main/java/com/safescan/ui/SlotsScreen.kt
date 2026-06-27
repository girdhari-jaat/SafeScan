package com.safescan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import com.safescan.scanner.ScannerViewModel
import com.safescan.R

@Composable
fun SlotsScreen(
    viewModel: ScannerViewModel,
    onSlotClick: (String) -> Unit,
    onSlotLongClick: (String) -> Unit
) {
    val currentMode by viewModel.currentMode.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val autoCrop by viewModel.autoCrop.collectAsState()
    val doubleFocus by viewModel.doubleFocusEnabled.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        // LAYER 1: Viewfinder Overlay Guides based on Selected Mood
        ViewfinderOverlay(mode = currentMode, modifier = Modifier.fillMaxSize())

        // LAYER 2: Control Panel and Overlays
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // A. TOP QUICK STATUS TOGGLE BAR (Toggles for everything)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Auto Crop Status Pill
                StatusPillButton(
                    label = "Auto Crop",
                    isActive = autoCrop,
                    onClick = { viewModel.toggleAutoCrop(!autoCrop) }
                )

                // Double Focus Status Pill
                StatusPillButton(
                    label = "Double Focus",
                    isActive = doubleFocus,
                    onClick = { viewModel.toggleDoubleFocus(!doubleFocus) }
                )

                // Engine Switch Status Pill
                Button(
                    onClick = {
                        val current = uiState.currentEngine
                        val next = when (current) {
                            com.safescan.scanner.ScannerEngineType.MLKIT -> com.safescan.scanner.ScannerEngineType.LOCAL_ML
                            com.safescan.scanner.ScannerEngineType.LOCAL_ML -> com.safescan.scanner.ScannerEngineType.MLKIT
                        }
                        viewModel.toggleEngine(next)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    val engineLabel = when (uiState.currentEngine) {
                        com.safescan.scanner.ScannerEngineType.MLKIT -> "ML Kit"
                        com.safescan.scanner.ScannerEngineType.LOCAL_ML -> "Local ML"
                    }
                    Text(
                        text = "Engine: $engineLabel",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // B. CENTER INSTRUCTIONS OVERLAY
            val guideText = when (currentMode) {
                ScannerMode.CARD -> "Align Card Inside Cutout"
                ScannerMode.DOCUMENT -> "Align Document Inside Frame"
                ScannerMode.BOOK -> "Align Book Spine with Yellow Center"
                ScannerMode.GRID -> "Utilize Grid for Centered Alignment"
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = guideText,
                    color = Color.Yellow,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // C. BOTTOM AREA: Viewfinder Mood Selector & Horizontal Slot Carousel
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // I. Viewfinder Mood Segment Toggles
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    listOf(
                        ScannerMode.CARD,
                        ScannerMode.DOCUMENT,
                        ScannerMode.BOOK,
                        ScannerMode.GRID
                    ).forEach { mode ->
                        Button(
                            onClick = { viewModel.switchMode(mode) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (currentMode == mode) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(text = mode.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // II. PDF Export Button
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
                                    android.widget.Toast.makeText(context, "Sharing error", android.widget.Toast.LENGTH_SHORT).show()
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

                // III. Horizontal Slots Carousel Card List
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                        .background(Color.Black.copy(alpha = 0.4f), shape = RoundedCornerShape(12.dp))
                        .padding(8.dp)
                ) {
                    if (slots.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "No Slots Available", color = Color.Gray)
                        }
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(slots) { slot ->
                                Box(modifier = Modifier.width(85.dp)) {
                                    SlotItem(
                                        slot = slot,
                                        onClick = { onSlotClick(slot.id) },
                                        onLongClick = { onSlotLongClick(slot.id) },
                                        onClear = { viewModel.clearSlot(slot.id) }
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

@Composable
fun StatusPillButton(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f),
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (isActive) Color.Green else Color.Gray, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun ViewfinderOverlay(mode: ScannerMode, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val rectWidth: Float
        val rectHeight: Float
        var isBookMode = false

        when (mode) {
            ScannerMode.CARD -> {
                // Card aspect ratio: 3:2 (approx. 1.5)
                rectWidth = width * 0.82f
                rectHeight = rectWidth / 1.5f
            }
            ScannerMode.DOCUMENT -> {
                // Document aspect ratio: A4 (approx. 1.41)
                rectWidth = width * 0.75f
                rectHeight = rectWidth * 1.35f
            }
            ScannerMode.BOOK -> {
                // Book dual page aspect ratio: 16:9
                rectWidth = width * 0.88f
                rectHeight = rectWidth / (16f / 9f)
                isBookMode = true
            }
            ScannerMode.GRID -> {
                rectWidth = 0f
                rectHeight = 0f
            }
        }

        if (rectWidth > 0f && rectHeight > 0f) {
            val left = (width - rectWidth) / 2f
            val top = (height - rectHeight) / 2f

            // 1. Draw outer darkened scrim rectangles
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(0f, 0f),
                size = Size(width, top)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(0f, top + rectHeight),
                size = Size(width, height - (top + rectHeight))
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(0f, top),
                size = Size(left, rectHeight)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(left + rectWidth, top),
                size = Size(width - (left + rectWidth), rectHeight)
            )

            // 2. Draw high-contrast target outline
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(rectWidth, rectHeight),
                cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                style = Stroke(width = 2.5.dp.toPx())
            )

            // 3. Draw book-divider spine if in dual book mode
            if (isBookMode) {
                drawLine(
                    color = Color.Yellow,
                    start = Offset(width / 2f, top),
                    end = Offset(width / 2f, top + rectHeight),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                )
            }
        } else if (mode == ScannerMode.GRID) {
            // Draw standard 3x3 alignment grids
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(width / 3f, 0f),
                end = Offset(width / 3f, height),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(width * 2f / 3f, 0f),
                end = Offset(width * 2f / 3f, height),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(0f, height / 3f),
                end = Offset(width, height / 3f),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(0f, height * 2f / 3f),
                end = Offset(width, height * 2f / 3f),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SlotItem(slot: Slot, onClick: () -> Unit, onLongClick: () -> Unit, onClear: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.LightGray)
            .combinedClickable(
                onClick = { if (slot.bitmap == null) onClick() },
                onLongClick = { if (slot.bitmap != null) onLongClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (slot.bitmap != null) {
            Image(
                bitmap = slot.bitmap.asImageBitmap(),
                contentDescription = slot.label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            IconButton(
                onClick = onClear,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear slot",
                    tint = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                        .padding(4.dp)
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Add, contentDescription = "Add image", tint = Color.DarkGray)
                Spacer(modifier = Modifier.height(2.dp))
                Text(slot.label, color = Color.DarkGray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
