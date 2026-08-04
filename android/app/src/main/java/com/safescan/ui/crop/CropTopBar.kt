package com.safescan.ui.crop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safescan.R

@Composable
fun CropTopBar(
    isAutoRunning: Boolean,
    hasNext: Boolean,
    onCancel: () -> Unit,
    onFull: () -> Unit,
    onAiDetect: () -> Unit,
    onAutoDetect: () -> Unit,
    onSave: () -> Unit,
    onNext: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Cancel
            IconButton(
                modifier = Modifier.weight(1f),
                enabled = !isAutoRunning,
                onClick = onCancel
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(id = R.string.cancel))
            }

            // 2. Full
            TextButton(
                modifier = Modifier.weight(1f),
                enabled = !isAutoRunning,
                onClick = onFull
            ) {
                Text(stringResource(id = R.string.full), color = MaterialTheme.colorScheme.onSurface)
            }

            // 3a. TF Auto (TFLite Model based detection)
            TextButton(
                modifier = Modifier.weight(1f),
                enabled = !isAutoRunning,
                onClick = onAiDetect
            ) {
                if (isAutoRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("AI", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }

            // 3. Auto
            TextButton(
                modifier = Modifier.weight(1f),
                enabled = !isAutoRunning,
                onClick = onAutoDetect
            ) {
                if (isAutoRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(id = R.string.auto), color = MaterialTheme.colorScheme.onSurface)
                }
            }

            // 4. Save
            IconButton(
                modifier = Modifier.weight(1f),
                enabled = !isAutoRunning,
                onClick = onSave
            ) {
                Icon(Icons.Default.Check, stringResource(id = R.string.save))
            }

            // 5. Next
            if (hasNext) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    enabled = !isAutoRunning,
                    onClick = onNext
                ) {
                    Text(stringResource(id = R.string.next), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
