package com.waenhancer.core.network.adapter

import com.waenhancer.core.result.Result
import java.lang.reflect.Type
import retrofit2.Call
import retrofit2.CallAdapter

class ResultCallAdapter<R>(private val responseType: Type) : CallAdapter<R, Call<Result<R>>> {
    override fun responseType(): Type = responseType
    override fun adapt(call: Call<R>): Call<Result<R>> = ResultCall(call)
}
