package com.safescan.scanner

import com.safescan.data.ScannerMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScannerTitleUtils {

    fun generateDefaultTitle(mode: ScannerMode): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val timestamp = sdf.format(Date())
        return when (mode) {
            ScannerMode.DOCUMENT -> "Doc_$timestamp"
            ScannerMode.CARD, ScannerMode.GRID -> "Card_$timestamp"
        }
    }

    fun resolveDynamicFilename(pattern: String, mode: ScannerMode): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val timestamp = sdf.format(Date())
        
        val trimmed = pattern.trim()
        val staticTimestampRegex = Regex("""^(Doc|Card|Scan)_\d{8}_\d{4}$""", RegexOption.IGNORE_CASE)

        if (trimmed.isEmpty() || 
            trimmed.equals("Doc+Date+Time", ignoreCase = true) || 
            trimmed.equals("Card+Date+Time", ignoreCase = true) || 
            trimmed.equals("Doc_yyyyMMdd_HHmm", ignoreCase = true) || 
            trimmed.equals("Card_yyyyMMdd_HHmm", ignoreCase = true) || 
            trimmed.equals("Scan_Document", ignoreCase = true) ||
            trimmed.matches(staticTimestampRegex)) {
            return when (mode) {
                ScannerMode.DOCUMENT -> "Doc_$timestamp"
                ScannerMode.CARD, ScannerMode.GRID -> "Card_$timestamp"
            }
        }
        
        var resolved = pattern
        if (resolved.contains("Doc+Date+Time", ignoreCase = true)) {
            resolved = resolved.replace("Doc+Date+Time", "Doc_$timestamp", ignoreCase = true)
        }
        if (resolved.contains("Card+Date+Time", ignoreCase = true)) {
            resolved = resolved.replace("Card+Date+Time", "Card_$timestamp", ignoreCase = true)
        }
        if (resolved.contains("Date+Time", ignoreCase = true)) {
            resolved = resolved.replace("Date+Time", timestamp, ignoreCase = true)
        }
        return resolved
    }
}
