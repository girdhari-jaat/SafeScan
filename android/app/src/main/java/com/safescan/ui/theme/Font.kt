package com.safescan.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.safescan.R

object Font {
    val regular = FontFamily(Font(R.font.inter_regular, FontWeight.Normal))
    val bold = FontFamily(Font(R.font.inter_bold, FontWeight.Bold))
    val mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))
}
