package com.waenhancer.core.preferences

/** Deliberately strict fake: all writes are observable, unexpected keys fail. */
class WaexPreferenceManager {
    enum class WriteFault { NONE, FAIL_ONCE_BEFORE, FAIL_ONCE_AFTER, THROW_ONCE_AFTER, FAIL_ALWAYS_AFTER }
    val store = mutableMapOf<String, String>()
    val writes = mutableListOf<Pair<String, String>>()
    var cssFault = WriteFault.NONE

    fun getString(key: String, fallback: String?): String? = store[key] ?: fallback

    fun putPublicString(key: String, value: String): Boolean {
        check(key in setOf("css_theme", "active_theme", "custom_css", "custom_css_theme")) {
            "Unexpected preference key: $key"
        }
        writes += key to value
        if (key != "css_theme") { store[key] = value; return true }
        return when (val fault = cssFault) {
            WriteFault.NONE -> { store[key] = value; true }
            WriteFault.FAIL_ONCE_BEFORE -> { cssFault = WriteFault.NONE; false }
            WriteFault.FAIL_ONCE_AFTER -> { cssFault = WriteFault.NONE; store[key] = value; false }
            WriteFault.THROW_ONCE_AFTER -> {
                cssFault = WriteFault.NONE; store[key] = value
                throw IllegalStateException("simulated post-mutation commit exception")
            }
            WriteFault.FAIL_ALWAYS_AFTER -> { store[key] = value; false }
        }
    }
}
