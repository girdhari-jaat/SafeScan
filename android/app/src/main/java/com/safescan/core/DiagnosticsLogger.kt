package com.safescan.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        val formattedLog = "[$timestamp] $message"
        val currentList = _logs.value.toMutableList()
        currentList.add(formattedLog)
        if (currentList.size > 1000) {
            currentList.removeAt(0)
        }
        _logs.value = currentList
    }

    fun info(message: String) {
        log("ℹ️ $message")
    }

    fun warn(message: String) {
        log("⚠️ $message")
    }

    fun error(message: String) {
        log("🔴 $message")
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
