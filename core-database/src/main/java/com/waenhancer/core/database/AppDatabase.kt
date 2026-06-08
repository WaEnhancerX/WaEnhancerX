package com.waenhancer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.waenhancer.core.database.dao.WaexToolDao
import com.waenhancer.core.database.entity.WaexToolEntity

@Database(entities = [WaexToolEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun waexToolDao(): WaexToolDao
}
