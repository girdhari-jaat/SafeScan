package com.safescan.data

import android.graphics.Bitmap

data class Slot(
    val id: String,
    val label: String,
    val bitmap: Bitmap? = null
)
