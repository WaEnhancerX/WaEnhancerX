package com.waenhancer.domain.usecase

import com.waenhancer.domain.model.WaexTool
import com.waenhancer.domain.repository.WaexRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetWaexToolsUseCase @Inject constructor(
    private val repository: WaexRepository
) {
    operator fun invoke(): Flow<List<WaexTool>> {
        return repository.getWaexToolsFlow()
    }
}
