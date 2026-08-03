package com.safescan.ui.slots

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safescan.data.FlashMode
import com.safescan.data.ScannerMode
import com.safescan.scanner.ScannerViewModel

@Composable
fun ScannerTopBar(
    viewModel: ScannerViewModel,
    onClose: () -> Unit,
    onFlashToggle: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val currentMode by viewModel.currentMode.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val hdMode by viewModel.hdMode.collectAsState()
    var isFlashMenuOpen by remember { mutableStateOf(false) }
    var isHdMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Close Scanner",
                    tint = Color.White
                )
            }

            IconButton(
                onClick = {
                    isFlashMenuOpen = !isFlashMenuOpen
                    if (isFlashMenuOpen) isHdMenuOpen = false
                },
                modifier = Modifier.background(
                    if (flashMode != FlashMode.OFF) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f),
                    CircleShape
                )
            ) {
                Icon(
                    imageVector = when (flashMode) {
                        FlashMode.AUTO -> Icons.Default.FlashAuto
                        FlashMode.ON -> Icons.Default.FlashOn
                        FlashMode.TORCH -> Icons.Default.FlashOn
                        else -> Icons.Default.FlashOff
                    },
                    contentDescription = "Toggle Flash",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isHdMenuOpen) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f)
                    )
                    .clickable {
                        isHdMenuOpen = !isHdMenuOpen
                        if (isHdMenuOpen) isFlashMenuOpen = false
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                val selectedLabel = when (hdMode.uppercase()) {
                    "FAST" -> "Fast"
                    "STANDARD" -> "Standard"
                    "HIGH" -> "High"
                    else -> hdMode
                }
                Text(
                    text = selectedLabel,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Settings",
                    tint = Color.White
                )
            }
        }

        // Horizontal Flash Mode Selection Bar
        AnimatedVisibility(
            visible = isFlashMenuOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E1E).copy(alpha = 0.95f))
                    .padding(vertical = 6.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val flashOptions = listOf(
                    FlashMode.ON to "On",
                    FlashMode.OFF to "Off",
                    FlashMode.AUTO to "Auto",
                    FlashMode.TORCH to "Stay On"
                )

                flashOptions.forEach { (mode, label) ->
                    val isSelected = flashMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) Color(0xFF383838) else Color.Transparent
                            )
                            .clickable {
                                viewModel.setFlashMode(mode)
                                isFlashMenuOpen = false
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color(0xFF4DB6AC) else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Horizontal HD Quality Mode Selection Bar
        AnimatedVisibility(
            visible = isHdMenuOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E1E).copy(alpha = 0.95f))
                    .padding(vertical = 6.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val hdOptions = listOf("Fast", "Standard", "High")

                hdOptions.forEach { mode ->
                    val isSelected = hdMode.equals(mode, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) Color(0xFF383838) else Color.Transparent
                            )
                            .clickable {
                                viewModel.setHdMode(mode)
                                isHdMenuOpen = false
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode,
                            color = if (isSelected) Color(0xFF4DB6AC) else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF121212))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    ScannerMode.DOCUMENT to "Paper",
                    ScannerMode.CARD to "Card"
                ).forEach { (mode, label) ->
                    val isSelected = currentMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { viewModel.switchMode(mode) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
