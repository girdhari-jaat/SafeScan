package com.safescan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safescan.scanner.ScannerViewModel
import com.safescan.utils.WizardPrefs
import com.safescan.data.ScannerMode
import com.safescan.data.FlashMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(
    viewModel: ScannerViewModel,
    onStartScan: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wizardPrefs = remember { WizardPrefs(context) }

    // Persistent state variables synced from SharedPreferences
    var scanType by remember { mutableStateOf(wizardPrefs.scanType) }
    var pageSize by remember { mutableStateOf(wizardPrefs.pageSize) }
    var imageQuality by remember { mutableStateOf(wizardPrefs.imageQuality) }
    var warp by remember { mutableStateOf(wizardPrefs.warp) }
    var rotation by remember { mutableStateOf(wizardPrefs.rotation) }
    var filter by remember { mutableStateOf(wizardPrefs.filter) }
    var flash by remember { mutableStateOf(wizardPrefs.flash) }
    var focusMode by remember { mutableStateOf(wizardPrefs.focusMode) }
    var liveEdge by remember { mutableStateOf(wizardPrefs.liveEdge) }
    var autoCapture by remember { mutableStateOf(wizardPrefs.autoCapture) }
    var autoCrop by remember { mutableStateOf(wizardPrefs.autoCrop) }
    var autoShadow by remember { mutableStateOf(wizardPrefs.autoShadow) }
    var manualCrop by remember { mutableStateOf(wizardPrefs.manualCrop) }
    var batchMode by remember { mutableStateOf(wizardPrefs.batchMode) }
    var dontShowAgain by remember { mutableStateOf(wizardPrefs.dontShowAgain) }

    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val types = listOf("Document", "Card", "Grid")
                types.forEach { type ->
                    val selected = scanType == type
                    WizardSelectionButton(
                        text = type,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scanType = type
                            wizardPrefs.scanType = type
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- Page Size ---
            Text(
                text = "Page Size",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val pageSizes = com.safescan.utils.PageConfig.ALL_PAGE_SIZES
                pageSizes.forEach { size ->
                    val selected = pageSize == size
                    WizardSelectionButton(
                        text = size,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            pageSize = size
                            wizardPrefs.pageSize = size
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // --- Image Quality ---
            Text(
                text = "Image Quality",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val qualities = listOf("Fast", "Standard", "High")
                qualities.forEach { quality ->
                    val selected = imageQuality == quality
                    WizardSelectionButton(
                        text = quality,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            imageQuality = quality
                            wizardPrefs.imageQuality = quality
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- Warp Correction ---
            Text(
                text = "Warp Correction",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val warps = listOf("Perspective", "Flat", "None")
                warps.forEach { warpItem ->
                    val selected = if (warpItem == "Flat") warp == "Flat Crop Only" else warp == warpItem
                    WizardSelectionButton(
                        text = warpItem,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val actualWarpValue = if (warpItem == "Flat") "Flat Crop Only" else warpItem
                            warp = actualWarpValue
                            wizardPrefs.warp = actualWarpValue
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // --- Rotation ---
            Text(
                text = "Rotation",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val rotations = listOf("Auto", "0°", "90°", "180°")
                rotations.forEach { rot ->
                    val selected = rotation == rot
                    WizardSelectionButton(
                        text = rot,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            rotation = rot
                            wizardPrefs.rotation = rot
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // --- Filter Selector (Full Width but compact) ---
            Text(
                text = "Processing Filter",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val filters = listOf("Original", "Magic", "Paper", "Card", "B&W", "Gray")
                filters.forEach { f ->
                    val selected = filter.equals(f, ignoreCase = true)
                    WizardSelectionButton(
                        text = f,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            filter = f
                            wizardPrefs.filter = f
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // --- Flash Mode ---
            Text(
                text = "Flash Mode",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val flashes = listOf("Off", "Auto", "On", "Torch")
                flashes.forEach { fl ->
                    val selected = flash == fl
                    WizardSelectionButton(
                        text = fl,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            flash = fl
                            wizardPrefs.flash = fl
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // --- Focus Mode ---
            Text(
                text = "Focus Mode",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val focuses = listOf("Continuous", "Single", "Double")
                focuses.forEach { foc ->
                    val selected = if (foc == "Double") focusMode == "Double Tap" else focusMode == foc
                    WizardSelectionButton(
                        text = foc,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val actualFocusValue = if (foc == "Double") "Double Tap" else foc
                            focusMode = actualFocusValue
                            wizardPrefs.focusMode = actualFocusValue
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --- Advanced Preferences Checkboxes (2 Columns) ---
            Text(
                text = "Advanced Preferences",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                WizardSelectionButton(
                    text = "Live Edge",
                    selected = liveEdge,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        liveEdge = !liveEdge
                        wizardPrefs.liveEdge = liveEdge
                    }
                )
                WizardSelectionButton(
                    text = "Auto Capture",
                    selected = autoCapture,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        autoCapture = !autoCapture
                        wizardPrefs.autoCapture = autoCapture
                    }
                )
                WizardSelectionButton(
                    text = "Auto Crop",
                    selected = autoCrop,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        autoCrop = !autoCrop
                        wizardPrefs.autoCrop = autoCrop
                    }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                WizardSelectionButton(
                    text = "Shadow Remove",
                    selected = autoShadow,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        autoShadow = !autoShadow
                        wizardPrefs.autoShadow = autoShadow
                    }
                )
                WizardSelectionButton(
                    text = "Manual Crop",
                    selected = manualCrop,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        manualCrop = !manualCrop
                        wizardPrefs.manualCrop = manualCrop
                    }
                )
                WizardSelectionButton(
                    text = "Batch Mode",
                    selected = batchMode,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        batchMode = !batchMode
                        wizardPrefs.batchMode = batchMode
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Bottom Row: [✓] Don't Show Again  [▶ Start Scan] ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Don't Show Again Checkbox Row
                Row(
                    modifier = Modifier
                        .weight(1.1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            dontShowAgain = !dontShowAgain
                            wizardPrefs.dontShowAgain = dontShowAgain
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = {
                            dontShowAgain = it
                            wizardPrefs.dontShowAgain = it
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = Color.Gray,
                            checkmarkColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Don't Show Again",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Start Scanning Button
                Button(
                    onClick = {
                        // Sync values to settingsRepository / ScannerViewModel before starting scan
                        scope.launch {
                            val repo = viewModel.settingsRepository

                            // 1. Scan Type (ScannerMode)
                            val modeObj = when (scanType) {
                                "Card" -> ScannerMode.CARD
                                "Grid" -> ScannerMode.GRID
                                else -> ScannerMode.DOCUMENT
                            }
                            repo.setScannerMode(modeObj)

                            // 2. Page Size
                            repo.setPageSize(pageSize)

                            // 3. HD Quality/Quality Mode
                            repo.setHdMode(imageQuality)

                            // 4. Filters & Shadow Removal
                            repo.setDefaultFilter(filter)
                            repo.setShadowRemove(autoShadow)

                            // 5. Camera & Flash
                            val flashObj = when (flash) {
                                "Auto" -> FlashMode.AUTO
                                "On" -> FlashMode.ON
                                "Torch" -> FlashMode.TORCH
                                else -> FlashMode.OFF
                            }
                            repo.setFlashMode(flashObj)
                            repo.setFlashOn(flash == "Torch")

                            // 6. Focus Mode
                            repo.setDoubleFocus(focusMode == "Double Tap")

                            // 7. Live Edge Detection & Auto Capture
                            repo.setLiveDetect(liveEdge)
                            
                            // Auto-capture should only toggle settings toggle
                            if (autoCapture != viewModel.autoCapture.value) {
                                repo.toggleAutoCapture()
                            }

                            // 8. Auto Crop & Manual Crop
                            repo.setAutoCrop(autoCrop)

                            // 9. Batch Mode
                            repo.setBatchScan(batchMode)

                            // 10. Run scan
                            onStartScan()
                        }
                    },
                    modifier = Modifier
                        .weight(0.9f)
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Start Scan",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun WizardSectionHeader(title: String) {
    Text(
        text = "▼ $title",
        style = MaterialTheme.typography.titleSmall.copy(
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        ),
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
fun WizardSelectionButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary 
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary 
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) MaterialTheme.colorScheme.onPrimary 
                    else MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
