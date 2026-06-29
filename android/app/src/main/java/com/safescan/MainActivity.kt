package com.safescan

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.OpenCVLoader
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "OpenCV init failed!", Toast.LENGTH_LONG).show()
        }
        
        setContentView(R.layout.activity_main)
    }
}
