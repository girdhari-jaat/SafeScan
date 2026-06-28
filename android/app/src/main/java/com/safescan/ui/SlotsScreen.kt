package com.safescan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import com.safescan.scanner.ScannerViewModel
import com.safescan.R

@Composable
fun SlotsScreen(
    viewModel: ScannerViewModel,
    onCaptureClick: () -> Unit,
    onAutoScanClick: () -> Unit,
    onClose: () -> Unit,
    onFlashToggle: () -> Unit,
    onGalleryClick: () -> Unit,
    onSlotClick: (String) -> Unit,
    onSlotLongClick: (String) -> Unit
) {
    val currentMode by viewModel.currentMode.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val autoCrop by viewModel.autoCrop.collectAsState()
    val flashOn by viewModel.flashOn.collectAsState()
    val doubleFocus by viewModel.doubleFocusEnabled.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val showGrid by viewModel.showGrid.collectAsState()
    val clickSound by viewModel.clickSound.collectAsState()
    val liveDetect by viewModel.liveDetect.collectAsState()
    val shadowRemove by viewModel.shadowRemove.collectAsState()
    val batterySaver by viewModel.batterySaver.collectAsState()
    val batchScan by viewModel.batchScan.collectAsState()
    val autoRotation by viewModel.autoRotation.collectAsState()
    val usePhoneCamera by viewModel.usePhoneCamera.collectAsState()
    val hdMode by viewModel.hdMode.collectAsState()

    var isSettingsPopoverOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        // LAYER 1: Viewfinder Overlay Guides based on Selected Mood
        ViewfinderOverlay(mode = currentMode, showGrid = showGrid, modifier = Modifier.fillMaxSize())

        // NEW LAYER: Live Detection Visualization
        val livePoints by viewModel.liveDetectionPoints.collectAsState()
        val liveResolution by viewModel.liveDetectionResolution.collectAsState()
        if (liveDetect && livePoints != null && liveResolution != null) {
            LiveDetectionOverlay(
                points = livePoints!!, 
                resolution = liveResolution!!,
                modifier = Modifier.fillMaxSize()
            )
        }

        // LAYER 2: Control Panel and Overlays
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // A. TOP BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Close Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Close Scanner", tint = Color.White)
                }

                // Center: Flash & Auto Crop Toggles
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.toggleAutoCrop(!autoCrop) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (autoCrop) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (autoCrop) "Auto Crop ON" else "Auto Crop OFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }

                    IconButton(
                        onClick = onFlashToggle,
                        modifier = Modifier.background(
                            if (flashOn) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f),
                            CircleShape
                        )
                    ) {
                        Text(
                            text = if (flashOn) "⚡" else "💡",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                // Right: Settings Menu Button
                IconButton(
                    onClick = { isSettingsPopoverOpen = !isSettingsPopoverOpen },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }

            // B. CENTER INSTRUCTIONS OVERLAY
            val guideText = when (currentMode) {
                ScannerMode.CARD -> "Align Card Inside Cutout"
                ScannerMode.DOCUMENT -> "Align Document Inside Frame"
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

            // C. BOTTOM AREA: Floating Carousel & Premium Control Hub
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // I. Horizontal Slots Carousel Card List
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

                Spacer(modifier = Modifier.height(8.dp))

                // II. Selector Segmented Tab bar for modes ("Paper", "Card", "Grid")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Row(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                            .padding(4.dp)
                    ) {
                        listOf(
                            ScannerMode.DOCUMENT to "Paper",
                            ScannerMode.CARD to "Card",
                            ScannerMode.GRID to "Grid"
                        ).forEach { (mode, label) ->
                            val selected = currentMode == mode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable { viewModel.switchMode(mode) }
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) Color.White else Color.LightGray,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // III. Premium Camera Action Trigger buttons row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Action: Fallback Import Gallery Picker
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(
                            onClick = onGalleryClick,
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Text(text = "🖼️", fontSize = 22.sp)
                        }

                        IconButton(
                            onClick = onAutoScanClick,
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Text(text = "✨", fontSize = 22.sp)
                        }
                    }

                    // Center Action: Large Circular Shutter button
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .background(Color.Transparent, CircleShape)
                            .clickable { onCaptureClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.White, CircleShape)
                        )
                    }

                    // Right Action: Done Button (Saves and generates PDF)
                    val isBatchActive by viewModel.batchScan.collectAsState()
                    val scannedCount = if (viewModel.capturedJpgFiles.isNotEmpty()) viewModel.capturedJpgFiles.size else slots.count { it.bitmap != null }
                    val hasScans = scannedCount > 0
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                if (hasScans) MaterialTheme.colorScheme.primary 
                                else if (isBatchActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                else Color.Black.copy(alpha = 0.5f)
                            )
                            .clickable {
                                if (hasScans) {
                                    viewModel.isGridViewVisible.value = true
                                } else {
                                    viewModel.toggleBatchScan(!isBatchActive)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (hasScans) {
                                Text(text = "✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(text = "($scannedCount)", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            } else {
                                Text(
                                    text = if (isBatchActive) "Batch\nON" else "Batch\nOFF",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 12.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isSettingsPopoverOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        isSettingsPopoverOpen = false
                    }
            )
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp, end = 16.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Card(
                    modifier = Modifier
                        .width(280.dp)
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { /* Consume click events to prevent dismiss */ },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xF21C1C1E)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Camera Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            IconButton(
                                onClick = {
                                    isSettingsPopoverOpen = false
                                    viewModel.isSettingsOpen.value = true
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "More Settings",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.1f))
                        )

                        Column(
                            modifier = Modifier
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            PopoverToggleRow(
                                icon = "🌐",
                                label = "Grid Lines",
                                checked = showGrid,
                                onCheckedChange = { viewModel.toggleShowGrid(it) }
                            )
                            PopoverToggleRow(
                                icon = "🔊",
                                label = "Shutter Sound",
                                checked = clickSound,
                                onCheckedChange = { viewModel.toggleClickSound(it) }
                            )
                            PopoverToggleRow(
                                icon = "✂️",
                                label = "Auto Crop",
                                checked = autoCrop,
                                onCheckedChange = { viewModel.toggleAutoCrop(it) }
                            )
                            PopoverToggleRow(
                                icon = "🔍",
                                label = "Live Detect",
                                checked = liveDetect,
                                onCheckedChange = { viewModel.toggleLiveDetect(it) }
                            )
                            PopoverToggleRow(
                                icon = "☀️",
                                label = "Shadow Remove",
                                checked = shadowRemove,
                                onCheckedChange = { viewModel.toggleShadowRemove(it) }
                            )
                            PopoverToggleRow(
                                icon = "🎯",
                                label = "Double Focus",
                                checked = doubleFocus,
                                onCheckedChange = { viewModel.toggleDoubleFocus(it) }
                            )
                            PopoverToggleRow(
                                icon = "🔋",
                                label = "Battery Saver",
                                checked = batterySaver,
                                onCheckedChange = { viewModel.toggleBatterySaver(it) }
                            )
                            PopoverToggleRow(
                                icon = "📄",
                                label = "Batch Scan",
                                checked = batchScan,
                                onCheckedChange = { viewModel.toggleBatchScan(it) }
                            )
                            PopoverToggleRow(
                                icon = "🔄",
                                label = "Auto Rotation",
                                checked = autoRotation,
                                onCheckedChange = { viewModel.toggleAutoRotation(it) }
                            )
                            PopoverToggleRow(
                                icon = "📱",
                                label = "Phone Camera",
                                checked = usePhoneCamera,
                                onCheckedChange = { viewModel.toggleUsePhoneCamera(it) }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.1f))
                        )

                        Text(
                            text = "Quality Mode",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf("Fast", "Standard", "High").forEach { mode ->
                                val active = hdMode == mode
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                                        .clickable { viewModel.setHdMode(mode) }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = mode,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (active) Color.White else Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val isGridViewVisible by viewModel.isGridViewVisible.collectAsState()
        if (isGridViewVisible) {
            DocumentGridView(
                viewModel = viewModel,
                onDismiss = { viewModel.isGridViewVisible.value = false }
            )
        }
    }
}

@Composable
private fun PopoverToggleRow(
    icon: String,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = icon, fontSize = 16.sp)
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.LightGray,
                uncheckedTrackColor = Color.DarkGray
            ),
            modifier = Modifier.scale(0.85f)
        )
    }
}

