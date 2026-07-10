sed -i '/cameraControl?.enableTorch(enabled)/c\                        cameraControl?.enableTorch(torchOn)' android/app/src/main/java/com/safescan/ui/ScannerFragment.kt
sed -i '/imageCapture?.flashMode = if (enabled) {/c\                    imageCapture?.flashMode = when (mode) {\n                        com.safescan.data.FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO\n                        com.safescan.data.FlashMode.ON -> ImageCapture.FLASH_MODE_ON\n                        else -> ImageCapture.FLASH_MODE_OFF\n                    }' android/app/src/main/java/com/safescan/ui/ScannerFragment.kt
sed -i '/ImageCapture.FLASH_MODE_ON/d' android/app/src/main/java/com/safescan/ui/ScannerFragment.kt
sed -i '/} else {/d' android/app/src/main/java/com/safescan/ui/ScannerFragment.kt
sed -i '/ImageCapture.FLASH_MODE_OFF/d' android/app/src/main/java/com/safescan/ui/ScannerFragment.kt
sed -i '/_binding?.btnFlash?.alpha = if (enabled) 1.0f else 0.5f/c\                    _binding?.btnFlash?.alpha = if (mode != com.safescan.data.FlashMode.OFF) 1.0f else 0.5f' android/app/src/main/java/com/safescan/ui/ScannerFragment.kt
