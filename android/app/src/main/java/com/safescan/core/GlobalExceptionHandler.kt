package com.safescan.core

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlobalExceptionHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val cacheDir = context.cacheDir
            val logDir = File(cacheDir, "crash_logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val logFile = File(logDir, "crash_$timestamp.txt")

            FileWriter(logFile, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println("Thread: ${t.name}")
                    e.printStackTrace(pw)
                }
            }
        } catch (ignored: Exception) {
            // Ignored, we are already crashing
        } finally {
            defaultHandler?.uncaughtException(t, e)
        }
    }

    companion object {
        fun initialize(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(context, defaultHandler))
        }
    }
}
