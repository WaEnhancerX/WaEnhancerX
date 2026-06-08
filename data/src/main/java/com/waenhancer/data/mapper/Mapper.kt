package com.waenhancer.data.mapper

import com.waenhancer.core.database.entity.WaexToolEntity
import com.waenhancer.data.remote.dto.WaexToolDto
import com.waenhancer.domain.model.WaexTool

fun WaexToolEntity.toDomain(): WaexTool {
    return WaexTool(
        id = id,
        name = name,
        description = description,
        isEnabled = isEnabled,
        category = category
    )
}

fun WaexToolDto.toEntity(): WaexToolEntity {
    return WaexToolEntity(
        id = id,
        name = name,
        description = description,
        isEnabled = isEnabled,
        category = category
    )
}

fun WaexToolDto.toDomain(): WaexTool {
    return WaexTool(
        id = id,
        name = name,
        description = description,
        isEnabled = isEnabled,
        category = category
    )
}
