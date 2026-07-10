package com.safescan

import android.app.Application
import android.content.Context
import android.util.Log
import com.safescan.core.GlobalExceptionHandler
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class SafeScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalExceptionHandler.initialize(this)
        
        if (!OpenCVLoader.initDebug()) {
            Log.e("SafeScanApplication", "OpenCVLoader.initDebug() failed! Trying manual load...")
            try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("opencv_java4")
                Log.d("SafeScanApplication", "Manual OpenCV init succeeded!")
            } catch (e: Throwable) {
                Log.e("SafeScanApplication", "Manual OpenCV init failed: ${e.message}", e)
            }
        } else {
            Log.d("SafeScanApplication", "OpenCV init succeeded!")
        }
    }
}
