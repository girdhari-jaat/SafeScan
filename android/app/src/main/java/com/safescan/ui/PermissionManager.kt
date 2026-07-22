package com.safescan.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class PermissionManager(
    private val fragment: ScannerFragment
) {
    private val requestPermissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val cameraGranted = ContextCompat.checkSelfPermission(
            fragment.requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            fragment.updateViewMode(ScannerFragment.FragmentViewMode.SCANNER)
        } else {
            Toast.makeText(
                fragment.context,
                "Camera permission is required to scan documents.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun allPermissionsGranted(): Boolean {
        val currentContext = fragment.context ?: return false
        return ContextCompat.checkSelfPermission(
            currentContext, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setupPermissions() {
        if (!allPermissionsGranted()) {
            checkPermissionAndStartScanner()
        }
    }

    fun checkPermissionAndStartScanner() {
        val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(fragment.requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            fragment.updateViewMode(ScannerFragment.FragmentViewMode.SCANNER)
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
}
