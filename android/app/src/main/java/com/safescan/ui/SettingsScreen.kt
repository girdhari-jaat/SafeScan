package com.safescan.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    Column {
        Text(stringResource(id = R.string.resolution_dpi) + ": ${dpi.toInt()}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = dpi,
            onValueChange = { viewModel.setDpi(it) },
            valueRange = 72f..600f,
            steps = 528
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(id = R.string.jpeg_quality) + ": ${jpegQuality.toInt()}%", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = jpegQuality,
            onValueChange = { viewModel.setJpegQuality(it) },
            valueRange = 10f..100f,
            steps = 90
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportTab(viewModel: ScannerViewModel) {
    val pdfFilename by viewModel.pdfFilename.collectAsState()
    val pageSize by viewModel.pageSize.collectAsState()
    
    var expanded by remember { mutableStateOf(false) }

    Column {
        OutlinedTextField(
            value = pdfFilename,
            onValueChange = { viewModel.setPdfFilename(it) },
            label = { Text(stringResource(id = R.string.pdf_filename)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = pageSize,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(id = R.string.page_size)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("A4") },
                    onClick = {
                        viewModel.setPageSize("A4")
                        expanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Letter") },
                    onClick = {
                        viewModel.setPageSize("Letter")
                        expanded = false
                    }
                )
            }
        }
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
