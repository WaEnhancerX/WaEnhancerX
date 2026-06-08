package com.waenhancer.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "waex_tools")
data class WaexToolEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val isEnabled: Boolean,
    val category: String
)
