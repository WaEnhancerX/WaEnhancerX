package com.waenhancer.core.network.adapter

import com.waenhancer.core.result.Result
import okhttp3.Request
import okio.Timeout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ResultCall<T>(private val delegate: Call<T>) : Call<Result<T>> {

    override fun enqueue(callback: Callback<Result<T>>) {
        delegate.enqueue(object : Callback<T> {
            override fun onResponse(call: Call<T>, response: Response<T>) {
                val body = response.body()
                val code = response.code()
                val error = response.errorBody()

                if (response.isSuccessful) {
                    if (body != null) {
                        callback.onResponse(this@ResultCall, Response.success(Result.Success(body)))
                    } else {
                        // Response is successful but body is null (e.g. 204 No Content)
                        @Suppress("UNCHECKED_CAST")
                        callback.onResponse(this@ResultCall, Response.success(Result.Success(Unit as T)))
                    }
                } else {
                    val msg = error?.string() ?: "Unknown error"
                    callback.onResponse(
                        this@ResultCall,
                        Response.success(Result.Error(Exception("API Error $code: $msg")))
                    )
                }
            }

            override fun onFailure(call: Call<T>, t: Throwable) {
                callback.onResponse(this@ResultCall, Response.success(Result.Error(t)))
            }
        })
    }

    override fun isExecuted(): Boolean = delegate.isExecuted
    override fun execute(): Response<Result<T>> = throw UnsupportedOperationException("ResultCall doesn't support execute")
    override fun cancel() = delegate.cancel()
    override fun isCanceled(): Boolean = delegate.isCanceled
    override fun clone(): Call<Result<T>> = ResultCall(delegate.clone())
    override fun request(): Request = delegate.request()
    override fun timeout(): Timeout = delegate.timeout()
}
