package com.waenhancer.domain.repository

import com.waenhancer.domain.model.WaexTool
import kotlinx.coroutines.flow.Flow

interface WaexRepository {
    fun getWaexToolsFlow(): Flow<List<WaexTool>>
    suspend fun toggleWaexTool(id: String, isEnabled: Boolean)
    suspend fun refreshWaexTools()
}
