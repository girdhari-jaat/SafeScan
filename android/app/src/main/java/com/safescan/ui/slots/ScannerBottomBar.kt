package com.safescan.ui.slots

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import com.safescan.scanner.ScannerViewModel
import java.io.File

@Composable
fun ScannerBottomCarousel(
    viewModel: ScannerViewModel,
    onSlotClick: (String) -> Unit,
    onSlotLongClick: (String) -> Unit
) {
    val currentMode by viewModel.currentMode.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val updateTick by viewModel.imageUpdateTick.collectAsState()

    if (currentMode != ScannerMode.DOCUMENT) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp)
                .padding(8.dp)
        ) {
            if (slots.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "No Slots Available", color = Color.Gray)
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(slots) { slot ->
                        Box(modifier = Modifier.width(85.dp)) {
                            SlotItem(
                                slot = slot,
                                updateTick = updateTick,
                                onClick = { onSlotClick(slot.id) },
                                onLongClick = { onSlotLongClick(slot.id) },
                                onClear = { viewModel.clearSlot(slot.id) }
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
fun ScannerBottomActions(
    viewModel: ScannerViewModel,
    onGalleryClick: () -> Unit,
    onCaptureClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isBatchActive by viewModel.batchScan.collectAsState()
    val slots by viewModel.slots.collectAsState()

    val scannedCount = if (viewModel.capturedJpgFiles.isNotEmpty()) {
        viewModel.capturedJpgFiles.size
    } else {
        slots.count { it.bitmap != null }
    }
    val hasScans = scannedCount > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onGalleryClick,
            modifier = Modifier
                .size(52.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = "Import from Gallery",
                tint = Color.White
            )
        }

        Box(
            modifier = Modifier
                .size(76.dp)
                .border(4.dp, Color.White, CircleShape)
                .background(Color.Transparent, CircleShape)
                .clickable { onCaptureClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.White, CircleShape)
            )
        }

        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    if (hasScans) MaterialTheme.colorScheme.primary
                    else if (isBatchActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else Color.Black.copy(alpha = 0.5f)
                )
                .clickable {
                    if (hasScans) {
                        viewModel.isGridViewVisible.value = true
                    } else {
                        viewModel.toggleBatchScan(!isBatchActive)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (hasScans && uiState.lastCapturedThumbnail != null) {
                Image(
                    bitmap = uiState.lastCapturedThumbnail!!.asImageBitmap(),
                    contentDescription = "Last captured thumbnail",
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$scannedCount",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (hasScans) {
                        Text(
                            text = "✓",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "($scannedCount)",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Text(
                            text = if (isBatchActive) "Batch\nON" else "Batch\nOFF",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlotItem(
    slot: Slot,
    updateTick: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.LightGray)
            .combinedClickable(
                onClick = {
                    if (slot.bitmap == null) onClick() else onLongClick()
                },
                onLongClick = { if (slot.bitmap != null) onLongClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (slot.bitmap != null) {
            val modelData = slot.bitmapPath?.let { File(it) } ?: slot.bitmap
            val cacheKey = remember(updateTick, slot.bitmapPath) { slot.bitmapPath?.let { File(it).lastModified().toString() } ?: System.currentTimeMillis().toString() }
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(modelData)
                    .setParameter("tick", updateTick, null)
                    .memoryCacheKey(slot.bitmapPath.orEmpty() + cacheKey + updateTick)
                    .diskCacheKey(slot.bitmapPath.orEmpty() + cacheKey + updateTick)
                    .build(),
                contentDescription = slot.label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            IconButton(
                onClick = onClear,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear slot",
                    tint = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                        .padding(4.dp)
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add image",
                    tint = Color.DarkGray
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = slot.label,
                    color = Color.DarkGray,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
