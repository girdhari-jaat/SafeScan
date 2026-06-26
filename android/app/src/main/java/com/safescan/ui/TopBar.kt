package com.safescan.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safescan.data.ScannerMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    currentMode: ScannerMode,
    autoCrop: Boolean,
    flashOn: Boolean,
    onModeChange: (ScannerMode) -> Unit,
    onAutoCropChange: (Boolean) -> Unit,
    onFlashChange: (Boolean) -> Unit,
    onSettingsClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("SafeScan") },
        actions = {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Grid Mode")
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = currentMode == ScannerMode.GRID,
                                onCheckedChange = { isGrid ->
                                    onModeChange(if (isGrid) ScannerMode.GRID else ScannerMode.CARD)
                                }
                            )
                        }
                    },
                    onClick = { }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto Crop")
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = autoCrop,
                                onCheckedChange = { onAutoCropChange(it) }
                            )
                        }
                    },
                    onClick = { }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Flash")
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = flashOn,
                                onCheckedChange = { onFlashChange(it) }
                            )
                        }
                    },
                    onClick = { }
                )
                Divider()
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = {
                        expanded = false
                        onSettingsClick()
                    }
                )
            }
        }
    )
}
