package android.content

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.Closeable

open class Context(val filesDir: File, val contentResolver: ContentResolver)

open class ContentResolver {
    val files = mutableMapOf<String, ByteArray>()
    open fun query(uri: Uri, projection: Array<String>?, selection: String?,
                   selectionArgs: Array<String>?, sortOrder: String?): Cursor? =
        Cursor(uri.lastPathSegment ?: "fixture.zip")
    open fun openInputStream(uri: Uri): InputStream? =
        files[uri.value]?.let(::ByteArrayInputStream)
}

class Cursor(private val name: String) : Closeable {
    fun getColumnIndex(column: String): Int = if (column == "_display_name") 0 else -1
    fun moveToFirst(): Boolean = true
    fun getString(columnIndex: Int): String = name
    override fun close() {}
}
