package com.safescan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safescan.scanner.ScannerViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.safescan.core.DiagnosticsLogger

@Composable
fun DeveloperStatsView(viewModel: ScannerViewModel) {
    val context = LocalContext.current
    val savedDocs by viewModel.savedDocuments.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    
    val logs by DiagnosticsLogger.logs.collectAsState()
    val listState = rememberLazyListState()
    
    var logFilterMode by remember { mutableStateOf("Diagnostics") }
    
    val filteredLogs = remember(logs, logFilterMode) {
        logs.filter { log ->
            val isHighFrequency = log.contains("[LiveEdge]") ||
                    log.contains("[Stability]") ||
                    log.contains("[AutoCap]") ||
                    log.contains("LiveEdgeDetectionEngine.process") ||
                    log.contains("onDocumentDetected")
            
            when (logFilterMode) {
                "Diagnostics" -> !isHighFrequency
                "Debug" -> isHighFrequency
                else -> true
            }
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        if (DiagnosticsLogger.logs.value.isEmpty()) {
            DiagnosticsLogger.info("Diagnostics Dashboard initialized.")
            DiagnosticsLogger.info("Device: ${android.os.Build.MODEL} (SDK ${android.os.Build.VERSION.SDK_INT})")
            DiagnosticsLogger.info("Active Session Storage slots allocated: ${slots.size}")
            DiagnosticsLogger.info("Offline saved documents count: ${savedDocs.size}")
        }
    }
    
    // Calculate stats
    val totalDocs = savedDocs.size
    val totalPages = savedDocs.sumOf { it.pages.size }
    val avgPages = if (totalDocs > 0) totalPages.toFloat() / totalDocs else 0f
    
    // Calculate filter distribution
    val filtersUsed = remember(savedDocs) {
        val map = mutableMapOf<String, Int>()
        savedDocs.forEach { doc ->
            doc.pages.forEach { page ->
                val f = page.filter
                map[f] = (map[f] ?: 0) + 1
            }
        }
        map.toList().sortedByDescending { it.second }
    }
    
    // Expandable stats state
    var isStatsExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // Section: Developer Contact
        Text(
            text = "Developer Profile",
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
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Developer",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Girdhari_Jaat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Lead Android & AI Developer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val whatsappUrl = "https://wa.me/923468925992?text=Hi%20Girdhari,%20I%20have%20a%20question%20about%20SafeScan"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse(whatsappUrl)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open WhatsApp. Contact: +923468925992", Toast.LENGTH_LONG).show()
                            }
                        }
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = "WhatsApp",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "WhatsApp Contact",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "+92 346 8925992",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Go",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Section: System Statistics / Performance
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Diagnostics & Stats",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
            TextButton(onClick = { isStatsExpanded = !isStatsExpanded }) {
                Text(if (isStatsExpanded) "Hide" else "Show Details")
            }
        }
        
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Quick stats summary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            text = totalDocs.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Documents",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            text = totalPages.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Pages Scanned",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            text = slots.size.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Active Session",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                AnimatedVisibility(visible = isStatsExpanded) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "Performance Diagnostics",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        // Active Mode & Session details
                        StatRow("Capture Mode", currentMode.name)
                        StatRow("Average Pages/Doc", String.format("%.1f", avgPages))
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Image Filters Distribution
                        if (filtersUsed.isNotEmpty()) {
                            Text(
                                text = "Image Filters Used",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            filtersUsed.forEach { (filter, count) ->
                                val pct = if (totalPages > 0) count.toFloat() / totalPages else 0f
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(filter, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        Text("${count}x", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { pct },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "No filter statistics available yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Live Console Logger Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live Diagnostic Console",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = {
                                        val logsText = logs.joinToString("\n")
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("SafeScan Logs", logsText)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Logs copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Copy Logs", fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = { DiagnosticsLogger.clear() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Clear", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        // Filter Chips Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Diagnostics", "Debug", "All").forEach { mode ->
                                val isSelected = logFilterMode == mode
                                val containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF252525)
                                val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.LightGray
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { logFilterMode = mode }
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF3C3C3C),
                                            shape = RoundedCornerShape(12.dp)
                                        ),
                                    shape = RoundedCornerShape(12.dp),
                                    color = containerColor
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = mode,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 11.sp
                                            ),
                                            color = contentColor
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Console Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF333333), shape = RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            if (filteredLogs.isEmpty()) {
                                Text(
                                    text = when (logFilterMode) {
                                        "Diagnostics" -> "No core diagnostic events captured. Standard operations will stream here."
                                        "Debug" -> "No live frame debug events active. Stream live view to capture frames."
                                        else -> "No logs captured yet."
                                    },
                                    style = androidx.compose.ui.text.TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    ),
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(filteredLogs) { log ->
                                        val color = when {
                                            log.contains("🔴") || log.contains("ERROR") || log.contains("error") -> Color(0xFFF44336)
                                            log.contains("⚠️") || log.contains("WARN") || log.contains("warn") -> Color(0xFFFFEB3B)
                                            log.contains("ℹ️") || log.contains("INFO") || log.contains("info") -> Color(0xFF03A9F4)
                                            else -> Color(0xFF4CAF50)
                                        }
                                        Text(
                                            text = log,
                                            style = androidx.compose.ui.text.TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = color
                                            ),
                                            modifier = Modifier.padding(vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val statsText = """
                                        === SafeScan Diagnostic Stats ===
                                        Developer: Girdhari_Jaat (WhatsApp: +92 346 8925992)
                                        Total Documents: $totalDocs
                                        Total Pages Scanned: $totalPages
                                        Average Pages per Doc: $avgPages
                                        Active Memory Slots: ${slots.size}
                                        Current Mode: ${currentMode.name}
                                        Filters Used: ${filtersUsed.joinToString { "${it.first}: ${it.second}" }}
                                    """.trimIndent()
                                    
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("SafeScan Stats", statsText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Stats copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Copy Stats", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
