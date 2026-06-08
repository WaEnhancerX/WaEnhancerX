package com.waenhancer.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class WaexToolDto(
    val id: String,
    val name: String,
    val description: String,
    val isEnabled: Boolean,
    val category: String
)
