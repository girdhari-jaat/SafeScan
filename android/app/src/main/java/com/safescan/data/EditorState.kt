package com.safescan.data

enum class FilterType { COLOR, GRAYSCALE, BLACK_WHITE, MAGIC_COLOR, PHOTO, AUTO, CARD }

data class EditorState(
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val sharpness: Float = 0f,
    val saturation: Float = 0f,
    val filter: FilterType = FilterType.COLOR
)
