package com.safescan.ui.slots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safescan.scanner.ScannerViewModel

@Composable
fun ScannerSettingsPopover(
    viewModel: ScannerViewModel,
    onWizardClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val autoCrop by viewModel.autoCrop.collectAsState()
    val doubleFocus by viewModel.doubleFocusEnabled.collectAsState()
    val autoCapture by viewModel.autoCapture.collectAsState()
    val shadowRemove by viewModel.shadowRemove.collectAsState()
    val batterySaver by viewModel.batterySaver.collectAsState()
    val batchScan by viewModel.batchScan.collectAsState()
    val autoRotation by viewModel.autoRotation.collectAsState()
    val usePhoneCamera by viewModel.usePhoneCamera.collectAsState()
    val useNativeScanner by viewModel.useNativeScanner.collectAsState()
    val hdMode by viewModel.hdMode.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onDismiss()
            }
    )

    val configuration = LocalConfiguration.current
    val maxCardHeight = (configuration.screenHeightDp * 0.8).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            modifier = Modifier
                .width(280.dp)
                .heightIn(max = maxCardHeight)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(24.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xF21C1C1E)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onDismiss()
                                viewModel.isSettingsOpen.value = true
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(Color.White.copy(alpha = 0.2f))
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onDismiss()
                                onWizardClick()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = "1 Tap",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "1 Tap",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.1f))
                )

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    PopoverToggleRow(
                        icon = Icons.Default.CameraAlt,
                        label = "Auto Capture",
                        checked = autoCapture,
                        onCheckedChange = { viewModel.toggleAutoCapture(it) }
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
                        .padding(vertical = 6.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.1f))
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("Fast", "Standard", "High").forEach { mode ->
                        val active = hdMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable { viewModel.setHdMode(mode) }
                                .padding(vertical = 4.dp),
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

@Composable
private fun PopoverToggleRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 1.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
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
            modifier = Modifier.scale(0.7f)
        )
    }
}
