package com.safescan.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.safescan.scanner.ScannerViewModel
import com.safescan.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ScannerViewModel,
    onBack: () -> Unit
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    
    // IMPROVEMENT: Localized tab labels
    val tabs = listOf(
        stringResource(id = R.string.scan),
        stringResource(id = R.string.export),
        stringResource(id = R.string.about)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(id = R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                when (selectedTabIndex) {
                    0 -> ScanTab(viewModel)
                    1 -> ExportTab(viewModel)
                    2 -> AboutTab()
                }
            }
        }
    }
}

@Composable
fun ScanTab(viewModel: ScannerViewModel) {
    val dpi by viewModel.dpi.collectAsState()
    val jpegQuality by viewModel.jpegQuality.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(stringResource(id = R.string.resolution_dpi) + ": ${dpi.toInt()}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = dpi,
            onValueChange = { viewModel.setDpi(it) },
            valueRange = 72f..600f,
            steps = 528
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(id = R.string.jpeg_quality) + ": ${jpegQuality.toInt()}%", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = jpegQuality,
            onValueChange = { viewModel.setJpegQuality(it) },
            valueRange = 10f..100f,
            steps = 90
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Save JPG", style = MaterialTheme.typography.titleMedium)
            val saveJpg by viewModel.saveJpg.collectAsState()
            Switch(
                checked = saveJpg,
                onCheckedChange = { viewModel.toggleSaveJpg(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto PDF", style = MaterialTheme.typography.titleMedium)
            val autoPdf by viewModel.autoPdf.collectAsState()
            Switch(
                checked = autoPdf,
                onCheckedChange = { viewModel.toggleAutoPdf(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Batch Scan", style = MaterialTheme.typography.titleMedium)
            val batchScan by viewModel.batchScan.collectAsState()
            Switch(
                checked = batchScan,
                onCheckedChange = { viewModel.toggleBatchScan(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Show Grid Lines", style = MaterialTheme.typography.titleMedium)
            val showGrid by viewModel.showGrid.collectAsState()
            Switch(
                checked = showGrid,
                onCheckedChange = { viewModel.toggleShowGrid(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Click Sound", style = MaterialTheme.typography.titleMedium)
            val clickSound by viewModel.clickSound.collectAsState()
            Switch(
                checked = clickSound,
                onCheckedChange = { viewModel.toggleClickSound(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Double Focus Lock", style = MaterialTheme.typography.titleMedium)
            val doubleFocus by viewModel.doubleFocusEnabled.collectAsState()
            Switch(
                checked = doubleFocus,
                onCheckedChange = { viewModel.toggleDoubleFocus(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto Crop Pages", style = MaterialTheme.typography.titleMedium)
            val autoCrop by viewModel.autoCrop.collectAsState()
            Switch(
                checked = autoCrop,
                onCheckedChange = { viewModel.toggleAutoCrop(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto Orientation", style = MaterialTheme.typography.titleMedium)
            val autoOrientation by viewModel.autoOrientation.collectAsState()
            Switch(
                checked = autoOrientation,
                onCheckedChange = { viewModel.toggleAutoOrientation(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Remove Shadows", style = MaterialTheme.typography.titleMedium)
            val shadowRemove by viewModel.shadowRemove.collectAsState()
            Switch(
                checked = shadowRemove,
                onCheckedChange = { viewModel.toggleShadowRemove(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto Rotation", style = MaterialTheme.typography.titleMedium)
            val autoRotation by viewModel.autoRotation.collectAsState()
            Switch(
                checked = autoRotation,
                onCheckedChange = { viewModel.toggleAutoRotation(it) }
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportTab(viewModel: ScannerViewModel) {
    val pdfFilename by viewModel.pdfFilename.collectAsState()
    val pageSize by viewModel.pageSize.collectAsState()
    val defaultFilter by viewModel.defaultFilter.collectAsState()
    val uiLanguage by viewModel.uiLanguage.collectAsState()
    
    var sizeExpanded by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    var langExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        OutlinedTextField(
            value = pdfFilename,
            onValueChange = { viewModel.setPdfFilename(it) },
            label = { Text(stringResource(id = R.string.pdf_filename)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Default Page Size", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = sizeExpanded,
            onExpandedChange = { sizeExpanded = !sizeExpanded }
        ) {
            OutlinedTextField(
                value = pageSize,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(id = R.string.page_size)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sizeExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = sizeExpanded,
                onDismissRequest = { sizeExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("A4") },
                    onClick = {
                        viewModel.setPageSize("A4")
                        sizeExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Letter") },
                    onClick = {
                        viewModel.setPageSize("Letter")
                        sizeExpanded = false
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("Default Processing Filter", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = filterExpanded,
            onExpandedChange = { filterExpanded = !filterExpanded }
        ) {
            OutlinedTextField(
                value = defaultFilter.replaceFirstChar { it.uppercase() },
                onValueChange = {},
                readOnly = true,
                label = { Text("Default Filter") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = filterExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = filterExpanded,
                onDismissRequest = { filterExpanded = false }
            ) {
                val filters = listOf("original", "magic", "grayscale", "threshold")
                filters.forEach { filter ->
                    DropdownMenuItem(
                        text = { Text(filter.replaceFirstChar { it.uppercase() }) },
                        onClick = {
                            viewModel.setDefaultFilter(filter)
                            filterExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("UI Language Selection", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = langExpanded,
            onExpandedChange = { langExpanded = !langExpanded }
        ) {
            val languageLabel = when(uiLanguage) {
                "ur" -> "Urdu (اردو)"
                "sd" -> "Sindhi (سنڌي)"
                else -> "English"
            }
            OutlinedTextField(
                value = languageLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("App Language") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = langExpanded,
                onDismissRequest = { langExpanded = false }
            ) {
                val languages = listOf("en" to "English", "ur" to "Urdu (اردو)", "sd" to "Sindhi (سنڌي)")
                languages.forEach { (code, label) ->
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
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun AboutTab() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(id = R.string.version_1_0),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.offline_desc),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = stringResource(id = R.string.no_data_desc),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
