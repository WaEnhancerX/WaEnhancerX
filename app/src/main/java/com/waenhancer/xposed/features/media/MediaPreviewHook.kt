package com.waenhancer.xposed.features.media

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Request

/** Adds a preview affordance to WhatsApp media controls and opens available media fullscreen. */
class MediaPreviewHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {
    override val name: String get() = "Media Preview"

    override fun hook() {
        if (!isEnabled("media_preview", isEnabled("enable_media_preview", false))) return
        val mediaRowClass = DexSearchEngine.getInstance().findClassWithCache(
            context, classLoader, "wpp_media_bubble_layout_v1"
        ) { bridge, loader ->
            bridge.findClass(FindClass.create().matcher(ClassMatcher.create().addUsingString(
                "BubbleRelativeLayout/ConversationRowText", StringMatchType.Contains
            ))).firstOrNull()?.getInstance(loader)
        }
        if (mediaRowClass == null) {
            XposedBridge.log("[WAEX][MediaPreview] Media bubble class not found")
            return
        }
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? ViewGroup ?: return
                if (!mediaRowClass.isInstance(view)) return
                view.postDelayed({ installIfMediaRow(view) }, 200L)
            }
        })
        XposedBridge.log("[WAEX][MediaPreview] Attachment hook installed")
    }

    private fun installIfMediaRow(root: ViewGroup) {
        if (root.getTag(TAG_INSTALLED) == true) return
        val control = CONTROL_IDS.asSequence().mapNotNull { name ->
            val id = root.resources.getIdentifier(name, "id", root.context.packageName)
            if (id > 0) root.findViewById<View>(id) else null
        }.firstOrNull { it.visibility == View.VISIBLE } ?: return
        val host = control.parent as? ViewGroup ?: return
        if (host.findViewWithTag<View>(BUTTON_TAG) != null) return
        val button = TextView(root.context).apply {
            tag = BUTTON_TAG
            text = "◉"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Preview media"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            setOnClickListener { preview(root) }
        }
        val size = dp(root.context, 40)
        when (host) {
            is FrameLayout -> host.addView(button, FrameLayout.LayoutParams(size, size, Gravity.CENTER).apply {
                topMargin = dp(root.context, 48)
            })
            else -> host.addView(button, ViewGroup.LayoutParams(size, size))
        }
        root.setTag(TAG_INSTALLED, true)
    }

    private fun preview(source: View) {
        val message = resolveMessage(source)
        val file = findMediaFile(message, 6, Collections.newSetFromMap(WeakHashMap()))
        if (file == null || !file.exists()) {
            downloadRemotePreview(source, message)
            return
        }
        showFile(source.context, file)
    }

    private fun resolveMessage(source: View): Any? {
        var current: View? = source
        repeat(10) {
            val view = current ?: return@repeat
            runCatching { XposedHelpers.callMethod(view, "getFMessage") }.getOrNull()?.let { return it }
            val tag = view.tag
            if (tag != null && tag !is String && extractRowId(tag) > 0L) return tag
            current = view.parent as? View
        }
        return findMessageHolder(source)
    }

    private fun downloadRemotePreview(source: View, message: Any?) {
        val rowId = extractRowId(message)
        if (rowId <= 0L) {
            Toast.makeText(source.context, "Unable to resolve this media message", Toast.LENGTH_SHORT).show()
            return
        }
        val loadingDialog = showLoadingDialog(source.context)
        executor.execute {
            try {
                val dbFile = File(source.context.filesDir.parentFile, "databases/msgstore.db")
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    var cursor = db.rawQuery(
                        "SELECT message_url,mime_type,media_key,direct_path FROM message_media WHERE message_row_id=?",
                        arrayOf(rowId.toString())
                    )
                    if (!cursor.moveToFirst()) {
                        cursor.close()
                        val keyId = extractKeyId(message)
                            ?: error("Media metadata was not found for row $rowId")
                        cursor = db.rawQuery(
                            "SELECT mm.message_url,mm.mime_type,mm.media_key,mm.direct_path " +
                                "FROM message_media mm JOIN message m ON m._id=mm.message_row_id " +
                                "WHERE m.key_id=? ORDER BY m._id DESC LIMIT 1",
                            arrayOf(keyId)
                        )
                        if (!cursor.moveToFirst()) {
                            cursor.close()
                            error("Media metadata was not found")
                        }
                    }
                    cursor.use {
                        var url = cursor.getString(0)
                        val mime = cursor.getString(1) ?: "application/octet-stream"
                        val key = cursor.getBlob(2)
                        val directPath = cursor.getString(3)
                        if (url.isNullOrBlank() && !directPath.isNullOrBlank()) {
                            url = if (directPath.startsWith("http")) directPath else "https://mmg.whatsapp.net$directPath"
                        }
                        if (url.isNullOrBlank()) error("Media download URL is unavailable")
                        val request = Request.Builder().url(url).header("User-Agent", "WhatsApp").build()
                        val body = http.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) error("Media server returned ${response.code}")
                            response.body?.bytes() ?: error("Media server returned an empty response")
                        }
                        val clear = if (key != null && key.size == 32) decryptMedia(body, key, mime) else body
                        val extension = when {
                            mime.startsWith("image/") -> ".jpg"
                            mime.startsWith("video/") -> ".mp4"
                            mime.startsWith("audio/") -> ".opus"
                            else -> ".bin"
                        }
                        val output = File.createTempFile("waex_preview_", extension, source.context.cacheDir)
                        FileOutputStream(output).use { it.write(clear) }
                        mainHandler.post {
                            loadingDialog.dismiss()
                            if (source.isAttachedToWindow) showFile(source.context, output)
                            else output.delete()
                        }
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[WAEX][MediaPreview] Preview failed: $t")
                mainHandler.post {
                    loadingDialog.dismiss()
                    Toast.makeText(source.context, t.message ?: "Media preview failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showLoadingDialog(context: Context): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 20))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20).toFloat()
                setColor(0xee202124.toInt())
            }
            addView(ProgressBar(context), LinearLayout.LayoutParams(dp(context, 28), dp(context, 28)))
            addView(TextView(context).apply {
                text = "Loading preview…"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(context, 16), 0, 0, 0)
            })
        }
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        return dialog
    }

    private fun decryptMedia(payload: ByteArray, mediaKey: ByteArray, mime: String): ByteArray {
        if (payload.size <= 10) error("Downloaded media is incomplete")
        val info = when {
            mime.startsWith("image/") -> "WhatsApp Image Keys"
            mime.startsWith("video/") -> "WhatsApp Video Keys"
            mime.startsWith("audio/") -> "WhatsApp Audio Keys"
            else -> "WhatsApp Document Keys"
        }.toByteArray(Charsets.UTF_8)
        val expanded = hkdfSha256(mediaKey, info, 112)
        val iv = expanded.copyOfRange(0, 16)
        val cipherKey = expanded.copyOfRange(16, 48)
        val encrypted = payload.copyOfRange(0, payload.size - 10)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(encrypted)
    }

    private fun hkdfSha256(input: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(input)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val count = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, output, offset, count)
            offset += count
            counter++
        }
        return output
    }

    private fun extractRowId(message: Any?): Long {
        if (message == null) return 0L
        var type: Class<*>? = message.javaClass
        val preferred = listOf("rowId", "_id", "A0m", "A0n")
        for (name in preferred) {
            type = message.javaClass
            while (type != null && type != Any::class.java) {
                try {
                    val field = type.getDeclaredField(name)
                    field.isAccessible = true
                    val value = field.get(message)
                    if (value is Long && value > 1L) return value
                } catch (_: Throwable) {}
                type = type.superclass
            }
        }
        return 0L
    }

    private fun extractKeyId(message: Any?): String? {
        if (message == null) return null
        var type: Class<*>? = message.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive || field.type == String::class.java) continue
                try {
                    field.isAccessible = true
                    val candidate = field.get(message) ?: continue
                    var hasBoolean = false
                    var id: String? = null
                    var nested: Class<*>? = candidate.javaClass
                    while (nested != null && nested != Any::class.java) {
                        for (keyField in nested.declaredFields) {
                            if (Modifier.isStatic(keyField.modifiers)) continue
                            keyField.isAccessible = true
                            if (keyField.type == Boolean::class.javaPrimitiveType) hasBoolean = true
                            if (keyField.type == String::class.java) {
                                val value = keyField.get(candidate) as? String
                                if (!value.isNullOrBlank() && !value.contains('@') && !value.contains('/')) id = value
                            }
                        }
                        nested = nested.superclass
                    }
                    if (hasBoolean && !id.isNullOrBlank()) return id
                } catch (_: Throwable) {}
            }
            type = type.superclass
        }
        return null
    }

    private fun waitForDownload(source: View, message: Any?, attempt: Int) {
        if (!source.isAttachedToWindow || attempt >= 60) {
            if (attempt >= 60) Toast.makeText(source.context, "Preview download timed out", Toast.LENGTH_SHORT).show()
            return
        }
        source.postDelayed({
            val file = findMediaFile(message, 6, Collections.newSetFromMap(WeakHashMap()))
            if (file != null && file.exists() && file.length() > 0L) showFile(source.context, file)
            else waitForDownload(source, message, attempt + 1)
        }, 500L)
    }

    private fun showFile(context: Context, file: File) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            setOnClickListener { /* Media taps never dismiss the explicit-close dialog. */ }
        }
        val close = TextView(context).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Close preview"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            setOnClickListener { dialog.dismiss() }
        }
        var bitmap: android.graphics.Bitmap? = null
        if (file.extension.lowercase() in setOf("mp4", "3gp", "mkv", "webm")) {
            val video = VideoView(context).apply {
                setVideoURI(android.net.Uri.fromFile(file))
            }
            val controls = MediaController(context, false).apply { setAnchorView(video) }
            video.setMediaController(controls)
            video.setOnPreparedListener {
                it.isLooping = false
                video.start()
                controls.show(3_000)
            }
            video.setOnClickListener { controls.show(3_000) }
            container.addView(video, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
            ))
        } else {
            bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null) {
                Toast.makeText(context, "The preview image could not be decoded", Toast.LENGTH_LONG).show()
                if (file.name.startsWith("waex_preview_")) file.delete()
                return
            }
            container.addView(ZoomableImageView(context).apply {
                setImageBitmap(bitmap)
                setBackgroundColor(Color.BLACK)
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
            ))
        }
        container.addView(close, FrameLayout.LayoutParams(dp(context, 44), dp(context, 44), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(context, 18)
            marginEnd = dp(context, 16)
        })
        dialog.setContentView(container)
        val decodedBitmap = bitmap
        dialog.setOnDismissListener {
            decodedBitmap?.recycle()
            if (file.name.startsWith("waex_preview_")) file.delete()
        }
        dialog.show()
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun findMessageHolder(view: View): Any? {
        var current: View? = view
        repeat(6) {
            current?.tag?.let { if (it !is String && extractRowId(it) > 0L) return it }
            current = current?.parent as? View
        }
        return null
    }

    private fun findMediaFile(value: Any?, depth: Int, seen: MutableSet<Any>): File? {
        if (value == null || depth < 0 || !seen.add(value)) return null
        if (value is File && value.isFile) return value
        if (value is String && value.startsWith("/") && value.length < 1024) {
            File(value).takeIf { it.isFile }?.let { return it }
        }
        var type: Class<*>? = value.javaClass
        while (type != null && !type.name.startsWith("java.") && !type.name.startsWith("android.")) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    findMediaFile(field.get(value), depth - 1, seen)?.let { return it }
                } catch (_: Throwable) {}
            }
            type = type.superclass
        }
        return null
    }

    private fun dp(ctx: Context, value: Int) = (value * ctx.resources.displayMetrics.density + .5f).toInt()

    companion object {
        private const val TAG_INSTALLED = 0x7f0f7b20
        private const val BUTTON_TAG = "waex_media_preview"
        private val CONTROL_IDS = listOf(
            "invisible_press_surface", "video_control_frame_view", "control_frame_new",
            "control_frame", "control_frame_view", "mms_control_frame_new", "mms_control_frame"
        )
        private val executor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val http = OkHttpClient()
    }

    private class ZoomableImageView(context: Context) : ImageView(context) {
        private val imageMatrixState = Matrix()
        private val scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val next = (scale * detector.scaleFactor).coerceIn(1f, 5f)
                    val factor = next / scale
                    scale = next
                    imageMatrixState.postScale(factor, factor, detector.focusX, detector.focusY)
                    constrainAndApply()
                    return true
                }
            })
        private var scale = 1f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        private var lastTapAt = 0L

        init {
            scaleType = ScaleType.MATRIX
            isClickable = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            resetImage()
        }

        override fun setImageBitmap(bitmap: android.graphics.Bitmap?) {
            super.setImageBitmap(bitmap)
            if (width > 0 && height > 0) resetImage()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastTapAt < 300L) resetImage()
                    lastTapAt = now
                    lastX = event.x
                    lastY = event.y
                    dragging = true
                }
                MotionEvent.ACTION_MOVE -> if (dragging && !scaleDetector.isInProgress && scale > 1f) {
                    imageMatrixState.postTranslate(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                    constrainAndApply()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }

        private fun resetImage() {
            val drawable = drawable ?: return
            if (width == 0 || height == 0) return
            val dw = drawable.intrinsicWidth.toFloat()
            val dh = drawable.intrinsicHeight.toFloat()
            if (dw <= 0f || dh <= 0f) return
            val fit = minOf(width / dw, height / dh)
            imageMatrixState.reset()
            imageMatrixState.postScale(fit, fit)
            imageMatrixState.postTranslate((width - dw * fit) / 2f, (height - dh * fit) / 2f)
            scale = 1f
            imageMatrix = imageMatrixState
        }

        private fun constrainAndApply() {
            val drawable = drawable ?: return
            val bounds = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
            imageMatrixState.mapRect(bounds)
            val dx = when {
                bounds.width() <= width -> width / 2f - bounds.centerX()
                bounds.left > 0f -> -bounds.left
                bounds.right < width -> width - bounds.right
                else -> 0f
            }
            val dy = when {
                bounds.height() <= height -> height / 2f - bounds.centerY()
                bounds.top > 0f -> -bounds.top
                bounds.bottom < height -> height - bounds.bottom
                else -> 0f
            }
            imageMatrixState.postTranslate(dx, dy)
            imageMatrix = imageMatrixState
        }
    }
}
