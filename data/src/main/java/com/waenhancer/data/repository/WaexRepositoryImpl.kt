package com.waenhancer.data.repository

import com.waenhancer.core.database.dao.WaexToolDao
import com.waenhancer.core.dispatchers.DispatcherProvider
import com.waenhancer.core.logger.Logger
import com.waenhancer.core.result.Result
import com.waenhancer.core.result.onSuccess
import com.waenhancer.core.result.onError
import com.waenhancer.data.mapper.toDomain
import com.waenhancer.data.mapper.toEntity
import com.waenhancer.data.remote.WaexApiService
import com.waenhancer.domain.model.WaexTool
import com.waenhancer.domain.repository.WaexRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WaexRepositoryImpl @Inject constructor(
    private val apiService: WaexApiService,
    private val toolDao: WaexToolDao,
    private val dispatchers: DispatcherProvider
) : WaexRepository {

    private val tag = "WaexRepository"

    override fun getWaexToolsFlow(): Flow<List<WaexTool>> {
        return toolDao.getAllToolsFlow()
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override suspend fun toggleWaexTool(id: String, isEnabled: Boolean) {
        withContext(dispatchers.io) {
            // Optimistically update local database
            toolDao.updateToolStatus(id, isEnabled)

            // Send update to remote api
            val result = apiService.toggleTool(id, isEnabled)
            result.onError {
                Logger.e(tag, "Failed to toggle remote tool $id status", it)
            }
        }
    }

    override suspend fun refreshWaexTools() {
        withContext(dispatchers.io) {
            val result = apiService.getTools()
            result.onSuccess { dtoList ->
                val entities = dtoList.map { it.toEntity() }
                toolDao.insertTools(entities)
            }
        }
    }
}
