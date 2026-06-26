package com.safescan.core

sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val message: String, val e: Throwable? = null) : AppResult<Nothing>()
}
