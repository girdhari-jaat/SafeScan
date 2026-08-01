package com.safescan.ui

// QA CHECKLIST:
// 1. DISTANCE TEST: Place A4 at 8 inch. Full page must be visible. If need 1.5ft then FAIL.
// 2. ACCURACY TEST: After capture, compare final cropped image with green box. Must match 100%.

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.safescan.R
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import com.safescan.scanner.ScannerViewModel
import com.safescan.ui.slots.*
import kotlinx.coroutines.delay

// ======================================================
// Main Screen Entry Point
// ======================================================

@Composable
fun SlotsScreen(
    viewModel: ScannerViewModel,
    onCaptureClick: () -> Unit,
    onClose: () -> Unit,
    onFlashToggle: () -> Unit,
    onGalleryClick: () -> Unit,
    onSlotClick: (String) -> Unit,
    onSlotLongClick: (String) -> Unit,
    onWizardClick: () -> Unit
) {
    // ------------------------------------------------------
    // Local UI State
    // ------------------------------------------------------
    var isSettingsPopoverOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // ------------------------------------------------------
        // Main UI Controls Layer
        // ------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // A. Top Bar
            ScannerTopBar(
                viewModel = viewModel,
                onClose = onClose,
                onFlashToggle = onFlashToggle,
                onSettingsClick = { isSettingsPopoverOpen = !isSettingsPopoverOpen }
            )

            // B. Center Overlay Instructions
            ScannerCenterInstructions(viewModel = viewModel)

            // C. Bottom Carousel & Actions Area
            Column(modifier = Modifier.fillMaxWidth()) {
                ScannerBottomCarousel(
                    viewModel = viewModel,
                    onSlotClick = onSlotClick,
                    onSlotLongClick = onSlotLongClick
                )

                ScannerBottomActions(
                    viewModel = viewModel,
                    onGalleryClick = onGalleryClick,
                    onCaptureClick = onCaptureClick
                )
            }
        }

        // ------------------------------------------------------
        // Settings Popover Overlay
        // ------------------------------------------------------
        if (isSettingsPopoverOpen) {
            ScannerSettingsPopover(
                viewModel = viewModel,
                onWizardClick = onWizardClick,
                onDismiss = { isSettingsPopoverOpen = false }
            )
        }

        // ------------------------------------------------------
        // Document Grid View Overlay
        // ------------------------------------------------------
        val isGridViewVisible by viewModel.isGridViewVisible.collectAsState()
        if (isGridViewVisible) {
            DocumentGridView(
                viewModel = viewModel,
                onDismiss = { viewModel.isGridViewVisible.value = false },
                onScanPage = {
                    viewModel.isGridViewVisible.value = false
                    viewModel.selectedSlotId.value = null
                }
            )
        }
    }
}