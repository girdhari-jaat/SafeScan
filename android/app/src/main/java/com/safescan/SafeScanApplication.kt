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
            Log.e("SafeScanApplication", "OpenCVLoader.initDebug() returned false")
            DiagnosticsLogger.warn("OpenCV initial loading returned false.")
        } else {
            Log.d("SafeScanApplication", "OpenCV init succeeded!")
            DiagnosticsLogger.info("OpenCV core initialization successful.")
        }
    }
}
