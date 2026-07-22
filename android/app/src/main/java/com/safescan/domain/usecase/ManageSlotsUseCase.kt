package com.safescan.domain.usecase

import com.safescan.data.Slot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManageSlotsUseCase @Inject constructor() {
    fun createDefaultSlots(): List<Slot> {
        return listOf(
            Slot(id = "1", title = "Front Side", isCaptured = false),
            Slot(id = "2", title = "Back Side", isCaptured = false)
        )
    }

    fun updateSlotCaptured(slots: List<Slot>, slotId: String, imageUri: String): List<Slot> {
        return slots.map { slot ->
            if (slot.id == slotId) {
                slot.copy(isCaptured = true, capturedImageUri = imageUri)
            } else slot
        }
    }
}
