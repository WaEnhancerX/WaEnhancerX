package com.waenhancer.plugin.api

interface PluginContext {
    fun getHostVersion(): String
    fun log(message: String)
}
