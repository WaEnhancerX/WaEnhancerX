package com.waenhancer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.waenhancer.core.database.entity.WaexToolEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WaexToolDao {

    @Query("SELECT * FROM waex_tools")
    fun getAllToolsFlow(): Flow<List<WaexToolEntity>>

    @Query("SELECT * FROM waex_tools WHERE id = :id LIMIT 1")
    suspend fun getToolById(id: String): WaexToolEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTool(tool: WaexToolEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTools(tools: List<WaexToolEntity>)

    @Query("UPDATE waex_tools SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun updateToolStatus(id: String, isEnabled: Boolean)
}
