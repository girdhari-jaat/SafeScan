package com.safescan

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.java.TfLite
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize LiteRT (TFLite) via Google Play Services
        val options = TfLiteInitializationOptions.builder()
            .setEnableGpuDelegateSupport(true)
            .build()
            
        TfLite.initialize(this, options)
            .addOnSuccessListener {
                Log.d("MainActivity", "LiteRT Play Services initialized successfully")
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "LiteRT Play Services initialization failed", e)
                Toast.makeText(this, "LiteRT init failed!", Toast.LENGTH_LONG).show()
            }
        
        setContentView(R.layout.activity_main)
    }
}
