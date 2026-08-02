package com.safescan.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object DiagnosticsLogger {
    private val buffer = ArrayDeque<String>(1005)
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        val timestamp = synchronized(timeFormat) {
            timeFormat.format(Date())
        }
        val formattedLog = "[$timestamp] $message"
        val snapshot: List<String>
        synchronized(buffer) {
            if (buffer.size >= 1000) {
                buffer.removeFirst()
            }
            buffer.addLast(formattedLog)
            snapshot = ArrayList(buffer)
        }
        _logs.value = snapshot
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
        synchronized(buffer) {
            buffer.clear()
        }
        _logs.value = emptyList()
    }
}

