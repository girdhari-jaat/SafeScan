package com.safescan.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.safescan.ui.SlotsScreen
import com.safescan.ui.theme.SafeScanTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class ScannerFragment : Fragment() {

    private var _binding: FragmentScannerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ScannerViewModel by viewModels()
    private val shutterSound = android.media.MediaActionSound()

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var imageAnalysis: ImageAnalysis? = null

    private var flashEnabled = false

    private enum class FragmentViewMode {
        LIBRARY,
        SCANNER
    }
    private var currentViewMode = FragmentViewMode.LIBRARY

    // On-demand permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            updateViewMode(FragmentViewMode.SCANNER)
        } else {
            Toast.makeText(context, "Camera permission is required to scan documents.", Toast.LENGTH_LONG).show()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(it)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    viewModel.onCapture(bitmap)
                    Toast.makeText(context, "Image imported successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Error reading imported image", e)
                Toast.makeText(context, "Failed to import image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val scannerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = com.google.android.gms.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.forEach { page ->
                val uri = page.imageUri
                // Import this URI into the app's JPG storage
                viewModel.importFromUri(uri)
            }
        }
    }

    private fun startMLKitScanner() {
        val scanner = com.google.android.gms.documentscanner.GmsDocumentScanning.getClient(
            com.google.android.gms.documentscanner.GmsDocumentScannerOptions.Builder()
                .setResultFormats(com.google.android.gms.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(com.google.android.gms.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
        )
        scanner.getStartScanIntent(requireActivity())
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                // Handle failure
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupObservers()
        setupListeners()

        // Handle physical device back presses gracefully
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val isEditing = viewModel.isEditing.value
                val isCropping = viewModel.isCropping.value
                val isSettingsOpen = viewModel.isSettingsOpen.value
                val isGridViewVisible = viewModel.isGridViewVisible.value
                if (isGridViewVisible) {
                    viewModel.isGridViewVisible.value = false
                } else if (isSettingsOpen) {
                    viewModel.isSettingsOpen.value = false
                } else if (isCropping) {
                    viewModel.isCropping.value = false
                } else if (isEditing) {
                    viewModel.isEditing.value = false
                } else if (currentViewMode == FragmentViewMode.SCANNER) {
                    updateViewMode(FragmentViewMode.LIBRARY)
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        // Default app to Library on startup
        updateViewMode(FragmentViewMode.LIBRARY)
    }

    private fun updateViewMode(mode: FragmentViewMode) {
        currentViewMode = mode
        if (mode == FragmentViewMode.LIBRARY) {
            // Hide camera-related XML views entirely
            binding.previewView.visibility = View.GONE
            binding.btnCapture.visibility = View.GONE
            binding.btnFlash.visibility = View.GONE
            binding.btnSwitchEngine.visibility = View.GONE
            binding.resultImageView.visibility = View.GONE

            // Release Camera Resources immediately
            val currentContext = context
            if (currentContext != null) {
                try {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(currentContext)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            cameraProvider.unbindAll()
                        } catch (e: Exception) {
                            Log.e("ScannerFragment", "Failed to release camera", e)
                        }
                    }, ContextCompat.getMainExecutor(currentContext))
                } catch (e: Exception) {
                    Log.e("ScannerFragment", "Error requesting camera provider", e)
                }
            }

            // Bind Compose View to LibraryScreen
            binding.composeView.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    SafeScanTheme {
                        LibraryScreen(
                            viewModel = viewModel,
                            onStartScan = {
                                checkPermissionAndStartScanner()
                            },
                            onOpenDocument = { doc ->
                                viewModel.loadDocumentIntoSlots(doc)
                                checkPermissionAndStartScanner()
                            }
                        )
                    }
                }
            }
        } else {
            // Show camera-related XML views (only previewView, hide old buttons)
            binding.previewView.visibility = View.VISIBLE
            binding.btnCapture.visibility = View.GONE
            binding.btnFlash.visibility = View.GONE
            binding.btnSwitchEngine.visibility = View.GONE
            
            // Start live CameraX preview
            startCamera()

            // Bind Compose View to scanner/editor layout overlays
            binding.composeView.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    SafeScanTheme {
                        val isEditing by viewModel.isEditing.collectAsState()
                        val isCropping by viewModel.isCropping.collectAsState()
                        val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()
                        if (isSettingsOpen) {
                            com.safescan.ui.SettingsScreen(
                                viewModel = viewModel,
                                onBack = { viewModel.isSettingsOpen.value = false }
                            )
                        } else if (isCropping) {
                            com.safescan.ui.CropScreen(viewModel = viewModel)
                        } else if (isEditing) {
                            com.safescan.ui.EditorScreen(viewModel = viewModel)
                        } else {
                            SlotsScreen(
                                viewModel = viewModel,
                                onCaptureClick = { takePhoto() },
                                onAutoScanClick = { startMLKitScanner() },
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
                                    viewModel.openCrop(slotId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionAndStartScanner() {
        val permissionsToRequest = mutableListOf(android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            updateViewMode(FragmentViewMode.SCANNER)
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun setupListeners() {
        binding.btnCapture.setOnClickListener {
            takePhoto()
        }

        binding.btnFlash.setOnClickListener {
            toggleFlash()
        }

        binding.btnSwitchEngine.setOnClickListener {
            val current = viewModel.uiState.value.currentEngine
            val next = if (current == ScannerEngineType.MLKIT) {
                ScannerEngineType.LOCAL_ML
            } else {
                ScannerEngineType.MLKIT
            }
            viewModel.toggleEngine(next)
            context?.let { ctx ->
                Toast.makeText(ctx, "Engine set to: $next", Toast.LENGTH_SHORT).show()
            }
        }

        binding.previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val factory = binding.previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                
                val doubleFocus = viewModel.doubleFocusEnabled.value
                val action = if (doubleFocus) {
                    val centerPoint = factory.createPoint(binding.previewView.width / 2f, binding.previewView.height / 2f)
                    FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .addPoint(centerPoint, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                } else {
                    FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                }
                
                cameraControl?.startFocusAndMetering(action)
                if (doubleFocus) {
                    Toast.makeText(requireContext(), "Dual-Point Focus Lock Active", Toast.LENGTH_SHORT).show()
                }
                return@setOnTouchListener true
            }
            false
        }
    }

    private fun startCamera() {
        if (!isAdded) return
        val currentContext = context ?: return
        if (ContextCompat.checkSelfPermission(currentContext, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(currentContext)

        cameraProviderFuture.addListener({
            try {
                val fragmentContext = context ?: return@addListener
                val binding = _binding ?: return@addListener
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val mode = viewModel.currentMode.value

                // 1. Dynamic Hardware Negotiation & Mood Alignment (from gemini.md rules)
                val hdModeValue = viewModel.hdMode.value
                val batterySaverActive = viewModel.batterySaver.value

                val targetResolution = when (hdModeValue) {
                    "Fast" -> android.util.Size(1280, 720)
                    "High" -> android.util.Size(3840, 2160)
                    else -> android.util.Size(1920, 1080)
                }

                val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            targetResolution,
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val captureMode = when (mode) {
                    com.safescan.data.ScannerMode.CARD -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    com.safescan.data.ScannerMode.GRID -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    com.safescan.data.ScannerMode.DOCUMENT -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                }

                val previewBuilder = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                
                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(captureMode)
                    .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                    .setResolutionSelector(resolutionSelector)
                    .build()

                val analysisResolution = if (batterySaverActive) android.util.Size(640, 480) else android.util.Size(1280, 720)
                val analysisSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            analysisResolution,
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(analysisSelector)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            if (viewModel.liveDetect.value) {
                                viewModel.detectEdges(imageProxy) { points, width, height ->
                                    viewModel.updateLiveDetectionPoints(points, width, height)
                                    imageProxy.close()
                                }
                            } else {
                                viewModel.updateLiveDetectionPoints(null)
                                imageProxy.close()
                            }
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()

                val camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis
                )

                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo

            } catch (exc: Exception) {
                Log.e("ScannerFragment", "CameraX initialization or binding failed", exc)
                context?.let { ctx ->
                    Toast.makeText(ctx, "Failed to initialize camera: ${exc.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }

        }, ContextCompat.getMainExecutor(context ?: return))
    }

    override fun onResume() {
        super.onResume()
        if (currentViewMode == FragmentViewMode.SCANNER && allPermissionsGranted()) {
            startCamera()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val currentContext = context ?: return false
        return ContextCompat.checkSelfPermission(
            currentContext, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val currentContext = context ?: return
        val binding = _binding ?: return

        binding.progressBar.visibility = View.VISIBLE

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(currentContext),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    if (viewModel.clickSound.value) {
                        shutterSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
                    }
                    val rawBitmap = imageProxy.toBitmap()
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val bitmap = if (rotationDegrees != 0) {
                        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                        val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                        rawBitmap.recycle()
                        rotated
                    } else {
                        rawBitmap
                    }
                    viewModel.onCapture(bitmap)
                    imageProxy.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("ScannerFragment", "Photo capture failed: ${exception.message}", exception)
                    _binding?.progressBar?.visibility = View.GONE
                    context?.let { ctx ->
                        Toast.makeText(ctx, "Capture failed: ${exception.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun toggleFlash() {
        flashEnabled = !flashEnabled
        cameraControl?.enableTorch(flashEnabled)
        imageCapture?.flashMode = if (flashEnabled) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
        
        _binding?.btnFlash?.alpha = if (flashEnabled) 1.0f else 0.5f
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val binding = _binding ?: return@collect
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    
                    state.scannedBitmap?.let { bitmap ->
                        binding.resultImageView.visibility = View.VISIBLE
                        binding.resultImageView.setImageBitmap(bitmap)
                        binding.previewView.visibility = View.INVISIBLE
                    } ?: run {
                        if (currentViewMode == FragmentViewMode.SCANNER) {
                            binding.resultImageView.visibility = View.GONE
                            binding.previewView.visibility = View.VISIBLE
                        }
                    }

                    state.errorMessage?.let { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Auto restart camera to align with new mood attributes on mode switch
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentMode.collect { mode ->
                    if (currentViewMode == FragmentViewMode.SCANNER) {
                        startCamera()
                    }
                }
            }
        }

        // Sync Flash with ViewModel
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flashOn.collect { enabled ->
                    if (flashEnabled != enabled) {
                        flashEnabled = enabled
                        cameraControl?.enableTorch(enabled)
                        imageCapture?.flashMode = if (enabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                        _binding?.btnFlash?.alpha = if (enabled) 1.0f else 0.5f
                    }
                }
            }
        }

        // Handle HD Mode change
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.hdMode.collect { mode ->
                    if (currentViewMode == FragmentViewMode.SCANNER) {
                        startCamera()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        imageCapture = null
        cameraControl = null
        cameraInfo = null
        cameraExecutor.shutdown()
    }
}
