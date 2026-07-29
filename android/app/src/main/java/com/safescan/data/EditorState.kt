package com.safescan.data

enum class FilterType { COLOR, MAGIC_COLOR, PAPER, CARD, BLACK_WHITE, GRAYSCALE }

data class EditorState(
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val sharpness: Float = 0f,
    val saturation: Float = 0f,
    val filter: FilterType = FilterType.COLOR,
    val rotation: Int = 0
)
