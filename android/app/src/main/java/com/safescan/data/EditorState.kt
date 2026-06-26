package com.safescan.data

enum class FilterType { COLOR, GRAYSCALE, BLACK_WHITE }

data class EditorState(
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val sharpness: Float = 0f,
    val filter: FilterType = FilterType.COLOR
)
