package com.safescan.ui

import android.annotation.SuppressLint
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.safescan.databinding.FragmentScannerBinding
import com.safescan.scanner.ScannerEngineType
import com.safescan.scanner.ScannerViewModel
import com.safescan.domain.model.Point
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.safescan.ui.SlotsScreen
import com.safescan.ui.theme.SafeScanTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class ScannerFragment : Fragment() {

    private var _binding: FragmentScannerBinding? = null
    val binding get() = _binding!!

    val viewModel: ScannerViewModel by viewModels()
    val liveEdgeDetectionEngine by lazy { com.safescan.scanner.LiveEdgeDetectionEngine() }

    lateinit var cameraController: CameraController
    val permissionManager = PermissionManager(this)

    val cameraExecutor get() = cameraController.cameraExecutor
    val imageCapture get() = cameraController.imageCapture
    val cameraControl get() = cameraController.cameraControl
    val cameraInfo get() = cameraController.cameraInfo
    val cameraProvider get() = cameraController.cameraProvider
    val imageAnalysis get() = cameraController.imageAnalysis
    val isCameraBound get() = cameraController.isCameraBound
    var isTargetLocked: Boolean
        get() = cameraController.isTargetLocked
        set(value) { cameraController.isTargetLocked = value }
    var lastDetectedScreenCorners: List<android.graphics.PointF>?
        get() = cameraController.lastDetectedScreenCorners
        set(value) { cameraController.lastDetectedScreenCorners = value }

    private var flashEnabled = false

    enum class FragmentViewMode {
        LIBRARY,
        WIZARD,
        SCANNER
    }
    var currentViewMode = FragmentViewMode.LIBRARY
    private var lastBackPressedTime = 0L
    private var isCapturingPhoto = false

    override fun onPause() {
        super.onPause()
        cameraController.unbindAll()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            updateViewMode(FragmentViewMode.SCANNER)
            Toast.makeText(context, "Camera permission is required to scan documents.", Toast.LENGTH_LONG).show()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris: List<android.net.Uri>? ->
        uris?.let { list ->
            if (list.isEmpty()) return@let
            
            viewLifecycleOwner.lifecycleScope.launch {
                var successCount = 0
                for (uri in list) {
                    try {
                        val inputStream = requireContext().contentResolver.openInputStream(uri)
                        var importedBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        if (importedBitmap != null) {
                            // Always apply EXIF orientation correction so images display upright
                            val exifRotation = getExifRotation(requireContext(), uri)
                            if (exifRotation != 0) {
                                val matrix = android.graphics.Matrix().apply { postRotate(exifRotation.toFloat()) }
                                val rotated = android.graphics.Bitmap.createBitmap(importedBitmap, 0, 0, importedBitmap.width, importedBitmap.height, matrix, true)
                                importedBitmap.recycle()
                                importedBitmap = rotated
                            }
                            // If Auto-Rotation is enabled, apply intelligent mode-based aspect ratio correction
                            if (viewModel.autoRotation.value) {
                                importedBitmap = autoRotateForMode(importedBitmap, viewModel.currentMode.value)
                            }
                            viewModel.onCapture(importedBitmap, forceSkipEditor = list.size > 1)
                            successCount++
                        }
                    } catch (e: Exception) {
                        Log.e("ScannerFragment", "Error reading imported image", e)
                    }
                }
                if (successCount > 0) {
                    Toast.makeText(context, "$successCount images imported successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to import images", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var photoUri: android.net.Uri? = null

    private val takePictureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            photoUri?.let { uri ->
                try {
                    val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        android.graphics.ImageDecoder.decodeBitmap(
                            android.graphics.ImageDecoder.createSource(requireContext().contentResolver, uri)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        android.provider.MediaStore.Images.Media.getBitmap(requireContext().contentResolver, uri)
                    }
                    // It returns a hardware bitmap on P+. We might need to copy it to a software bitmap to process it.
                    var softwareBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                    if (softwareBitmap != bitmap && !bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                    
                    // Always apply EXIF orientation correction so images display upright
                    val exifRotation = getExifRotation(requireContext(), uri)
                    if (exifRotation != 0) {
                        val matrix = android.graphics.Matrix().apply { postRotate(exifRotation.toFloat()) }
                        val rotated = android.graphics.Bitmap.createBitmap(softwareBitmap, 0, 0, softwareBitmap.width, softwareBitmap.height, matrix, true)
                        softwareBitmap.recycle()
                        softwareBitmap = rotated
                    }
                    // If Auto-Rotation is enabled, apply intelligent mode-based aspect ratio correction
                    if (viewModel.autoRotation.value) {
                        softwareBitmap = autoRotateForMode(softwareBitmap, viewModel.currentMode.value)
                    }
                    
                    viewModel.onCapture(softwareBitmap, isNativeScanned = true)
                } catch (e: Exception) {
                    Log.e("ScannerFragment", "Failed to process captured image", e)
                }
            }
        }
    }

    private val systemDocumentScannerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.let { pages ->
                viewLifecycleOwner.lifecycleScope.launch {
                    for (page in pages) {
                        try {
                            val uri = page.imageUri
                            val inputStream = requireContext().contentResolver.openInputStream(uri)
                            var scanBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                            if (scanBitmap != null) {
                                // Always apply EXIF orientation correction so images display upright
                                val exifRotation = getExifRotation(requireContext(), uri)
                                if (exifRotation != 0) {
                                    val matrix = android.graphics.Matrix().apply { postRotate(exifRotation.toFloat()) }
                                    val rotated = android.graphics.Bitmap.createBitmap(scanBitmap, 0, 0, scanBitmap.width, scanBitmap.height, matrix, true)
                                    scanBitmap.recycle()
                                    scanBitmap = rotated
                                }
                                // If Auto-Rotation is enabled, apply intelligent mode-based aspect ratio correction
                                if (viewModel.autoRotation.value) {
                                    scanBitmap = autoRotateForMode(scanBitmap, viewModel.currentMode.value)
                                }
                                viewModel.onCapture(scanBitmap, isNativeScanned = true, forceSkipEditor = true)
                            }
                        } catch (e: Exception) {
                            Log.e("ScannerFragment", "Error processing scanned image", e)
                        }
                    }
                }
            }
        }
    }

    private val ocrScannerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val scannedText = data?.getStringExtra("label")
                ?: data?.getStringExtra("text")
                ?: data?.getStringExtra("com.google.android.gms.actions.extra.TEXT")
                ?: data?.getStringExtra("com.google.android.gms.actions.extra.OCR_TEXT")
                ?: data?.getStringExtra("OCR_TEXT")
                ?: ""
            
            if (scannedText.isNotEmpty()) {
                viewModel.recognizedText.value = scannedText
                Toast.makeText(context, "Text captured successfully!", Toast.LENGTH_SHORT).show()
                Toast.makeText(context, "No text detected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val qrCodeScannerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val scannedQr = data?.getStringExtra("android.provider.extra.SCAN_QR_CODE_RESULT")
                ?: data?.getStringExtra("SCAN_RESULT")
                ?: data?.data?.toString()
                ?: ""
            
            if (scannedQr.isNotEmpty()) {
                viewModel.recognizedText.value = scannedQr
                Toast.makeText(context, "QR Scanned: $scannedQr", Toast.LENGTH_LONG).show()
                Toast.makeText(context, "No QR data found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openDocumentScanner(maxPages: Int) {
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(maxPages)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
                
            val scanner = GmsDocumentScanning.getClient(options)
            scanner.getStartScanIntent(requireActivity())
                .addOnSuccessListener { intentSender ->
                    systemDocumentScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                .addOnFailureListener { e ->
                    Log.e("ScannerFragment", "Failed to start system document scanner", e)
                    Toast.makeText(context, "System document scanner not available.", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Log.e("ScannerFragment", "Failed to start system document scanner", e)
            Toast.makeText(context, "System document scanner not available.", Toast.LENGTH_SHORT).show()
        }
    }

    fun openOcrScanner() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse("googleapp://lens")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW)
                fallbackIntent.data = android.net.Uri.parse("https://lens.google.com/")
                startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "Install Google Lens", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openQrScanner() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val intent = Intent("android.provider.action.SCAN_QR_CODE")
                qrCodeScannerLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Failed to start system QR scanner", e)
                Toast.makeText(context, "System QR scanner not available.", Toast.LENGTH_SHORT).show()
            }
            Toast.makeText(requireContext(), "Requires Android 14+", Toast.LENGTH_SHORT).show()
        }
    }

    private var shutterSound: android.media.MediaActionSound? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
        shutterSound = android.media.MediaActionSound()
        shutterSound?.load(android.media.MediaActionSound.SHUTTER_CLICK)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraController = CameraController(this)

        setupPermissions()
        setupCamera()
        setupObservers()
        setupListeners()
        setupNavigation()
    }

    private fun setupPermissions() {
        permissionManager.setupPermissions()
    }

    private fun setupCamera() {
        cameraController.setupCamera()
    }

    private fun setupNavigation() {
        ScannerNavigation(this).setupNavigation()
    }

    fun updateViewMode(mode: FragmentViewMode) {
        currentViewMode = mode
        updateCameraState()
        
        // Disable touch forwarding listener when exiting scanner view to save battery and resources
        if (mode != FragmentViewMode.SCANNER) {
            binding.composeView.setOnTouchListener(null)
        }

        if (mode == FragmentViewMode.LIBRARY) {
            // Hide camera-related XML views entirely
            binding.btnCapture.visibility = View.GONE
            binding.btnFlash.visibility = View.GONE
            binding.btnSwitchEngine.visibility = View.GONE
            binding.resultImageView.visibility = View.GONE
            binding.overlayView.visibility = View.GONE
            binding.overlayView.updateCorners(null)

            // Bind Compose View to LibraryScreen
            binding.composeView.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    SafeScanTheme {
                        LibraryScreen(
                            viewModel = viewModel,
                            onStartScan = {
                                viewModel.isDocumentOpenedFromLibrary.value = false
                                viewModel.openedDocumentId = null
                                // CRITICAL PERFORMANCE & STATE RESET: Do not remove endSession()!
                                // Calling endSession() ensures that starting a 'New Scan' clears any 
                                // previously viewed or loaded document slots from memory.
                                viewModel.endSession()
                                if (viewModel.wizardDontShowAgain.value) {
                                    checkPermissionAndStartScanner()
                                } else {
                                    updateViewMode(FragmentViewMode.WIZARD)
                                }
                            },
                            onOpenDocument = { doc ->
                                viewModel.loadDocumentIntoSlots(doc)
                                checkPermissionAndStartScanner()
                            }
                        )
                    }
                }
            }
        } else if (mode == FragmentViewMode.WIZARD) {
            // Hide camera-related XML views entirely
            binding.btnCapture.visibility = View.GONE
            binding.btnFlash.visibility = View.GONE
            binding.btnSwitchEngine.visibility = View.GONE
            binding.resultImageView.visibility = View.GONE
            binding.overlayView.visibility = View.GONE
            binding.overlayView.updateCorners(null)

            // Bind Compose View to WizardScreen
            binding.composeView.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    SafeScanTheme {
                        com.safescan.ui.WizardScreen(
                            viewModel = viewModel,
                            onStartScan = {
                                checkPermissionAndStartScanner()
                            },
                            onClose = {
                                updateViewMode(FragmentViewMode.LIBRARY)
                            }
                        )
                    }
                }
            }
        } else {
            // Show camera-related XML views (only previewView, hide old buttons)
            binding.btnCapture.visibility = View.GONE
            binding.btnFlash.visibility = View.GONE
            binding.btnSwitchEngine.visibility = View.GONE
            
            // Bind Compose View to scanner/editor layout overlays
            binding.composeView.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                
                // PERFORMANCE & GESTURE OPTIMIZATION: Forward unconsumed viewfinder touch events
                // (like pinch-to-zoom & tap-to-focus) to the underlying PreviewView while ignoring
                // touches on the top/bottom bars to prevent focusing on buttons.
                setOnTouchListener { v, event ->
                    val viewHeight = v.height
                    if (viewHeight > 0) {
                        val yPercent = event.y / viewHeight
                        // Viewfinder active area is between top bar (12%) and bottom dashboard (75%)
                        if (yPercent in 0.12f..0.75f) {
                            binding.previewView.dispatchTouchEvent(event)
                        }
                    }
                    false // Let ComposeView handle touches for its own UI controls
                }

                setContent {
                    val isEditing by viewModel.isEditing.collectAsState()
                    val isCropping by viewModel.isCropping.collectAsState()
                    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()
                    val isDocOpenFromLib by viewModel.isDocumentOpenedFromLibrary.collectAsState()

                    val isDarkScreen = !isSettingsOpen && !isCropping && !isEditing && !isDocOpenFromLib
                    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
                    val forceDark = isDarkScreen || systemDark

                    SafeScanTheme(
                        darkTheme = forceDark,
                        statusBarColor = if (isDarkScreen) androidx.compose.ui.graphics.Color.Transparent else null,
                        navigationBarColor = if (isDarkScreen) androidx.compose.ui.graphics.Color.Black else null
                    ) {
                        val showOverlay = !isSettingsOpen && !isCropping && !isEditing && !isDocOpenFromLib
                        androidx.compose.runtime.LaunchedEffect(showOverlay) {
                            binding.overlayView.visibility = if (showOverlay) View.VISIBLE else View.GONE
                            if (!showOverlay) {
                                binding.overlayView.updateCorners(null)
                            }
                        }

                        if (isSettingsOpen) {
                            com.safescan.ui.SettingsScreen(
                                viewModel = viewModel,
                                onBack = { viewModel.isSettingsOpen.value = false }
                            )
                        } else if (isCropping) {
                            com.safescan.ui.CropScreen(viewModel = viewModel)
                        } else if (isEditing) {
                            com.safescan.ui.EditorScreen(viewModel = viewModel)
                        } else if (isDocOpenFromLib) {
                            com.safescan.ui.DocumentGridView(
                                viewModel = viewModel,
                                onDismiss = {
                                    viewModel.isDocumentOpenedFromLibrary.value = false
                                    // CRITICAL CLEANUP: Do not remove endSession()!
                                    // This resets and clears document slot states when closing the 
                                    // document view and returning to the Library screen.
                                    viewModel.endSession()
                                    updateViewMode(FragmentViewMode.LIBRARY)
                                },
                                onScanPage = {
                                    viewModel.isDocumentOpenedFromLibrary.value = false
                                    viewModel.selectedSlotId.value = null
                                }
                            )
                        } else {
                            SlotsScreen(
                                viewModel = viewModel,
                                onCaptureClick = { focusAndTakePhoto(isAutoCapture = false) },
                                onClose = { updateViewMode(FragmentViewMode.LIBRARY) },
                                onFlashToggle = { toggleFlash() },
                                onGalleryClick = { pickImageLauncher.launch("image/*") },
                                onSlotClick = { slotId ->
                                    viewModel.onSlotClick(slotId)
                                    // return to preview view and result image gone
                                    binding.resultImageView.visibility = View.GONE
                                    binding.previewView.visibility = View.VISIBLE
                                },
                                onSlotLongClick = { slotId ->
                                    viewModel.openEditor(slotId)
                                },
                                onWizardClick = { updateViewMode(FragmentViewMode.WIZARD) }
                            )
                        }
                    }
                }
            }
        }
    }

    fun checkPermissionAndStartScanner() {
        permissionManager.checkPermissionAndStartScanner()
    }

    private fun setupListeners() {
        ScannerUiActions(this).setupListeners()
    }

    private fun mapPointsToPreviewView(
        points: List<Point>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        rotationDegrees: Int
    ): List<android.graphics.PointF> {
        val binding = _binding ?: return emptyList()
        val viewWidth = binding.previewView.width.toFloat()
        val viewHeight = binding.previewView.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return emptyList()

        val rotatedWidth: Float
        val rotatedHeight: Float
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            rotatedWidth = bitmapHeight.toFloat()
            rotatedHeight = bitmapWidth.toFloat()
        } else {
            rotatedWidth = bitmapWidth.toFloat()
            rotatedHeight = bitmapHeight.toFloat()
        }

        val frameRatio = rotatedWidth / rotatedHeight
        val viewRatio = viewWidth / viewHeight

        val scale: Float
        val dx: Float
        val dy: Float

        if (frameRatio > viewRatio) {
            // Frame is wider than view -> width fits, top/bottom black bars
            scale = viewWidth / rotatedWidth
            dx = 0f
            dy = (viewHeight - rotatedHeight * scale) / 2f
        } else {
            // Frame is taller than view -> height fits, left/right black bars
            scale = viewHeight / rotatedHeight
            dx = (viewWidth - rotatedWidth * scale) / 2f
            dy = 0f
        }

        Log.d("LiveEdgeDetection", "mapPointsToPreviewView: dx=$dx dy=$dy scale=$scale viewW=$viewWidth viewH=$viewHeight rot=$rotationDegrees")

        return points.map { pt ->
            val normX = pt.x.toFloat() / bitmapWidth
            val normY = pt.y.toFloat() / bitmapHeight

            val rotatedX: Float
            val rotatedY: Float
            when (rotationDegrees) {
                90 -> {
                    rotatedX = 1f - normY
                    rotatedY = normX
                }
                180 -> {
                    rotatedX = 1f - normX
                    rotatedY = 1f - normY
                }
                270 -> {
                    rotatedX = normY
                    rotatedY = 1f - normX
                }
                else -> {
                    rotatedX = normX
                    rotatedY = normY
                }
            }

            val screenX = (rotatedX * rotatedWidth * scale) + dx
            val screenY = (rotatedY * rotatedHeight * scale) + dy

            android.graphics.PointF(screenX, screenY)
        }
    }

    private fun getOverlayHoleRect(pw: Float, ph: Float): android.graphics.RectF {
        val mode = viewModel.currentMode.value
        val finalRatio = com.safescan.utils.PageConfig.getOnscreenLayoutRatio(requireContext(), mode)

        val maxWidth = pw * 0.90f
        val maxHeight = ph * 0.85f

        var rectWidth = maxWidth
        var rectHeight = rectWidth / finalRatio

        if (rectHeight > maxHeight) {
            rectHeight = maxHeight
            rectWidth = rectHeight * finalRatio
        }

        val rectLeft = (pw - rectWidth) / 2f
        val rectTop = (ph - rectHeight) / 2f
        return android.graphics.RectF(rectLeft, rectTop, rectLeft + rectWidth, rectTop + rectHeight)
    }

    private fun getExifRotation(context: android.content.Context, uri: android.net.Uri): Int {
        var rotation = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exifInterface = android.media.ExifInterface(inputStream)
                val orientation = exifInterface.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
                rotation = when (orientation) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ScannerFragment", "Failed to read EXIF rotation for uri: $uri", e)
        }
        return rotation
    }

    private fun autoRotateForMode(bitmap: android.graphics.Bitmap, mode: com.safescan.data.ScannerMode): android.graphics.Bitmap {
        val isLandscape = bitmap.width > bitmap.height
        val needsRotation = when (mode) {
            com.safescan.data.ScannerMode.DOCUMENT -> isLandscape // DOCUMENT should be portrait
            com.safescan.data.ScannerMode.CARD, com.safescan.data.ScannerMode.GRID -> !isLandscape // CARD/GRID should be landscape
        }
        return if (needsRotation) {
            val angle = when (mode) {
                com.safescan.data.ScannerMode.DOCUMENT -> 90f
                com.safescan.data.ScannerMode.CARD, com.safescan.data.ScannerMode.GRID -> -90f
            }
            com.safescan.core.ScannerDebugLogger.logAutoRotation(angle)
            val matrix = android.graphics.Matrix().apply { postRotate(angle) }
            val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            rotated
        } else {
            com.safescan.core.ScannerDebugLogger.logAutoRotation(0f)
            bitmap
        }
    }

    fun updateCameraState() {
        cameraController.setupCamera()
    }

    override fun onResume() {
        super.onResume()
        updateCameraState()
    }

    fun allPermissionsGranted(): Boolean {
        return permissionManager.allPermissionsGranted()
    }

    private fun openPhoneCamera() {
        val context = requireContext()
        val photoFile = java.io.File(context.cacheDir, "temp_camera_${System.currentTimeMillis()}.jpg")
        photoUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile
        )
        takePictureLauncher.launch(photoUri)
    }

    fun focusAndTakePhoto(isAutoCapture: Boolean = false) {
        if (isCapturingPhoto) return
        if (isAutoCapture) {
            if (viewModel.isFocusing) return
        } else {
            // Manual capture takes precedence - clear any lock or auto-capture cooldown
            viewModel.isFocusing = false
        }
        viewModel.isFocusing = true

        val binding = _binding
        if (binding == null) {
            viewModel.isFocusing = false
            takePhoto()
            return
        }
        
        val previewView = binding.previewView
        val mappedCorners = binding.overlayView.getCorners()
        
        var centerX = previewView.width / 2f
        var centerY = previewView.height / 2f
        
        if (mappedCorners != null && mappedCorners.size == 4) {
            var sumX = 0f
            var sumY = 0f
            for (p in mappedCorners) {
                sumX += p.x
                sumY += p.y
            }
            centerX = sumX / 4f
            centerY = sumY / 4f
        }

        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(centerX, centerY)
        // Relaxed settings for Auto Capture
        val flags = if (isAutoCapture) androidx.camera.core.FocusMeteringAction.FLAG_AF else androidx.camera.core.FocusMeteringAction.FLAG_AF or androidx.camera.core.FocusMeteringAction.FLAG_AE
        val action = androidx.camera.core.FocusMeteringAction.Builder(
            point, 
            flags
        ).build()
        
        val future = cameraControl?.startFocusAndMetering(action)
        if (future == null) {
            viewModel.isFocusing = false
            takePhoto()
            return
        }

        future.addListener({
            try {
                val result = future.get()
                if (result.isFocusSuccessful) {
                    Log.d("ScannerFragment", "Focus locked and successful. Triggering photo capture.")
                } else {
                    Log.d("ScannerFragment", "Focus lock failed or timed out. Proceeding to take photo as fallback.")
                    cameraControl?.cancelFocusAndMetering()
                }
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Focus metering listener exception", e)
                cameraControl?.cancelFocusAndMetering()
            }
            takePhoto()
        }, androidx.core.content.ContextCompat.getMainExecutor(requireContext()))
    }

    private fun runAccuracyTest(previewCorners: List<android.graphics.PointF>?) {
        android.util.Log.d("ScannerTest", "=== ACCURACY TEST START ===")
        com.safescan.core.DiagnosticsLogger.info("ScannerTest: === ACCURACY TEST START ===")
        val cornersToLog = previewCorners ?: lastDetectedScreenCorners
        if (cornersToLog.isNullOrEmpty()) {
            android.util.Log.d("ScannerTest", "Corners: No document corners detected on overlay")
            com.safescan.core.DiagnosticsLogger.info("ScannerTest: Corners: No document corners detected on overlay")
        } else {
            cornersToLog.forEachIndexed { i, p -> 
                android.util.Log.d("ScannerTest", "Corner $i: View(${p.x},${p.y})")
                com.safescan.core.DiagnosticsLogger.info("ScannerTest: Corner $i: View(${p.x},${p.y})")
            }
        }
        android.util.Log.d("ScannerTest", "=== ACCURACY TEST END ===")
        com.safescan.core.DiagnosticsLogger.info("ScannerTest: === ACCURACY TEST END ===")
    }

    private fun takePhoto() {
        if (isCapturingPhoto) {
            Log.w("ScannerFragment", "takePhoto ignored: photo capture already in progress")
            return
        }
        isCapturingPhoto = true

        if (viewModel.usePhoneCamera.value) {
            viewModel.isFocusing = false
            isCapturingPhoto = false
            openPhoneCamera()
            return
        }
        if (viewModel.useNativeScanner.value) {
            viewModel.isFocusing = false
            isCapturingPhoto = false
            val maxPages = when (viewModel.currentMode.value) {
                com.safescan.data.ScannerMode.CARD -> 2
                com.safescan.data.ScannerMode.GRID -> 8
                else -> 150
            }
            openDocumentScanner(maxPages)
            return
        }

        val imageCapture = imageCapture ?: run {
            viewModel.isFocusing = false
            isCapturingPhoto = false
            return
        }
        val currentContext = context ?: run {
            viewModel.isFocusing = false
            isCapturingPhoto = false
            return
        }
        val binding = _binding ?: run {
            viewModel.isFocusing = false
            isCapturingPhoto = false
            return
        }

        binding.progressBar.visibility = View.VISIBLE

        // Play shutter sound if enabled
        if (viewModel.clickSound.value) {
            try {
                shutterSound?.play(android.media.MediaActionSound.SHUTTER_CLICK)
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Failed to play shutter sound", e)
            }
        }

        // Trigger vibration/haptic feedback if enabled
        if (viewModel.vibrateOnCapture.value) {
            try {
                // Use performHapticFeedback for modern devices
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                
                // Also use Vibrator for redundancy
                val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val vibratorManager = currentContext.getSystemService(android.os.VibratorManager::class.java)
                    vibratorManager?.defaultVibrator
                } else {
                    currentContext.getSystemService(android.os.Vibrator::class.java)
                }
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
                    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(50)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Failed to vibrate on capture", e)
            }
        }

        runAccuracyTest(_binding?.overlayView?.getCorners())

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(currentContext),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    // FIX: FINAL LEAK
                    try {
                        cameraControl?.cancelFocusAndMetering()
                        val rawBitmap = imageProxy.toBitmap()
                        // ALWAYS rotate the raw camera sensor bitmap by imageProxy.imageInfo.rotationDegrees
                        // so it displays in standard upright portrait/landscape orientation matching the screen preview.
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        com.safescan.core.ScannerDebugLogger.logCameraRotation(rotationDegrees)
                        val bitmap = if (rotationDegrees != 0) {
                            val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                            rawBitmap.recycle()
                            rotated
                        } else {
                            rawBitmap
                        }

                        // No cropping to visual cutout overlay frame is needed. We pass the full-resolution uncropped camera image.
                        var finalBitmap = bitmap

                        // If Auto-Rotation is enabled, apply intelligent mode-based aspect ratio/layout correction
                        // (We always auto-rotate CARD and GRID captures so portrait overlays are transformed to landscape instantly)
                        if (viewModel.autoRotation.value || viewModel.currentMode.value == com.safescan.data.ScannerMode.CARD || viewModel.currentMode.value == com.safescan.data.ScannerMode.GRID) {
                            finalBitmap = autoRotateForMode(finalBitmap, viewModel.currentMode.value)
                        }

                        viewModel.onCapture(finalBitmap)
                        isCapturingPhoto = false
                        if (viewModel.autoCapture.value) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.delay(2500)
                                viewModel.isFocusing = false
                            }
                        } else {
                            viewModel.isFocusing = false
                        }
                    } finally {
                        imageProxy.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cameraControl?.cancelFocusAndMetering()
                    viewModel.isFocusing = false
                    isCapturingPhoto = false
                    Log.e("ScannerFragment", "Photo capture failed: ${exception.message}", exception)
                    _binding?.progressBar?.visibility = View.GONE
                    context?.let { ctx ->
                        Toast.makeText(ctx, "Capture failed: ${exception.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    fun toggleFlash() {
        ScannerUiActions(this).toggleFlash()
    }

    private fun setupObservers() {
        ScannerObservers(this).setupObservers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // FIX: FINAL LEAK
        try {
            context?.let { ctx ->
                val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx)
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                } else {
                    cameraProviderFuture.addListener({
                        try {
                            cameraProviderFuture.get().unbindAll()
                        } catch (e: Exception) {
                            Log.e("ScannerFragment", "Failed to unbind camera in onDestroyView listener", e)
                        }
                    }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
                }
            }
        } catch (e: Exception) {
            Log.e("ScannerFragment", "Failed to unbind camera in onDestroyView", e)
        }
        try {
            liveEdgeDetectionEngine.release()
        } catch (e: Exception) {
            Log.e("ScannerFragment", "Failed to release liveEdgeDetectionEngine", e)
        }
        _binding = null
        shutterSound?.release()
        shutterSound = null
    }
}
