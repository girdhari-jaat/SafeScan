package com.safescan.domain.usecase

import com.safescan.data.FilterType
import com.safescan.domain.ImageFilterEngine
import org.opencv.core.Mat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApplyFilterUseCase @Inject constructor() {
    fun execute(src: Mat, filterType: FilterType): Mat {
        return ImageFilterEngine.applyFilter(src, filterType)
    }

    fun executeCardFilter(src: Mat): Mat {
        return ImageFilterEngine.applyCardFilter(src)
    }
}
