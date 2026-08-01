package com.safescan.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EditorBottomToolbar(
    activePanel: String?,
    onCropClick: () -> Unit,
    onRotateLeftClick: () -> Unit,
    onRotateRightClick: () -> Unit,
    onToggleFilter: () -> Unit,
    onToggleAdjust: () -> Unit,
    onOcrClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomToolbarItem(
                icon = Icons.Default.Crop,
                label = "Crop",
                onClick = onCropClick
            )
            BottomToolbarItem(
                icon = Icons.AutoMirrored.Filled.RotateLeft,
                label = "Rotate L",
                onClick = onRotateLeftClick
            )
            BottomToolbarItem(
                icon = Icons.AutoMirrored.Filled.RotateRight,
                label = "Rotate R",
                onClick = onRotateRightClick
            )
            BottomToolbarItem(
                icon = Icons.Default.AutoFixHigh,
                label = "Filter",
                selected = activePanel == "filters",
                onClick = onToggleFilter
            )
            BottomToolbarItem(
                icon = Icons.Default.Tune,
                label = "Adjust",
                selected = activePanel == "adjustments",
                onClick = onToggleAdjust
            )
            BottomToolbarItem(
                icon = Icons.Default.TextFields,
                label = "OCR",
                onClick = onOcrClick
            )
        }
    }
}

@Composable
fun BottomToolbarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
