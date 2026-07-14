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
import androidx.compose.material.icons.filled.*
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
import coil.compose.AsyncImage
import androidx.compose.animation.core.*

@Composable
fun SlotsScreen(
    viewModel: ScannerViewModel,
    onCaptureClick: () -> Unit,
    onClose: () -> Unit,
    onFlashToggle: () -> Unit,
    onGalleryClick: () -> Unit,
    onSlotClick: (String) -> Unit,
    onSlotLongClick: (String) -> Unit
) {
    val currentMode by viewModel.currentMode.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val autoCrop by viewModel.autoCrop.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val doubleFocus by viewModel.doubleFocusEnabled.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val autoCapture by viewModel.autoCapture.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val showGrid by viewModel.showGrid.collectAsState()
    val clickSound by viewModel.clickSound.collectAsState()
    val liveDetect by viewModel.liveDetect.collectAsState()
    val shadowRemove by viewModel.shadowRemove.collectAsState()
    val batterySaver by viewModel.batterySaver.collectAsState()
    val batchScan by viewModel.batchScan.collectAsState()
    val autoRotation by viewModel.autoRotation.collectAsState()
    val usePhoneCamera by viewModel.usePhoneCamera.collectAsState()
    val useNativeScanner by viewModel.useNativeScanner.collectAsState()
    val hdMode by viewModel.hdMode.collectAsState()
    val isDocumentDetected by viewModel.isDocumentDetected.collectAsState()

    var isSettingsPopoverOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        // LAYER 1: Viewfinder Overlay Guides based on Selected Mood
        ViewfinderOverlay(mode = currentMode, showGrid = showGrid, modifier = Modifier.fillMaxSize())

        // LAYER 2: Control Panel and Overlays
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
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

                // Center: Flash Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onFlashToggle,
                        modifier = Modifier.background(
                            if (flashMode != com.safescan.data.FlashMode.OFF) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f),
                            CircleShape
                        )
                    ) {
                        Icon(
                            imageVector = when(flashMode) { com.safescan.data.FlashMode.AUTO -> Icons.Default.FlashAuto; com.safescan.data.FlashMode.ON -> Icons.Default.FlashOn; com.safescan.data.FlashMode.TORCH -> Icons.Default.FlashOn; else -> Icons.Default.FlashOff },
                            contentDescription = "Toggle Flash",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
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
            val guideText = if (isDocumentDetected) {
                if (autoCapture) "HOLD STILL... AUTO-CAPTURING" else "READY TO CAPTURE"
            } else {
                when (currentMode) {
                    ScannerMode.CARD -> "Align Card Inside Cutout"
                    ScannerMode.DOCUMENT -> "Align Document Inside Frame"
                    ScannerMode.GRID -> "Utilize Grid for Centered Alignment"
                }
            }
            val guideColor = if (isDocumentDetected) Color(0xFF10B981) else Color.Yellow

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (isDocumentDetected) {
                        val pulseTransition = rememberInfiniteTransition(label = "dot_pulse")
                        val dotAlpha by pulseTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dot_alpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = dotAlpha))
                        )
                    }
                    Text(
                        text = guideText,
                        color = guideColor,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            // C. BOTTOM AREA: Floating Carousel & Premium Control Hub
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // I. Horizontal Slots Carousel Card List
                if (currentMode != ScannerMode.DOCUMENT) {
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
                }

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
                    IconButton(
                        onClick = onGalleryClick,
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "Import from Gallery", tint = Color.White)
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
                        if (hasScans && uiState.lastCapturedThumbnail != null) {
                            Image(
                                bitmap = uiState.lastCapturedThumbnail!!.asImageBitmap(),
                                contentDescription = "Last captured thumbnail",
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            // Overlay count
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$scannedCount",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        } else {
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
                                icon = Icons.Default.Grid4x4,
                                label = "Grid Lines",
                                checked = showGrid,
                                onCheckedChange = { viewModel.toggleShowGrid(it) }
                            )
                            PopoverToggleRow(
                                icon = Icons.Default.VolumeUp,
                                label = "Shutter Sound",
                                checked = clickSound,
                                onCheckedChange = { viewModel.toggleClickSound(it) }
                            )
                            PopoverToggleRow(
                                icon = Icons.Default.DocumentScanner,
                                label = "Live Detect",
                                checked = liveDetect,
                                onCheckedChange = { viewModel.toggleLiveDetect(it) }
                            )
                            PopoverToggleRow(
                                icon = Icons.Default.CameraAlt,
                                label = "Auto Capture",
                                checked = autoCapture,
                                onCheckedChange = { viewModel.toggleAutoCapture() }
                            )
                            PopoverToggleRow(
                                icon = Icons.Default.AutoFixHigh,
                                label = "Auto Crop",
                                checked = autoCrop,
                                onCheckedChange = { viewModel.toggleAutoCrop(it) }
                            )
                            PopoverToggleRow(
                                icon = Icons.Default.BrightnessMedium,
                                label = "Shadow Remove",
                                checked = shadowRemove,
                                onCheckedChange = { viewModel.toggleShadowRemove(it) }
                            )
                            PopoverToggleRow(
                                icon = Icons.Default.CenterFocusStrong,
                                label = "Double Focus",
                                checked = doubleFocus,
                                onCheckedChange = { viewModel.toggleDoubleFocus(it) }
                            )
                            PopoverToggleRow(
                                icon = Icons.Default.BatteryChargingFull,
                                label = "Battery Saver",
                                checked = batterySaver,
                                onCheckedChange = { viewModel.toggleBatterySaver(it) }
                            )
                            PopoverToggleRow(
                                icon = Icons.Default.Layers,
                                label = "Batch Scan",
                                checked = batchScan,
                                onCheckedChange = { viewModel.toggleBatchScan(it) }
                            )
                            PopoverToggleRow(
                                icon = Icons.Default.ScreenRotation,
                                label = "Auto Rotation",
                                checked = autoRotation,
                                onCheckedChange = { viewModel.toggleAutoRotation(it) }
                            )
                            PopoverToggleRow(
                                icon = Icons.Default.DocumentScanner,
                                label = "Native Scanner",
                                checked = useNativeScanner,
                                onCheckedChange = { viewModel.toggleUseNativeScanner(it) }
                            )
                            PopoverToggleRow(
                                icon = Icons.Default.CameraAlt,
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
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
fun ViewfinderOverlay(mode: ScannerMode, showGrid: Boolean, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isA4 = remember(context) { com.safescan.scanner.CameraHardwareConfig.isA4Supported(context) }
    val isCnic = remember(context) { com.safescan.scanner.CameraHardwareConfig.isCnicSupported(context) }

    val infiniteTransition = rememberInfiniteTransition(label = "viewfinder_pulse")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "border_pulse"
    )
    val laserYRatio by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_y"
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val finalRatio = com.safescan.utils.PageConfig.getOnscreenLayoutRatio(context, mode)

        // Limit the cutout bounds to prevent overflowing the preview viewport
        val maxWidth = width * 0.90f
        val maxHeight = height * 0.85f

        var rectWidth = maxWidth
        var rectHeight = rectWidth / finalRatio

        if (rectHeight > maxHeight) {
            rectHeight = maxHeight
            rectWidth = rectHeight * finalRatio
        }

        if (rectWidth > 0f && rectHeight > 0f) {
            val left = (width - rectWidth) / 2f
            val top = (height - rectHeight) / 2f

            // 1. Draw outer darkened scrim rectangles
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(0f, 0f),
                size = Size(width, maxOf(0f, top))
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(0f, top + rectHeight),
                size = Size(width, maxOf(0f, height - (top + rectHeight)))
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(0f, maxOf(0f, top)),
                size = Size(left, rectHeight)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(left + rectWidth, maxOf(0f, top)),
                size = Size(width - (left + rectWidth), rectHeight)
            )

            // 2. Draw high-contrast target outline with pulsing glow
            drawRoundRect(
                color = Color.White.copy(alpha = borderAlpha),
                topLeft = Offset(left, top),
                size = Size(rectWidth, rectHeight),
                cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                style = Stroke(width = 2.5.dp.toPx())
            )

            // Draw a high-tech glowing laser scanning line inside the cutout
            val laserY = top + rectHeight * laserYRatio
            drawLine(
                color = Color(0xFF10B981).copy(alpha = 0.8f),
                start = Offset(left + 8.dp.toPx(), laserY),
                end = Offset(left + rectWidth - 8.dp.toPx(), laserY),
                strokeWidth = 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
            )

            // 3. Draw grid if requested
            if (showGrid || mode == ScannerMode.GRID) {
                // Draw standard 3x3 alignment grids inside the container
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left + rectWidth / 3f, top),
                    end = Offset(left + rectWidth / 3f, top + rectHeight),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left + rectWidth * 2f / 3f, top),
                    end = Offset(left + rectWidth * 2f / 3f, top + rectHeight),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left, top + rectHeight / 3f),
                    end = Offset(left + rectWidth, top + rectHeight / 3f),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left, top + rectHeight * 2f / 3f),
                    end = Offset(left + rectWidth, top + rectHeight * 2f / 3f),
                    strokeWidth = 1.dp.toPx()
                )
            }
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
                onClick = { 
                    if (slot.bitmap == null) onClick() else onLongClick()
                },
                onLongClick = { if (slot.bitmap != null) onLongClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (slot.bitmap != null) {
            AsyncImage(
                model = slot.bitmapPath ?: slot.bitmap,
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
