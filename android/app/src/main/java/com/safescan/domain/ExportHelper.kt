package com.safescan.domain

import com.safescan.data.ScannerMode
import com.safescan.data.Slot

object ExportHelper {
    /**
     * Get front and back slots for a specific row index (0..3) in the PDF/Image grid.
     * For CARD mode, duplicate the same front (slot 0) and back (slot 1) slots 4 times.
     * For other modes (like GRID), map them sequentially.
     */
    fun getSlotsForGridRow(slots: List<Slot>, mode: ScannerMode, rowIndex: Int): Pair<Slot?, Slot?> {
        if (mode == ScannerMode.CARD) {
            val frontItem = slots.getOrNull(0)
            val backItem = slots.getOrNull(1)
            return Pair(frontItem, backItem)
        } else {
            val frontIdx = rowIndex * 2
            val backIdx = rowIndex * 2 + 1
            val frontItem = if (frontIdx < slots.size) slots[frontIdx] else null
            val backItem = if (backIdx < slots.size) slots[backIdx] else null
            return Pair(frontItem, backItem)
        }
    }
}
