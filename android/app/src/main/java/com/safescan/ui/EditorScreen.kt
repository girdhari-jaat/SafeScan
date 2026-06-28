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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: ScannerViewModel) {
    val editorState by viewModel.editorState.collectAsState()
    val editingBitmap by viewModel.editingBitmapPreview.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.edit_image)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeEditor(save = false) }) {
                        Icon(Icons.Default.ArrowBack, stringResource(id = R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.closeEditor(save = true) }) {
                        Icon(Icons.Default.Check, stringResource(id = R.string.save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
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

            Spacer(modifier = Modifier.height(16.dp))

            val recognizedText by viewModel.recognizedText.collectAsState()
            val isOcrRunning by viewModel.isOcrRunning.collectAsState()
            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
            val context = androidx.compose.ui.platform.LocalContext.current

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.applyAutoEnhance() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(id = R.string.auto_enhance))
                }

                Button(
                    onClick = { viewModel.runOcrOnCurrentBitmap() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    enabled = !isOcrRunning
                ) {
                    if (isOcrRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Recognize Text")
                    }
                }
            }

            // Display OCR text results if available
            recognizedText?.let { text ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recognized Text (ML Kit OCR)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                                        android.widget.Toast.makeText(context, "Text copied", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Copy", fontSize = 12.sp)
                                }
                                TextButton(
                                    onClick = {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "Share Text"))
                                    }
                                ) {
                                    Text("Share", fontSize = 12.sp)
                                }
                            }
                        }
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = text.ifEmpty { "No text recognized." },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilterType.values().forEach { filterType ->
                    FilterChip(
                        selected = editorState.filter == filterType,
                        onClick = { viewModel.updateEditorState(editorState.copy(filter = filterType)) },
                        label = { Text(filterType.name) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(id = R.string.brightness) + ": ${editorState.brightness.toInt()}")
                Slider(
                    value = editorState.brightness,
                    onValueChange = { viewModel.updateEditorState(editorState.copy(brightness = it)) },
                    valueRange = -100f..100f
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(stringResource(id = R.string.contrast) + ": ${String.format(Locale.US, "%.1f", editorState.contrast)}")
                Slider(
                    value = editorState.contrast,
                    onValueChange = { viewModel.updateEditorState(editorState.copy(contrast = it)) },
                    valueRange = 0.5f..3.0f
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(stringResource(id = R.string.sharpness) + ": ${editorState.sharpness.toInt()}")
                Slider(
                    value = editorState.sharpness,
                    onValueChange = { viewModel.updateEditorState(editorState.copy(sharpness = it)) },
                    valueRange = 0f..10f
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
