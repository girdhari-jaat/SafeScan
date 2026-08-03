package com.safescan.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safescan.scanner.ScannerViewModel
import com.safescan.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ScannerViewModel,
    onBack: () -> Unit
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabTitles = listOf("Scanning", "Processing", "Export", "System")
    val tabIcons = listOf(
        Icons.Default.CameraAlt,
        Icons.Default.Tune,
        Icons.Default.Save,
        Icons.Default.Settings
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    title = { 
                        Text(
                            stringResource(id = R.string.settings),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            icon = { Icon(tabIcons[index], contentDescription = title) },
                            text = { Text(title, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (selectedTabIndex) {
                0 -> {
                    item {
                        SettingsSection(title = "Scanning") {
                            val showGrid by viewModel.showGrid.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.Grid4x4,
                                title = "Show Grid Lines",
                                description = "Display helpful grid lines on camera view",
                                checked = showGrid,
                                onCheckedChange = { viewModel.toggleShowGrid(it) }
                            )

                            val batchScan by viewModel.batchScan.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.Layers,
                                title = "Batch Scan",
                                description = "Capture multiple pages in one go",
                                checked = batchScan,
                                onCheckedChange = { viewModel.toggleBatchScan(it) }
                            )

                            val autoCapture by viewModel.autoCapture.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.CameraAlt,
                                title = "Auto Capture",
                                description = "Automatically capture document when stable",
                                checked = autoCapture,
                                onCheckedChange = { viewModel.toggleAutoCapture(it) }
                            )
                            
                            val liveDetect by viewModel.liveDetect.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.RemoveRedEye,
                                title = "Live Edge Detection",
                                description = "Real-time document boundary highlighting",
                                checked = liveDetect,
                                onCheckedChange = { viewModel.toggleLiveDetect(it) }
                            )

                            val autoCrop by viewModel.autoCrop.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.CropFree,
                                title = "Auto Crop",
                                description = "Automatically detect and crop document edges",
                                checked = autoCrop,
                                onCheckedChange = { viewModel.toggleAutoCrop(it) }
                            )

                            val useNativeScanner by viewModel.useNativeScanner.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.DocumentScanner,
                                title = "Native Scanner",
                                description = "Use ML Kit Document Scanner API if available",
                                checked = useNativeScanner,
                                onCheckedChange = { viewModel.toggleUseNativeScanner(it) }
                            )

                            val hdMode by viewModel.hdMode.collectAsState()
                            var hdExpanded by remember { mutableStateOf(false) }
                            SettingsClickItem(
                                icon = Icons.Default.Speed,
                                title = "Capture Quality (HD)",
                                subtitle = hdMode,
                                onClick = { hdExpanded = true }
                            ) {
                                DropdownMenu(
                                    expanded = hdExpanded,
                                    onDismissRequest = { hdExpanded = false }
                                ) {
                                    listOf("Fast", "Standard", "High").forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode) },
                                            onClick = {
                                                viewModel.setHdMode(mode)
                                                hdExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    item {
                        SettingsSection(title = "Processing") {
                            val shadowRemove by viewModel.shadowRemove.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.Brightness6,
                                title = "Shadow Removal",
                                description = "Flatten lighting for cleaner documents",
                                checked = shadowRemove,
                                onCheckedChange = { viewModel.toggleShadowRemove(it) }
                            )

                            val autoRotation by viewModel.autoRotation.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.AutoMirrored.Filled.RotateRight,
                                title = "Auto Rotation",
                                description = "Automatically rotate pages for correct orientation",
                                checked = autoRotation,
                                onCheckedChange = { viewModel.toggleAutoRotation(it) }
                            )

                            val defaultFilter by viewModel.defaultFilter.collectAsState()
                            var filterExpanded by remember { mutableStateOf(false) }
                            SettingsClickItem(
                                icon = Icons.Default.FilterBAndW,
                                title = "Default Filter",
                                subtitle = defaultFilter,
                                onClick = { filterExpanded = true }
                            ) {
                                DropdownMenu(
                                    expanded = filterExpanded,
                                    onDismissRequest = { filterExpanded = false }
                                ) {
                                    val filters = listOf("Original", "Magic", "Card", "Paper", "B&W", "Gray")
                                    filters.forEach { filter ->
                                        DropdownMenuItem(
                                            text = { Text(filter) },
                                            onClick = {
                                                viewModel.setDefaultFilter(filter)
                                                filterExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    item {
                        SettingsSection(title = "Export & Quality") {
                            val dpi by viewModel.dpi.collectAsState()
                            SettingsSliderItem(
                                icon = Icons.Default.HighQuality,
                                title = "Resolution (DPI)",
                                value = dpi.coerceIn(150f, 300f),
                                valueRange = 150f..300f,
                                steps = 2,
                                onValueChange = { viewModel.setDpi(it) }
                            )

                            val jpegQuality by viewModel.jpegQuality.collectAsState()
                            SettingsSliderItem(
                                icon = Icons.Default.PhotoLibrary,
                                title = "JPEG Quality",
                                value = jpegQuality.coerceIn(60f, 100f),
                                valueRange = 60f..100f,
                                steps = 3,
                                onValueChange = { viewModel.setJpegQuality(it) }
                            )

                            val saveJpg by viewModel.saveJpg.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.Image,
                                title = "Save Raw JPG",
                                description = "Save original raw capture to local storage",
                                checked = saveJpg,
                                onCheckedChange = { viewModel.toggleSaveJpg(it) }
                            )

                            val autoPdf by viewModel.autoPdf.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.PictureAsPdf,
                                title = "Auto PDF Compilation",
                                description = "Automatically compile PDF when finishing document",
                                checked = autoPdf,
                                onCheckedChange = { viewModel.toggleAutoPdf(it) }
                            )

                            val pdfFilename by viewModel.pdfFilename.collectAsState()
                            SettingsInputItem(
                                icon = Icons.Default.Description,
                                title = "Default PDF Filename",
                                value = pdfFilename,
                                onValueChange = { viewModel.setPdfFilename(it) }
                            )

                            val pageSize by viewModel.pageSize.collectAsState()
                            var sizeExpanded by remember { mutableStateOf(false) }
                            SettingsClickItem(
                                icon = Icons.Default.AspectRatio,
                                title = "Page Size",
                                subtitle = pageSize,
                                onClick = { sizeExpanded = true }
                            ) {
                                DropdownMenu(
                                    expanded = sizeExpanded,
                                    onDismissRequest = { sizeExpanded = false }
                                ) {
                                    com.safescan.utils.PageConfig.ALL_PAGE_SIZES.forEach { size ->
                                        DropdownMenuItem(
                                            text = { Text(size) },
                                            onClick = {
                                                viewModel.setPageSize(size)
                                                sizeExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> {
                    item {
                        SettingsSection(title = "Preferences") {
                            val uiLanguage by viewModel.uiLanguage.collectAsState()
                            var langExpanded by remember { mutableStateOf(false) }
                            val languageLabel = when(uiLanguage) {
                                "ur" -> "Urdu (اردو)"
                                "sd" -> "Sindhi (سنڌي)"
                                else -> "English"
                            }
                            SettingsClickItem(
                                icon = Icons.Default.Language,
                                title = "App Language",
                                subtitle = languageLabel,
                                onClick = { langExpanded = true }
                            ) {
                                DropdownMenu(
                                    expanded = langExpanded,
                                    onDismissRequest = { langExpanded = false }
                                ) {
                                    listOf("en" to "English", "ur" to "Urdu (اردو)", "sd" to "Sindhi (سنڌي)").forEach { (code, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                viewModel.setUiLanguage(code)
                                                langExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            val clickSound by viewModel.clickSound.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.AutoMirrored.Filled.VolumeUp,
                                title = "Click Sound",
                                description = "Play sound when capturing a document",
                                checked = clickSound,
                                onCheckedChange = { viewModel.toggleClickSound(it) }
                            )

                            val vibrateOnCapture by viewModel.vibrateOnCapture.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.Vibration,
                                title = "Haptic Feedback",
                                description = "Vibrate when capturing a document",
                                checked = vibrateOnCapture,
                                onCheckedChange = { viewModel.setVibrateOnCapture(it) }
                            )

                            val startWithCamera by viewModel.startWithCamera.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.CameraAlt,
                                title = "Start with Camera",
                                description = "Open camera automatically on app launch",
                                checked = startWithCamera,
                                onCheckedChange = { viewModel.toggleStartWithCamera(it) }
                            )

                            val batterySaver by viewModel.batterySaver.collectAsState()
                            SettingsToggleItem(
                                icon = Icons.Default.BatteryChargingFull,
                                title = "Battery Saver",
                                description = "Reduce UI animations and camera frequency",
                                checked = batterySaver,
                                onCheckedChange = { viewModel.toggleBatterySaver(it) }
                            )
                        }
                    }

                    item {
                        DeveloperStatsView(viewModel = viewModel)
                    }

                    item {
                        SettingsSection(title = "About") {
                            SettingsInfoItem(
                                icon = Icons.Default.Info,
                                title = "Safe Scan v1.0",
                                subtitle = "Fully Offline & Secure Document Scanner"
                            )
                            Text(
                                "Crafted with Privacy First principle. No data leaves your device.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingsClickItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        content()
    }
}

@Composable
fun SettingsInputItem(
    icon: ImageVector,
    title: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title, 
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
fun SettingsSliderItem(
    icon: ImageVector,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                value.toInt().toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun SettingsInfoItem(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

