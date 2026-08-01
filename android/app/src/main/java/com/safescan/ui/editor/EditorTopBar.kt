package com.safescan.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.safescan.R

@Composable
fun EditorTopBar(
    onClose: () -> Unit,
    onSaveImage: () -> Unit,
    onToggleExportPopover: () -> Unit,
    onDeletePage: () -> Unit,
    onSaveSlot: () -> Unit,
    onSaveNext: () -> Unit
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
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(id = R.string.cancel),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(onClick = onSaveImage, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "Save Image",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onToggleExportPopover, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = "PDF Export Options",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onDeletePage, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Page",
                    tint = MaterialTheme.colorScheme.error
                )
            }

            IconButton(onClick = onSaveSlot, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(id = R.string.save),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onSaveNext, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
