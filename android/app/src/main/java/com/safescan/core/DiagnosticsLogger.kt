package com.safescan.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

object DiagnosticsLogger {
    private val buffer = ConcurrentLinkedQueue<String>()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    fun log(message: String) {
        val timestamp = timeFormatter.format(Instant.now())
        val formattedLog = "[$timestamp] $message"
        buffer.add(formattedLog)
        while (buffer.size > 1000) {
            buffer.poll()
        }
        _logs.value = buffer.toList()
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
        buffer.clear()
        _logs.value = emptyList()
    }
}


