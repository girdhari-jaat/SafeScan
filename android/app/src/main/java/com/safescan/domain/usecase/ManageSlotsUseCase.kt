package com.safescan.domain.usecase

import android.graphics.Bitmap
import com.safescan.data.Slot
import com.safescan.domain.model.Point
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManageSlotsUseCase @Inject constructor() {
    fun createDefaultSlots(): List<Slot> {
        return listOf(
            Slot(id = "1", label = "Front Side"),
            Slot(id = "2", label = "Back Side")
        )
    }

    fun updateSlotCaptured(
        slots: List<Slot>, 
        slotId: String, 
        bitmap: Bitmap, 
        originalBitmap: Bitmap, 
        corners: List<Point>? = null,
        bitmapPath: String? = null,
        originalBitmapPath: String? = null
    ): List<Slot> {
        return slots.map { slot ->
            if (slot.id == slotId) {
                slot.copy(
                    bitmap = bitmap, 
                    originalBitmap = originalBitmap, 
                    corners = corners,
                    bitmapPath = bitmapPath,
                    originalBitmapPath = originalBitmapPath
                )
            } else slot
        }
    }
}
