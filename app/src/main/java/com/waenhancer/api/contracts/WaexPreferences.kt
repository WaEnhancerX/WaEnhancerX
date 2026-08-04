package com.waenhancer.api.contracts

interface WaexPreferences {
    fun <T : Any> get(key: WaexPreferenceKey<T>): T
    fun getAll(): Map<String, Any>
}
