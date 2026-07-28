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
    val captureManager = ScannerCaptureManager(this)

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
    private var flashEnabled = false

    enum class FragmentViewMode {
        LIBRARY,
        WIZARD,
        SCANNER
    }
    var currentViewMode = FragmentViewMode.LIBRARY
    private var lastBackPressedTime = 0L
    val isCapturingPhoto get() = captureManager.isCapturingPhoto

    override fun onPause() {
        super.onPause()
        cameraController.unbindAll()
        _binding?.overlayView?.visibility = View.GONE
        _binding?.overlayView?.updateCorners(null)
    }

    override fun onStop() {
        super.onStop()
        cameraController.unbindAll()
        _binding?.overlayView?.visibility = View.GONE
        _binding?.overlayView?.updateCorners(null)
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
                            val exifRotation = ScannerImageUtils.getExifRotation(requireContext(), uri)
                            if (exifRotation != 0) {
                                val matrix = android.graphics.Matrix().apply { postRotate(exifRotation.toFloat()) }
                                val rotated = android.graphics.Bitmap.createBitmap(importedBitmap, 0, 0, importedBitmap.width, importedBitmap.height, matrix, true)
                                importedBitmap.recycle()
                                importedBitmap = rotated
                            }
                            // If Auto-Rotation is enabled, apply intelligent mode-based aspect ratio correction
                            if (viewModel.autoRotation.value) {
                                importedBitmap = ScannerImageUtils.autoRotateForMode(importedBitmap, viewModel.currentMode.value)
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

    val takePictureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            captureManager.photoUri?.let { uri ->
                captureManager.processCapturedPhonePhoto(uri)
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
                                val exifRotation = ScannerImageUtils.getExifRotation(requireContext(), uri)
                                if (exifRotation != 0) {
                                    val matrix = android.graphics.Matrix().apply { postRotate(exifRotation.toFloat()) }
                                    val rotated = android.graphics.Bitmap.createBitmap(scanBitmap, 0, 0, scanBitmap.width, scanBitmap.height, matrix, true)
                                    scanBitmap.recycle()
                                    scanBitmap = rotated
                                }
                                // If Auto-Rotation is enabled, apply intelligent mode-based aspect ratio correction
                                if (viewModel.autoRotation.value) {
                                    scanBitmap = ScannerImageUtils.autoRotateForMode(scanBitmap, viewModel.currentMode.value)
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

    var shutterSound: android.media.MediaActionSound? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val sound = android.media.MediaActionSound()
                sound.load(android.media.MediaActionSound.SHUTTER_CLICK)
                shutterSound = sound
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Failed to pre-load shutter sound", e)
            }
        }
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraController = CameraController(this)

        setupPermissions()
        setupObservers()
        setupListeners()

        // Render default LIBRARY view mode instantly on frame 1
        updateViewMode(FragmentViewMode.LIBRARY)

        setupNavigation()
    }

    private fun setupPermissions() {
        permissionManager.setupPermissions()
    }

    fun setupCamera() {
        cameraController.setupCamera()
    }

    fun updateCameraState() {
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
            cameraController.unbindAll()
            binding.overlayView.visibility = View.GONE
            binding.overlayView.updateCorners(null)
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

                    val isCameraLiveScreen = !isSettingsOpen && !isEditing && !isCropping && !isDocOpenFromLib
                    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
                    val forceDark = isCameraLiveScreen || systemDark

                    SafeScanTheme(
                        darkTheme = forceDark
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

    fun focusAndTakePhoto(isAutoCapture: Boolean = false) {
        captureManager.focusAndTakePhoto(isAutoCapture)
    }

    fun openPhoneCamera() {
        captureManager.openPhoneCamera()
    }

    fun runAccuracyTest(previewCorners: List<android.graphics.PointF>?) {
        captureManager.runAccuracyTest(previewCorners)
    }

    fun toggleFlash() {
        ScannerUiActions(this).toggleFlash()
    }

    private fun setupObservers() {
        ScannerObservers(this).setupObservers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraController.destroy()
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
