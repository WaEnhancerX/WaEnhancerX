package com.waenhancer.plugin.host

import android.content.Context
import com.waenhancer.plugin.api.PluginContext
import dalvik.system.DexClassLoader
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginManager @Inject constructor(
    private val context: Context
) {
    fun loadPlugin(apkFile: File, className: String): Any? {
        return try {
            val dexOptDir = context.codeCacheDir
            val classLoader = DexClassLoader(
                apkFile.absolutePath,
                dexOptDir.absolutePath,
                null,
                context.classLoader
            )
            val clazz = classLoader.loadClass(className)
            clazz.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
