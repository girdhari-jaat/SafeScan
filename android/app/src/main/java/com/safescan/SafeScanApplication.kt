package com.safescan

import android.app.Application
import android.content.Context
import android.util.Log
import com.safescan.core.GlobalExceptionHandler
import com.safescan.core.DiagnosticsLogger
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class SafeScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalExceptionHandler.initialize(this)
        DiagnosticsLogger.info("SafeScanApplication starting up...")
        
        if (!OpenCVLoader.initDebug()) {
            Log.e("SafeScanApplication", "OpenCVLoader.initDebug() failed! Trying manual load...")
            DiagnosticsLogger.warn("OpenCV initial loading failed, trying manual...")
            try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("opencv_java4")
                Log.d("SafeScanApplication", "Manual OpenCV init succeeded!")
                DiagnosticsLogger.info("Manual OpenCV initialization succeeded!")
            } catch (e: Throwable) {
                Log.e("SafeScanApplication", "Manual OpenCV init failed: ${e.message}", e)
                DiagnosticsLogger.error("Manual OpenCV loading failed: ${e.message}")
            }
        } else {
            Log.d("SafeScanApplication", "OpenCV init succeeded!")
            DiagnosticsLogger.info("OpenCV core initialization successful.")
        }
    }
}
