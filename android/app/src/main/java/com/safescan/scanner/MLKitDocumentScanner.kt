package com.safescan.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.google.android.gms.documentscanner.GmsDocumentScanner
import com.google.android.gms.documentscanner.GmsDocumentScannerOptions
import com.google.android.gms.documentscanner.GmsDocumentScanning
import com.google.android.gms.documentscanner.GmsDocumentScanningResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MLKitDocumentScanner @Inject constructor() {

    private val options = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(20)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    fun getScannerClient(activity: Activity): GmsDocumentScanner {
        return GmsDocumentScanning.getClient(options)
    }

    /**
     * Helper to start the scanner activity.
     */
    fun startScan(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
        val scanner = getScannerClient(activity)
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                // Note: This requires handling the intent sender in the activity/fragment
                // Usually via startIntentSenderForResult
            }
            .addOnFailureListener {
                // Handle failure
            }
    }
}
