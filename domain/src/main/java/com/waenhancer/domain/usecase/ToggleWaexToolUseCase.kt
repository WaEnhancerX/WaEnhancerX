package com.waenhancer.domain.usecase

import com.waenhancer.domain.repository.WaexRepository
import javax.inject.Inject

class ToggleWaexToolUseCase @Inject constructor(
    private val repository: WaexRepository
) {
    suspend operator fun invoke(id: String, isEnabled: Boolean) {
        repository.toggleWaexTool(id, isEnabled)
    }
}
