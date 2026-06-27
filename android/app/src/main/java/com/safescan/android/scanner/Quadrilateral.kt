package com.safescan.android.scanner

data class Point(val x: Double, val y: Double) {
    constructor() : this(0.0, 0.0)
}

data class Quadrilateral(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point
)
