package com.safescan

import android.app.Application
import com.safescan.core.GlobalExceptionHandler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SafeScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalExceptionHandler.initialize(this)
    }
}
