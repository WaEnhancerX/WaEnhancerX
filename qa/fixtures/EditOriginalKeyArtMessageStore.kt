package com.waenhancer.xposed.core.db
import android.content.Context
import java.io.File
object MessageStore {
    @JvmField var hostFile: File? = null
    fun findMsgstoreDbFile(context: Context): File? = hostFile
}
