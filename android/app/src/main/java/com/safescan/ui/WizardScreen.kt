package com.safescan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
    var saveAs by remember { mutableStateOf(wizardPrefs.saveAs) }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "New Scan Setup",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E1E)
                )
            )
        },
        containerColor = Color(0xFF121212)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // --- Scan Type Section ---
            WizardSectionHeader(title = "Scan Type")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF2C2C2C), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // --- Page & Quality Section ---
            WizardSectionHeader(title = "Page & Quality")
            
            Text(
                text = "Page Size",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
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

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Image Quality",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF2C2C2C), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // --- Correction Section ---
            WizardSectionHeader(title = "Correction")

            Text(
                text = "Warp",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val warps = listOf("Perspective", "Flat Crop Only", "None")
                warps.forEach { warpItem ->
                    val selected = warp == warpItem
                    WizardSelectionButton(
                        text = warpItem,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            warp = warpItem
                            wizardPrefs.warp = warpItem
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Rotation",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Filter",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val filters = listOf("Original", "Magic", "Paper", "B&W", "Color", "Card")
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

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF2C2C2C), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // --- Camera Section ---
            WizardSectionHeader(title = "Camera")

            Text(
                text = "Flash",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Focus Mode",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val focuses = listOf("Continuous", "Single", "Double Tap")
                focuses.forEach { foc ->
                    val selected = focusMode == foc
                    WizardSelectionButton(
                        text = foc,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            focusMode = foc
                            wizardPrefs.focusMode = foc
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF2C2C2C), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // --- Advanced Section ---
            WizardSectionHeader(title = "Advanced")

            WizardCheckboxItem(
                text = "Live Edge Detection",
                checked = liveEdge,
                onCheckedChange = {
                    liveEdge = it
                    wizardPrefs.liveEdge = it
                }
            )
            WizardCheckboxItem(
                text = "Auto Capture",
                checked = autoCapture,
                onCheckedChange = {
                    autoCapture = it
                    wizardPrefs.autoCapture = it
                }
            )
            WizardCheckboxItem(
                text = "Auto Crop",
                checked = autoCrop,
                onCheckedChange = {
                    autoCrop = it
                    wizardPrefs.autoCrop = it
                }
            )
            WizardCheckboxItem(
                text = "Auto Shadow Removal",
                checked = autoShadow,
                onCheckedChange = {
                    autoShadow = it
                    wizardPrefs.autoShadow = it
                }
            )
            WizardCheckboxItem(
                text = "Manual Crop",
                checked = manualCrop,
                onCheckedChange = {
                    manualCrop = it
                    wizardPrefs.manualCrop = it
                }
            )
            WizardCheckboxItem(
                text = "Batch Mode",
                checked = batchMode,
                onCheckedChange = {
                    batchMode = it
                    wizardPrefs.batchMode = it
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Save As",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val saveFormats = listOf("PDF", "JPG")
                saveFormats.forEach { fmt ->
                    val selected = saveAs == fmt
                    WizardSelectionButton(
                        text = fmt,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            saveAs = fmt
                            wizardPrefs.saveAs = fmt
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- Start Scanning Button ---
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
                        repo.setDefaultFilter(filter.lowercase())
                        repo.setShadowRemove(autoShadow)

                                                // 5. Camera & Flash
                        val flashObj = when (flash) {
                            "Auto" -> FlashMode.AUTO
                            "On" -> FlashMode.TORCH
                            "Torch" -> FlashMode.TORCH
                            else -> FlashMode.OFF
                        }
                        repo.setFlashMode(flashObj)
                        repo.setFlashOn(flash == "On" || flash == "Torch")

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

                        // 10. Save formats
                        repo.setSaveJpg(saveAs == "JPG" || saveAs == "PDF")

                        // 11. Run scan
                        onStartScan()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Start Scanning",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun WizardSectionHeader(title: String) {
    Text(
        text = "▼ $title",
        style = MaterialTheme.typography.titleSmall.copy(
            color = Color.White,
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
            .background(if (selected) MaterialTheme.colorScheme.primary else Color(0xFF1E1E1E))
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color(0xFFBBBBBB),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
fun WizardCheckboxItem(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = Color.Gray,
                checkmarkColor = Color.White
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp
        )
    }
}
