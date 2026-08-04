package com.waenhancer.api.contracts

import kotlinx.serialization.KSerializer

sealed interface WaexPreferenceType<T : Any> {
    object BooleanType : WaexPreferenceType<Boolean>
    object IntType : WaexPreferenceType<Int>
    object LongType : WaexPreferenceType<Long>
    object StringType : WaexPreferenceType<String>
    data class EnumType<E : Enum<E>>(val enumClass: Class<E>) : WaexPreferenceType<E>
    data class JsonType<T : Any>(val valueClass: Class<T>, val serializer: KSerializer<T>) : WaexPreferenceType<T>
}

data class WaexPreferenceKey<T : Any>(
    val key: String,
    val type: WaexPreferenceType<T>,
    val defaultValue: T
) {
    init {
        validateKey(key)
    }

    private fun validateKey(key: String) {
        val parts = key.split(".")
        require(parts.size == 2) { "Key must follow '<category>.<keyName>' format: $key" }
        val category = parts[0]
        val validCategories = setOf(
            "privacy", "media", "status", "chat",
            "interface", "automation", "security", "pro"
        )
        require(category in validCategories) { "Invalid category namespace: $category" }
        
        val keyName = parts[1]
        require(keyName.isNotEmpty() && keyName[0].isLowerCase()) {
            "Key name must follow lowerCamelCase: $keyName"
        }
    }

    companion object {
        fun boolean(key: String, defaultValue: Boolean): WaexPreferenceKey<Boolean> =
            WaexPreferenceKey(key, WaexPreferenceType.BooleanType, defaultValue)

        fun int(key: String, defaultValue: Int): WaexPreferenceKey<Int> =
            WaexPreferenceKey(key, WaexPreferenceType.IntType, defaultValue)

        fun long(key: String, defaultValue: Long): WaexPreferenceKey<Long> =
            WaexPreferenceKey(key, WaexPreferenceType.LongType, defaultValue)

        fun string(key: String, defaultValue: String): WaexPreferenceKey<String> =
            WaexPreferenceKey(key, WaexPreferenceType.StringType, defaultValue)

        inline fun <reified E : Enum<E>> enum(key: String, defaultValue: E): WaexPreferenceKey<E> =
            WaexPreferenceKey(key, WaexPreferenceType.EnumType(E::class.java), defaultValue)

        fun <T : Any> json(key: String, valueClass: Class<T>, serializer: KSerializer<T>, defaultValue: T): WaexPreferenceKey<T> =
            WaexPreferenceKey(key, WaexPreferenceType.JsonType(valueClass, serializer), defaultValue)
    }
}
