package com.safescan.ocr

import android.app.Activity
import android.widget.Toast
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus

fun Activity.downloadOcrModule(onComplete: () -> Unit) {
    val manager = SplitInstallManagerFactory.create(this)
    val request = SplitInstallRequest.newBuilder()
        .addModule("mlkit_ocr")
        .build()

    Toast.makeText(this, "Starting OCR Module download...", Toast.LENGTH_SHORT).show()

    var mySessionId = 0
    val listener = object : SplitInstallStateUpdatedListener {
        override fun onStateUpdate(state: com.google.android.play.core.splitinstall.SplitInstallSessionState) {
            if (state.sessionId() == mySessionId) {
                when (state.status()) {
                    SplitInstallSessionStatus.DOWNLOADING -> {
                        Toast.makeText(this@downloadOcrModule, "Downloading OCR Module...", Toast.LENGTH_SHORT).show()
                    }
                    SplitInstallSessionStatus.INSTALLING -> {
                        Toast.makeText(this@downloadOcrModule, "Installing OCR Module...", Toast.LENGTH_SHORT).show()
                    }
                    SplitInstallSessionStatus.INSTALLED -> {
                        Toast.makeText(this@downloadOcrModule, "OCR Module Installed!", Toast.LENGTH_SHORT).show()
                        manager.unregisterListener(this)
                        onComplete()
                    }
                    SplitInstallSessionStatus.FAILED -> {
                        Toast.makeText(this@downloadOcrModule, "OCR Module Download Failed", Toast.LENGTH_SHORT).show()
                        manager.unregisterListener(this)
                    }
                }
            }
        }
    }

    manager.registerListener(listener)
    manager.startInstall(request)
        .addOnSuccessListener { sessionId ->
            mySessionId = sessionId
        }
        .addOnFailureListener { exception ->
            Toast.makeText(this, "Installation failed: ${exception.message}", Toast.LENGTH_SHORT).show()
            manager.unregisterListener(listener)
        }
}

fun Activity.downloadBarcodeModule(onComplete: () -> Unit) {
    val manager = SplitInstallManagerFactory.create(this)
    val request = SplitInstallRequest.newBuilder()
        .addModule("mlkit_barcode")
        .build()

    Toast.makeText(this, "Starting Barcode Module download...", Toast.LENGTH_SHORT).show()

    var mySessionId = 0
    val listener = object : SplitInstallStateUpdatedListener {
        override fun onStateUpdate(state: com.google.android.play.core.splitinstall.SplitInstallSessionState) {
            if (state.sessionId() == mySessionId) {
                when (state.status()) {
                    SplitInstallSessionStatus.DOWNLOADING -> {
                        Toast.makeText(this@downloadBarcodeModule, "Downloading Barcode Module...", Toast.LENGTH_SHORT).show()
                    }
                    SplitInstallSessionStatus.INSTALLING -> {
                        Toast.makeText(this@downloadBarcodeModule, "Installing Barcode Module...", Toast.LENGTH_SHORT).show()
                    }
                    SplitInstallSessionStatus.INSTALLED -> {
                        Toast.makeText(this@downloadBarcodeModule, "Barcode Module Installed!", Toast.LENGTH_SHORT).show()
                        manager.unregisterListener(this)
                        onComplete()
                    }
                    SplitInstallSessionStatus.FAILED -> {
                        Toast.makeText(this@downloadBarcodeModule, "Barcode Module Download Failed", Toast.LENGTH_SHORT).show()
                        manager.unregisterListener(this)
                    }
                }
            }
        }
    }

    manager.registerListener(listener)
    manager.startInstall(request)
        .addOnSuccessListener { sessionId ->
            mySessionId = sessionId
        }
        .addOnFailureListener { exception ->
            Toast.makeText(this, "Installation failed: ${exception.message}", Toast.LENGTH_SHORT).show()
            manager.unregisterListener(listener)
        }
}