@Composable
fun LiveDetectionOverlay(
    points: List<com.safescan.android.scanner.Point>, 
    resolution: Pair<Int, Int>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val imgWidth = resolution.first.toFloat()
        val imgHeight = resolution.second.toFloat()
        
        val scaleX = canvasWidth / imgWidth
        val scaleY = canvasHeight / imgHeight
        
        if (points.size >= 4) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(points[0].x.toFloat() * scaleX, points[0].y.toFloat() * scaleY)
                for (i in 1 until points.size) {
                    lineTo(points[i].x.toFloat() * scaleX, points[i].y.toFloat() * scaleY)
                }
                close()
            }
            
            drawPath(
                path = path,
                color = Color.Green.copy(alpha = 0.6f),
                style = Stroke(
                    width = 3.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f), 0f)
                )
            )
            
            // Draw corner indicators
            points.forEach { pt ->
                drawCircle(
                    color = Color.Green,
                    radius = 5.dp.toPx(),
                    center = Offset(pt.x.toFloat() * scaleX, pt.y.toFloat() * scaleY)
                )
            }
        }
    }
}

@Composable
fun ViewfinderOverlay(mode: ScannerMode, showGrid: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val rectWidth: Float
        val rectHeight: Float
        var isBookMode = false

        when (mode) {
            ScannerMode.CARD -> {
                // Card aspect ratio: 3:2 (approx. 1.5)
                rectWidth = width * 0.88f
                rectHeight = rectWidth / 1.58f
            }
            ScannerMode.DOCUMENT -> {
                // Document aspect ratio: A4 (approx. 1.41)
                rectWidth = width * 0.92f
                rectHeight = rectWidth * 1.414f
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
        }

        if (mode == ScannerMode.GRID || showGrid) {
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
                            val bitmap = remember(file) {
                                try {
                                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .aspectRatio(0.72f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.DarkGray)
                                    .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .clickable { 
                                        viewModel.openEditorFromBatch(idx)
                                        onDismiss()
                                    }
                            ) {
                                bitmap?.let { b ->
                                    Image(
                                        bitmap = b.asImageBitmap(),
                                        contentDescription = "Page ${idx + 1}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                
                                // Delete/Clear button
                                IconButton(
                                    onClick = {
                                        try {
                                            file.delete()
                                        } catch (e: Exception) {}
                                        capturedJpgs.removeAt(idx)
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
                                    .clickable {
                                        viewModel.openEditorFromBatch(idx)
                                        onDismiss()
                                    }
                            ) {
                                slot.bitmap?.let { bmp ->
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = slot.label,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                
                                // Edit/Crop button
                                IconButton(
                                    onClick = {
                                        onDismiss()
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
                            }
                        }
                    }
                }
            }
        }
    }
}
