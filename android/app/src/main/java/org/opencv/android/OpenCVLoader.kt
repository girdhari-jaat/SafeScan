package org.opencv.android

object OpenCVLoader {
    fun initLocal(): Boolean {
        return try {
            System.loadLibrary("opencv_java4")
            true
        } catch (e: Exception) {
            try {
                System.loadLibrary("opencv_java3")
                true
            } catch (ex: Exception) {
                false
            }
        }
    }
}
