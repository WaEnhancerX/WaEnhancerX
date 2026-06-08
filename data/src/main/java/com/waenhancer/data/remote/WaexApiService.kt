package com.waenhancer.data.remote

import com.waenhancer.core.result.Result
import com.waenhancer.data.remote.dto.WaexToolDto
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface WaexApiService {

    @GET("tools")
    suspend fun getTools(): Result<List<WaexToolDto>>

    @POST("tools/{id}/toggle")
    suspend fun toggleTool(
        @Path("id") id: String,
        @Query("enabled") enabled: Boolean
    ): Result<Unit>
}
