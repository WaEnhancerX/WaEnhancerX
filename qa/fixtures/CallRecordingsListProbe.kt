package com.waenhancer.qa.recordings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

// Explicit Compose state/effect adapters: no Compose rendering or recomposition claim.
class State<T>(var value: T)
fun <T> mutableStateOf(value: T) = State(value)
operator fun <T> State<T>.getValue(owner: Any?, key: KProperty<*>) = value
operator fun <T> State<T>.setValue(owner: Any?, key: KProperty<*>, value: T) { this.value = value }
fun <T> remember(block: () -> T) = block()
object Effects {
    var work: suspend () -> Unit = {}
    var keys: List<Any?> = emptyList()
    val disposals = mutableListOf<() -> Unit>()
}
fun LaunchedEffect(vararg keys: Any?, block: suspend () -> Unit) { Effects.keys = keys.toList(); Effects.work = block }
class Disposal { fun onDispose(block: () -> Unit) { Effects.disposals.add(block) } }
fun DisposableEffect(vararg keys: Any?, block: Disposal.() -> Unit) { Disposal().block() }
class Lifecycle {
    enum class Event { ON_RESUME, ON_PAUSE }
    val observers = mutableListOf<LifecycleEventObserver>()
    fun addObserver(observer: LifecycleEventObserver) { observers.add(observer) }
    fun removeObserver(observer: LifecycleEventObserver) { observers.remove(observer) }
}
fun interface LifecycleEventObserver { fun onStateChanged(owner: Owner, event: Lifecycle.Event) }
class Owner { val lifecycle = Lifecycle() }
object RequestedPermission {
    var permission: String = ""
    var enabled: Boolean = false
    var result: (Boolean) -> Unit = {}
}
fun RequestRuntimePermissionOnUse(permission: String, enabled: Boolean, onResult: (Boolean) -> Unit) {
    RequestedPermission.permission = permission; RequestedPermission.enabled = enabled; RequestedPermission.result = onResult
}
object UiText {
    fun displayLocale() = Locale.US
    fun get(resource: Int, fallback: String, vararg arguments: Any?) =
        if (arguments.isEmpty()) fallback else String.format(Locale.US, fallback, *arguments)
}
object R { object string { @@RESOURCE_IDS@@ } }

@@ITEM@@

class Controller(val owner: Owner, val list: () -> List<RecordedCallItem>,
                 val currentPermission: () -> Boolean, val loading: () -> Boolean,
                 val player: () -> MediaPlayer?, val playing: () -> String?,
                 val playback: (RecordedCallItem) -> Unit, val stopPlayback: () -> Unit,
                 val remove: (RecordedCallItem) -> Unit, val add: (RecordedCallItem) -> Unit) {
    fun refresh() = runBlocking { Effects.work() }
    fun names() = list().map { it.file.name }.toTypedArray()
    fun paths() = list().map { it.file.absolutePath }.toTypedArray()
    fun permissionGranted() = currentPermission()
    fun requestedPermission() = RequestedPermission.permission
    fun permissionRequestEnabled() = RequestedPermission.enabled
    fun permissionResult(granted: Boolean) { RequestedPermission.result(granted) }
    fun resume() { owner.lifecycle.observers.toList().forEach { it.onStateChanged(owner, Lifecycle.Event.ON_RESUME) } }
    fun observerCount() = owner.lifecycle.observers.size
    fun dispose() { Effects.disposals.toList().forEach { it() }; Effects.disposals.clear() }
    fun isLoading() = loading()
    fun activePlayer() = player()
    fun playingPath() = playing()
    fun play(item: RecordedCallItem) = playback(item)
    fun stop() = stopPlayback()
    fun delete(item: RecordedCallItem) = remove(item)
    fun addFile(file: File): RecordedCallItem {
        val item = RecordedCallItem(file, file.nameWithoutExtension, "fixture date", "fixture size", "fixture duration")
        add(item)
        return item
    }
}

object CallRecordingsListProbe {
    @JvmStatic fun create(context: Context, tab: String): Controller {
        Effects.work = {}; Effects.keys = emptyList(); Effects.disposals.clear()
        val lifecycleOwner = Owner()
        var selectedTab by remember { mutableStateOf(tab) }
        @@STATE@@
        val recordingsList = mutableListOf<RecordedCallItem>()
        var isLoadingRecordings = false
        var selectedRecordingPaths = emptySet<String>()
        var mediaPlayer: MediaPlayer? = null
        var currentlyPlayingPath: String? = null
        @@STOP@@
        @@AUDIO_DISPOSE@@
        fun playClick(item: RecordedCallItem) {
            val isPlaying = currentlyPlayingPath == item.file.absolutePath
            @@PLAY@@
        }
        fun deleteClick(item: RecordedCallItem) {
            @@DELETE@@
        }
        @@LOAD@@
        @@EFFECT@@
        return Controller(lifecycleOwner, { recordingsList.toList() }, { canReadLegacyRecordings }, { isLoadingRecordings },
            { mediaPlayer }, { currentlyPlayingPath }, ::playClick, ::stopAudio, ::deleteClick, { recordingsList.add(it) })
    }
}
