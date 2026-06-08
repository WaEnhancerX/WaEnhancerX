package com.waenhancer.core.logger

import android.util.Log

object Logger {
    private const val GLOBAL_TAG = "WaEnhancerX"

    fun d(tag: String, message: String) {
        Log.d("$GLOBAL_TAG:$tag", message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("$GLOBAL_TAG:$tag", message, throwable)
    }

    fun i(tag: String, message: String) {
        Log.i("$GLOBAL_TAG:$tag", message)
    }

    fun w(tag: String, message: String) {
        Log.w("$GLOBAL_TAG:$tag", message)
    }
}
