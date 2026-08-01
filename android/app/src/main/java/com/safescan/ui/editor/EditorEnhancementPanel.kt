package com.safescan.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safescan.R
import com.safescan.data.EditorState
import com.safescan.data.FilterType
import com.safescan.utils.HapticFeedbackHelper

@Composable
fun EditorEnhancementPanel(
    activePanel: String?,
    editorState: EditorState,
    applyAllFilters: Boolean = false,
    onApplyAllToggled: (Boolean) -> Unit = {},
    onFilterSelected: (FilterType) -> Unit,
    onEditorStateUpdate: (EditorState) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (activePanel == "filters") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enhancement Filters",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    val view = LocalView.current
                    Surface(
                        onClick = {
                            val newState = !applyAllFilters
                            onApplyAllToggled(newState)
                            HapticFeedbackHelper.triggerHaptic(view)
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (applyAllFilters) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            1.dp,
                            if (applyAllFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (applyAllFilters) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = "Apply All",
                                tint = if (applyAllFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Apply All",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (applyAllFilters) FontWeight.Bold else FontWeight.Medium,
                                color = if (applyAllFilters) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(FilterType.values()) { filterType ->
                        FilterItem(
                            filterType = filterType,
                            isSelected = editorState.filter == filterType,
                            onClick = { onFilterSelected(filterType) }
                        )
                    }
                }
            } else if (activePanel == "adjustments") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Manual Adjustments",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = {
                                onEditorStateUpdate(
                                    editorState.copy(
                                        brightness = 0f,
                                        contrast = 1.0f,
                                        sharpness = 0.0f,
                                        saturation = 0f
                                    )
                                )
                            },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text("Reset", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            AdjustmentSlider(
                                label = stringResource(id = R.string.brightness),
                                value = editorState.brightness,
                                valueRange = -50f..50f,
                                onValueChange = { onEditorStateUpdate(editorState.copy(brightness = it)) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            AdjustmentSlider(
                                label = stringResource(id = R.string.contrast),
                                value = editorState.contrast,
                                valueRange = 0.5f..3.0f,
                                onValueChange = { onEditorStateUpdate(editorState.copy(contrast = it)) }
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            AdjustmentSlider(
                                label = stringResource(id = R.string.sharpness),
                                value = editorState.sharpness,
                                valueRange = 0.0f..3.0f,
                                onValueChange = { onEditorStateUpdate(editorState.copy(sharpness = it)) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            AdjustmentSlider(
                                label = stringResource(id = R.string.saturation),
                                value = editorState.saturation,
                                valueRange = -100f..100f,
                                onValueChange = { onEditorStateUpdate(editorState.copy(saturation = it)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilterItem(
    filterType: FilterType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val displayName = when (filterType) {
        FilterType.COLOR -> "Original"
        FilterType.MAGIC_COLOR -> "Magic"
        FilterType.PAPER -> "Paper"
        FilterType.CARD -> "Card"
        FilterType.BLACK_WHITE -> "B&W"
        FilterType.GRAYSCALE -> "Gray"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .border(
                width = 2.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayName,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

@Composable
fun AdjustmentSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = String.format("%.1f", value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
