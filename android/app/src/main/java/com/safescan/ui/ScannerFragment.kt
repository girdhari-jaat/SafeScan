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
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    private var flashEnabled = false

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

        binding.composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SafeScanTheme {
                    val isEditing by viewModel.isEditing.collectAsState()
                    val isCropping by viewModel.isCropping.collectAsState()
                    if (isCropping) {
                        com.safescan.ui.CropScreen(viewModel = viewModel)
                    } else if (isEditing) {
                        com.safescan.ui.EditorScreen(viewModel = viewModel)
                    } else {
                        SlotsScreen(
                            viewModel = viewModel,
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

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupObservers()
        setupListeners()
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
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val context = context ?: return@addListener
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val mode = viewModel.currentMode.value

            // 1. Dynamic Hardware Negotiation & Mood Alignment (from gemini.md rules)
            val captureMode = when (mode) {
                com.safescan.data.ScannerMode.CARD -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY // Fast capture, lower latency
                com.safescan.data.ScannerMode.GRID -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                com.safescan.data.ScannerMode.DOCUMENT -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY // Standard balanced
                com.safescan.data.ScannerMode.BOOK -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY // High-quality detail focus
            }

            val targetFrameRateRange = when (mode) {
                com.safescan.data.ScannerMode.CARD -> Range(60, 60) // High frame rate for fast card detection
                else -> Range(30, 30) // Standard frame rate for high detail documents
            }

            val previewBuilder = Preview.Builder()
            // setTargetFrameRate is removed to avoid potential runtime crashes on unsupported devices
            // as it is only natively supported in CameraX 1.4.0+ or via specific interop
            
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(captureMode)
                .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                val camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview, imageCapture
                )

                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo

            } catch (exc: Exception) {
                Log.e("ScannerFragment", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(context ?: return))
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        requireContext(), android.Manifest.permission.CAMERA
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        binding.progressBar.visibility = View.VISIBLE

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxy.toBitmap()
                    viewModel.onCapture(bitmap)
                    imageProxy.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("ScannerFragment", "Photo capture failed: ${exception.message}", exception)
                    _binding?.progressBar?.visibility = View.GONE
                    context?.let { ctx ->
                        Toast.makeText(ctx, "Capture failed", Toast.LENGTH_SHORT).show()
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
        
        binding.btnFlash.alpha = if (flashEnabled) 1.0f else 0.5f
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    
                    state.scannedBitmap?.let { bitmap ->
                        binding.resultImageView.visibility = View.VISIBLE
                        binding.resultImageView.setImageBitmap(bitmap)
                        binding.previewView.visibility = View.INVISIBLE
                    } ?: run {
                        binding.resultImageView.visibility = View.GONE
                        binding.previewView.visibility = View.VISIBLE
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
                    startCamera()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        cameraExecutor.shutdown()
    }
}
