package com.waenhancer.xposed.features.media


import com.waenhancer.app.R
import com.waenhancer.utils.UiText
import android.R as AndroidR

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.graphics.drawable.DrawableCompat
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.db.MessageStore
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import com.waenhancer.xposed.utils.HKDFv3
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.graphics.Path

/**
 * MediaPreviewHook provides inline media preview (images & videos) directly on WhatsApp chat bubbles,
 * with full zoomable images, video playback controls, audio mute/unmute, playback speed, and remote decrypted download.
 */
class MediaPreviewHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String get() = "Media Preview"

    @Volatile
    private var activePreview: PreviewSession? = null

    private class PreviewSession(val executor: ThreadPoolExecutor) {
        @Volatile var cancelled = false
        var task: FutureTask<Unit>? = null
        var call: Call? = null
        var temporaryFile: File? = null
        var dialog: Dialog? = null
        var videoView: VideoView? = null
        var mediaPlayer: MediaPlayer? = null
        var speed = 1.0f
        var lastProgressPostAt = 0L
    }

    private fun isCurrent(session: PreviewSession): Boolean =
        activePreview === session && !session.cancelled

    private fun checkCurrent(session: PreviewSession) {
        if (!isCurrent(session) || Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Preview cancelled")
        }
    }

    private fun postPreview(session: PreviewSession, action: () -> Unit) {
        if (!isCurrent(session)) return
        mainHandler.postAtTime({ if (isCurrent(session)) action() }, session, SystemClock.uptimeMillis())
    }

    private fun executePreview(session: PreviewSession, context: Context, action: () -> Unit) {
        val task = FutureTask<Unit> { if (isCurrent(session)) action() }
        try {
            synchronized(session) {
                if (!isCurrent(session)) return
                session.task = task
                // Register and enqueue together so cleanup cannot remove a task
                // before it has entered the bounded queue.
                session.executor.execute(task)
            }
        } catch (e: RejectedExecutionException) {
            handleError(e, session, context)
        }
    }

    override fun hook() {
        if (!isEnabled("media_preview", isEnabled("enable_media_preview", true))) return

        try {
            val videoViewContainerClass = DexSearchEngine.getInstance().findClassWithCache(
                context, classLoader, "wpp_video_view_container"
            ) { bridge, loader ->
                bridge.findClass(FindClass.create().matcher(ClassMatcher.create().addUsingString(
                    "frame_visibility_serial_worker", StringMatchType.Contains
                ))).firstOrNull()?.getInstance(loader)
            }

            if (videoViewContainerClass != null) {
                XposedBridge.hookAllConstructors(videoViewContainerClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.size < 2) return
                        val view = param.thisObject as? View ?: return

                        val invisiblePressSurfaceId = getWppId(view.context, "invisible_press_surface")
                        val videoControlFrameViewId = getWppId(view.context, "video_control_frame_view")
                        var surface: ViewGroup? = if (invisiblePressSurfaceId > 0) view.findViewById(invisiblePressSurfaceId) else null
                        if (surface == null && videoControlFrameViewId > 0) {
                            surface = view.findViewById(videoControlFrameViewId)
                        }

                        if (surface != null && surface.childCount > 0) {
                            handlePressSurface(view, surface)
                        } else {
                            val legacyStubId = getWppId(view.context, "video_control_frame_legacy_stub")
                            if (legacyStubId > 0) {
                                view.findViewById<ViewStub>(legacyStubId)?.setOnInflateListener { _, inflated ->
                                    handlePressSurface(view, inflated)
                                }
                            }
                            val viewStubId = getWppId(view.context, "video_control_frame_view_stub")
                            if (viewStubId > 0) {
                                view.findViewById<ViewStub>(viewStubId)?.setOnInflateListener { _, inflated ->
                                    handlePressSurface(view, inflated)
                                }
                            }
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][MediaPreview] Error hooking videoViewContainer: ${t.javaClass.simpleName}")
        }

        try {
            val imageViewContainerClass = DexSearchEngine.getInstance().findClassWithCache(
                context, classLoader, "wpp_image_view_container"
            ) { bridge, loader ->
                val hdTouchId = getWppId(context, "hd_invisible_touch")
                val controlBtnId = getWppId(context, "control_btn")
                val classes = bridge.findClass(FindClass.create().matcher(
                    ClassMatcher.create().addMethod(
                        MethodMatcher.create()
                            .addUsingNumber(hdTouchId)
                            .addUsingNumber(controlBtnId)
                    )
                ))
                for (c in classes) {
                    val inst = c.getInstance(loader)
                    if (ViewGroup::class.java.isAssignableFrom(inst)) return@findClassWithCache inst
                }
                null
            }

            if (imageViewContainerClass != null) {
                XposedBridge.hookAllConstructors(imageViewContainerClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.size < 2) return
                        val view = param.thisObject as? View ?: return

                        val controlFrameId = getWppId(view.context, "control_frame")
                        val controlFrameNewId = getWppId(view.context, "control_frame_new")
                        var controlFrame: View? = if (controlFrameId > 0) view.findViewById(controlFrameId) else null
                        if (controlFrame == null && controlFrameNewId > 0) {
                            controlFrame = view.findViewById(controlFrameNewId)
                        }

                        if (controlFrame != null) {
                            handleMediaControlFrame(view, controlFrame)
                        } else {
                            val legacyStubId = getWppId(view.context, "control_frame_legacy_stub")
                            if (legacyStubId > 0) {
                                view.findViewById<ViewStub>(legacyStubId)?.setOnInflateListener { _, inflated ->
                                    handleMediaControlFrame(view, inflated)
                                }
                            }
                            val viewStubId = getWppId(view.context, "control_frame_view_stub")
                            if (viewStubId > 0) {
                                view.findViewById<ViewStub>(viewStubId)?.setOnInflateListener { _, inflated ->
                                    handleMediaControlFrame(view, inflated)
                                }
                            }
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][MediaPreview] Error hooking imageViewContainer: ${t.javaClass.simpleName}")
        }

        // Secondary fallback: bubble row attachment hook
        try {
            val mediaRowClass = DexSearchEngine.getInstance().findClassWithCache(
                context, classLoader, "wpp_media_bubble_layout_v1"
            ) { bridge, loader ->
                bridge.findClass(FindClass.create().matcher(ClassMatcher.create().addUsingString(
                    "BubbleRelativeLayout/ConversationRowText", StringMatchType.Contains
                ))).firstOrNull()?.getInstance(loader)
            }
            if (mediaRowClass != null) {
                XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? ViewGroup ?: return
                        if (!mediaRowClass.isInstance(view)) return
                        view.postDelayed({ installAffordanceFallback(view) }, 200L)
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][MediaPreview] Secondary bubble hook error: ${t.javaClass.simpleName}")
        }
    }

    private fun handlePressSurface(mainContainer: View, surface: View) {
        if (surface !is ViewGroup) return
        val context = surface.context
        val controlFrame = surface.getChildAt(0) ?: return
        if (MEDIA_PREVIEW_WRAPPER_TAG == controlFrame.tag) return

        surface.removeViewAt(0)
        val linearLayout = LinearLayout(context).apply {
            tag = MEDIA_PREVIEW_WRAPPER_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        surface.addView(linearLayout, 0)
        linearLayout.addView(controlFrame)

        val prevBtn = createPreviewButton(context, dipToPixels(context, 8f))
        prevBtn.setOnClickListener { startPreview(mainContainer, context) }
        linearLayout.addView(prevBtn)
    }

    private fun handleMediaControlFrame(mainContainer: View, controlFrame: View) {
        val context = mainContainer.context
        val mediaContainerId = getWppId(context, "media_container")
        val mediaContainer = if (mediaContainerId > 0) mainContainer.findViewById<ViewGroup>(mediaContainerId) else null
        if (mediaContainer == null) return

        val currentParent = controlFrame.parent as? ViewGroup
        if (currentParent != null && MEDIA_PREVIEW_WRAPPER_TAG == currentParent.tag) return

        val linearLayout = LinearLayout(context).apply {
            tag = MEDIA_PREVIEW_WRAPPER_TAG
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            orientation = LinearLayout.VERTICAL
        }

        currentParent?.removeView(controlFrame)
        linearLayout.addView(controlFrame)
        mediaContainer.addView(linearLayout)

        val prevBtn = createPreviewButton(context, dipToPixels(context, 8f))
        prevBtn.visibility = controlFrame.visibility
        prevBtn.setOnClickListener { startPreview(mainContainer, context) }
        linearLayout.addView(prevBtn)

        controlFrame.viewTreeObserver.addOnGlobalLayoutListener {
            if (prevBtn.visibility != controlFrame.visibility) {
                prevBtn.visibility = controlFrame.visibility
            }
        }
    }

    private fun installAffordanceFallback(root: ViewGroup) {
        if (root.getTag(TAG_INSTALLED) == true) return
        val control = CONTROL_IDS.asSequence().mapNotNull { name ->
            val id = getWppId(root.context, name)
            if (id > 0) root.findViewById<View>(id) else null
        }.firstOrNull { it.visibility == View.VISIBLE } ?: return

        val host = control.parent as? ViewGroup ?: return
        if (host.findViewWithTag<View>(BUTTON_TAG) != null || host.tag == MEDIA_PREVIEW_WRAPPER_TAG) return

        val prevBtn = createPreviewButton(root.context, dipToPixels(root.context, 8f)).apply {
            tag = BUTTON_TAG
            setOnClickListener { startPreview(root, root.context) }
        }

        when (host) {
            is FrameLayout -> host.addView(prevBtn, FrameLayout.LayoutParams(
                dipToPixels(root.context, 42f),
                dipToPixels(root.context, 32f),
                Gravity.CENTER
            ).apply {
                topMargin = dipToPixels(root.context, 48f)
            })
            else -> host.addView(prevBtn, ViewGroup.LayoutParams(
                dipToPixels(root.context, 42f),
                dipToPixels(root.context, 32f)
            ))
        }
        root.setTag(TAG_INSTALLED, true)
    }

    private fun createEyeDrawable(context: Context): Drawable {
        val size = dipToPixels(context, 24f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dipToPixels(context, 2f).toFloat()
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        val cx = size / 2f
        val cy = size / 2f
        val rx = size * 0.42f
        val ry = size * 0.26f

        // Draw outer eye path
        val path = Path().apply {
            moveTo(cx - rx, cy)
            quadTo(cx, cy - ry * 1.8f, cx + rx, cy)
            quadTo(cx, cy + ry * 1.8f, cx - rx, cy)
            close()
        }
        canvas.drawPath(path, paint)

        // Draw pupil circle
        canvas.drawCircle(cx, cy, size * 0.14f, fillPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun createPreviewButton(context: Context, topMargin: Int): ImageView {
        val drawable = createEyeDrawable(context)
        val padding = dipToPixels(context, 4f)
        val prevBtn = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dipToPixels(context, 42f), dipToPixels(context, 32f)).apply {
                gravity = Gravity.CENTER
                this.topMargin = topMargin
            }
            setImageDrawable(drawable)
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        return prevBtn
    }

    private fun startPreview(messageSource: View, context: Context) {
        try {
            val objMessage = resolveMessageDeep(messageSource)
                ?: resolveFMessageFromKeyTag(messageSource.tag)

            var rowId = extractRowId(objMessage)
            val keyId = extractKeyId(objMessage) ?: findKeyIdInViewHierarchy(messageSource)
            if (!keyId.isNullOrBlank()) {
                val canonicalRowId = MessageStore.getInstance(context).getIdFromKey(keyId)
                if (canonicalRowId > 0L) rowId = canonicalRowId
            }

            val inMemoryMedia = extractMediaInfo(objMessage)

            if (rowId <= 0L && !inMemoryMedia.isUsable()) {
                XposedBridge.log("[WAEX][MediaPreview] Unable to resolve message row/key or in-memory media")
                Toast.makeText(context, UiText.get(R.string.waex_ui_error_finding_message_info_d5e24fb5, "Error finding message info"), Toast.LENGTH_SHORT).show()
                return
            }

            val isNewsletter = isNewsletterSource(objMessage)
            startPlayer(rowId.takeIf { it > 0L }, context, isNewsletter, messageSource, inMemoryMedia)
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][MediaPreview] startPreview error: ${t.javaClass.simpleName}")
            Toast.makeText(context, UiText.get(R.string.waex_ui_error_launching_preview_1_s_e1200bff, "Error launching preview: %1\$s", t.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPlayer(
        id: Long?, context: Context, isNewsletter: Boolean, sourceView: View?,
        inMemoryMedia: MediaInfo
    ) {
        // Entry clicks and dialog callbacks are serialized on the main looper.
        val session = PreviewSession(getPreviewExecutor())
        val previous = activePreview
        activePreview = session
        previous?.let(::closePreview)
        executePreview(session, context) {
            try {
                checkCurrent(session)
                var media = inMemoryMedia
                if (id != null) {
                    try {
                        val query = "SELECT message_url, mime_type, media_key, direct_path, " +
                            "file_length, file_path FROM message_media WHERE message_row_id=?"
                        val db = MessageStore.getInstance(context).database
                        db?.rawQuery(query, arrayOf(id.toString()))?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                media = MediaInfo(
                                    url = cursor.getString(0) ?: media.url,
                                    mimeType = cursor.getString(1) ?: media.mimeType,
                                    mediaKey = normalizeMediaKey(cursor.getBlob(2)) ?: media.mediaKey,
                                    directPath = cursor.getString(3) ?: media.directPath,
                                    fileLength = cursor.getLong(4).takeIf { it > 0L } ?: media.fileLength,
                                    localFile = cursor.getString(5)?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                                        ?: media.localFile
                                )
                            }
                        }
                    } catch (dbError: Throwable) {
                        XposedBridge.log("[WAEX][MediaPreview] Database media lookup failed: ${dbError.javaClass.simpleName}")
                    }
                }

                // Obfuscated row/key fields vary between WhatsApp releases. The CDN URL and
                // direct path are stable identifiers, so use either to recover the canonical
                // message_media row (and its key) when reflection selected no/wrong row.
                if (normalizeMediaKey(media.mediaKey) == null &&
                    (!inMemoryMedia.url.isNullOrBlank() || !inMemoryMedia.directPath.isNullOrBlank())) {
                    try {
                        val db = MessageStore.getInstance(context).database
                        val clauses = ArrayList<String>(2)
                        val args = ArrayList<String>(2)
                        inMemoryMedia.url?.takeIf { it.isNotBlank() }?.let {
                            clauses.add("message_url=?")
                            args.add(it)
                        }
                        inMemoryMedia.directPath?.takeIf { it.isNotBlank() }?.let {
                            clauses.add("direct_path=?")
                            args.add(it)
                        }
                        if (clauses.isNotEmpty()) {
                            val query = "SELECT message_url, mime_type, media_key, direct_path, " +
                                "file_length, file_path FROM message_media WHERE ${clauses.joinToString(" OR ")} " +
                                "ORDER BY message_row_id DESC LIMIT 1"
                            db?.rawQuery(query, args.toTypedArray())?.use { cursor ->
                                if (cursor.moveToFirst()) media = mergeMediaInfo(media, cursor)
                            }
                        }
                    } catch (dbError: Throwable) {
                        XposedBridge.log("[WAEX][MediaPreview] URL media lookup failed: ${dbError.javaClass.simpleName}")
                    }
                }

                checkCurrent(session)
                if (media.isUsable()) {
                    var url = media.url
                    val mimeType = media.mimeType ?: inferMimeType(media.localFile)
                    val mediaKey = normalizeMediaKey(media.mediaKey)
                    val directPath = media.directPath
                    val fileLength = media.fileLength
                    if (isNewsletter && !directPath.isNullOrBlank()) {
                        url = "https://mmg.whatsapp.net$directPath"
                    } else if (url.isNullOrBlank() && !directPath.isNullOrBlank()) {
                        url = if (directPath.startsWith("http")) directPath else "https://mmg.whatsapp.net$directPath"
                    }

                    val finalUrl = url
                    postPreview(session) {
                        try {
                            val dialog = Dialog(context, AndroidR.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                                requestWindowFeature(Window.FEATURE_NO_TITLE)
                                setCancelable(true)
                                window?.let { w ->
                                    w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                                    w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                }
                            }
                            session.dialog = dialog

                            val mainContainer = RelativeLayout(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setBackgroundColor(Color.BLACK)
                            }

                            val header = createHeader(context, mimeType, session)
                            mainContainer.addView(header)

                            val contentContainer = FrameLayout(context).apply {
                                this.id = View.generateViewId()
                                layoutParams = RelativeLayout.LayoutParams(
                                    RelativeLayout.LayoutParams.MATCH_PARENT,
                                    RelativeLayout.LayoutParams.MATCH_PARENT
                                ).apply {
                                    addRule(RelativeLayout.BELOW, header.id)
                                }
                            }
                            mainContainer.addView(contentContainer)

                            val loadingUi = createLoadingView(context, session)
                            val loadingContainer = loadingUi.container
                            contentContainer.addView(loadingContainer)

                            val progressBar = loadingUi.progressBar
                            val progressText = loadingUi.statusText

                            dialog.setContentView(mainContainer)
                            dialog.setOnDismissListener { closePreview(session) }
                            dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(view: View) {}
                                override fun onViewDetachedFromWindow(view: View) { closePreview(session) }
                            })
                            dialog.show()

                            val localFile = media.localFile
                            if (localFile != null) {
                                executePreview(session, context) {
                                    displayLocalMedia(session, localFile, mimeType, context, contentContainer, loadingContainer)
                                }
                            } else {
                                executePreview(session, context) {
                                    downloadAndDisplayMedia(
                                        session,
                                        finalUrl, mediaKey, mimeType, fileLength, isNewsletter, context,
                                        contentContainer, loadingContainer, progressBar, progressText
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            handleError(e, session, context)
                        }
                    }
                } else {
                    postPreview(session) {
                        Toast.makeText(context, UiText.get(R.string.waex_ui_full_quality_media_info_is_unavailable_a93af643, "Full-quality media info is unavailable"), Toast.LENGTH_LONG).show()
                        closePreview(session)
                    }
                }
            } catch (e: Exception) {
                handleError(e, session, context)
            }
        }
    }

    private fun createHeader(context: Context, mimeType: String?, session: PreviewSession): RelativeLayout {
        val header = RelativeLayout(context).apply {
            this.id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                dipToPixels(context, 56f)
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
            }
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dipToPixels(context, 16f), 0, dipToPixels(context, 16f), 0)
        }

        val closeBtn = ImageButton(context).apply {
            this.id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(
                dipToPixels(context, 40f),
                dipToPixels(context, 40f)
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.WHITE)
            setImageResource(AndroidR.drawable.ic_menu_close_clear_cancel)
            setOnClickListener {
                closePreview(session)
            }
        }
        header.addView(closeBtn)

        val title = TextView(context).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT)
            }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            text = if (mimeType != null && mimeType.startsWith("image")) UiText.get(R.string.waex_ui_image_preview_eec6294a, "Image Preview") else UiText.get(R.string.waex_ui_video_preview_95068058, "Video Preview")
        }
        header.addView(title)

        return header
    }

    private data class LoadingUi(
        val container: LinearLayout,
        val progressBar: ProgressBar,
        val statusText: TextView
    )

    private fun createLoadingView(context: Context, session: PreviewSession): LoadingUi {
        val loadingContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dipToPixels(context, 24f), dipToPixels(context, 24f),
                dipToPixels(context, 24f), dipToPixels(context, 24f))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dipToPixels(context, 24f), dipToPixels(context, 22f),
                dipToPixels(context, 24f), dipToPixels(context, 16f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dipToPixels(context, 20f).toFloat()
                setColor(Color.parseColor("#E61F2C30"))
                setStroke(dipToPixels(context, 1f), Color.parseColor("#334A5A5F"))
            }
            elevation = dipToPixels(context, 10f).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dipToPixels(context, 16f)
                rightMargin = dipToPixels(context, 16f)
            }
        }

        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            text = UiText.get(R.string.waex_ui_preparing_preview_21580387, "Preparing preview")
        }
        card.addView(title)

        val progressBar = ProgressBar(context, null, AndroidR.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dipToPixels(context, 6f)
            ).apply {
                topMargin = dipToPixels(context, 20f)
                gravity = Gravity.CENTER
            }
            progressDrawable?.let { drawable ->
                DrawableCompat.setTint(drawable, Color.parseColor("#25D366"))
            }
        }
        card.addView(progressBar)

        val progressText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dipToPixels(context, 12f)
                gravity = Gravity.CENTER
            }
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D9E1E3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = UiText.get(R.string.waex_ui_connecting_0_bf886e36, "Connecting… 0%")
        }
        card.addView(progressText)

        val cancel = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dipToPixels(context, 44f)
            ).apply {
                topMargin = dipToPixels(context, 10f)
                gravity = Gravity.CENTER
            }
            gravity = Gravity.CENTER
            setPadding(dipToPixels(context, 18f), 0, dipToPixels(context, 18f), 0)
            setTextColor(Color.parseColor("#53BDEB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = UiText.get(R.string.action_cancel, "Cancel")
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dipToPixels(context, 22f).toFloat()
                setColor(Color.parseColor("#183B4A54"))
            }
            setOnClickListener {
                closePreview(session)
            }
        }
        card.addView(cancel)
        loadingContainer.addView(card)

        return LoadingUi(loadingContainer, progressBar, progressText)
    }

    private fun downloadAndDisplayMedia(
        session: PreviewSession,
        url: String?, mediaKey: String?, mimeType: String,
        expectedSize: Long, isNewsletter: Boolean, context: Context,
        contentContainer: FrameLayout, loadingContainer: LinearLayout,
        progressBar: ProgressBar, progressText: TextView
    ) {
        var ownedFile: File? = null
        var activeCall: Call? = null
        try {
            checkCurrent(session)
            if (url.isNullOrBlank()) {
                throw Exception("Media URL is unavailable")
            }

            val fileExtension = if (mimeType.startsWith("image")) ".jpg" else ".mp4"
            val file = File.createTempFile("mediapreview_", fileExtension, context.cacheDir)
            ownedFile = file
            synchronized(session) {
                checkCurrent(session)
                session.temporaryFile = file
            }
            session.lastProgressPostAt = 0L

            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .addHeader("User-Agent", "Chrome/117.0.5938.150")
                            .build()
                    )
                }
                .build()

            val request = Request.Builder().url(url).build()
            val call = client.newCall(request)
            activeCall = call
            synchronized(session) {
                if (!isCurrent(session)) {
                    call.cancel()
                    throw InterruptedIOException("Preview cancelled")
                }
                session.call = call
            }
            call.execute().use { response ->
                checkCurrent(session)
                if (!response.isSuccessful || response.body == null) {
                    throw Exception("Failed to download media: HTTP ${response.code}")
                }

                var contentLength = response.body!!.contentLength()
                if (contentLength <= 0L) contentLength = expectedSize

                val inputStream = response.body!!.byteStream()

                if (isNewsletter) {
                    downloadWithProgress(session, inputStream, contentLength, progressBar, progressText, file)
                } else {
                    downloadAndDecryptWithProgress(session, inputStream, contentLength, mediaKey, mimeType, progressBar, progressText, file)
                }

                checkCurrent(session)
                val bitmap = if (mimeType.startsWith("image")) {
                    BitmapFactory.decodeFile(file.absolutePath)
                } else null

                postPreview(session) {
                    loadingContainer.visibility = View.GONE
                    if (mimeType.startsWith("image")) {
                        if (bitmap != null) {
                            displayImage(context, contentContainer, bitmap)
                        } else {
                            Toast.makeText(context, UiText.get(R.string.waex_ui_the_full_quality_image_could_not_be_de_355cd5b2, "The full-quality image could not be decoded"), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        displayVideo(session, context, contentContainer, file)
                    }
                }
            }
        } catch (e: Throwable) {
            handleError(e, session, context)
        } finally {
            synchronized(session) {
                if (session.call === activeCall) session.call = null
            }
            // Cancellation may race file creation/opening. The worker owns this
            // final cleanup even when the dialog already removed the pathname.
            if (session.cancelled) ownedFile?.delete()
        }
    }

    private fun downloadWithProgress(
        session: PreviewSession,
        inputStream: InputStream, contentLength: Long,
        progressBar: ProgressBar, progressText: TextView, file: File
    ) {
        checkCurrent(session)
        FileOutputStream(file).use { fos ->
            val buffer = ByteArray(8192)
            var totalBytesRead = 0L
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                checkCurrent(session)
                fos.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    val progress = ((totalBytesRead * 100) / contentLength).toInt()
                    val sizeInfo = "${formatSize(totalBytesRead)} / ${formatSize(contentLength)}"
                    postDownloadProgress(session, progressBar, progressText, progress, sizeInfo)
                }
            }
        }
        inputStream.close()
    }

    private fun downloadAndDecryptWithProgress(
        session: PreviewSession,
        inputStream: InputStream, contentLength: Long,
        mediaKey: String?, mimeType: String,
        progressBar: ProgressBar, progressText: TextView, file: File
    ) {
        checkCurrent(session)
        val encryptedData = ByteArrayOutputStream().use { baos ->
            val buffer = ByteArray(8192)
            var totalBytesRead = 0L
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                checkCurrent(session)
                baos.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    val progress = ((totalBytesRead * 100) / contentLength).toInt()
                    val sizeInfo = "${formatSize(totalBytesRead)} / ${formatSize(contentLength)}"
                    postDownloadProgress(session, progressBar, progressText, progress, sizeInfo)
                }
            }
            baos.toByteArray()
        }
        inputStream.close()

        checkCurrent(session)
        postPreview(session) { progressText.text = "Decrypting..." }

        val decryptedData = decryptMedia(encryptedData, mediaKey, mimeType)
            ?: throw Exception("Decryption failed")

        checkCurrent(session)
        FileOutputStream(file).use { fos ->
            fos.write(decryptedData)
        }
    }

    private fun postDownloadProgress(
        session: PreviewSession,
        progressBar: ProgressBar, progressText: TextView,
        progress: Int, sizeInfo: String
    ) {
        val now = SystemClock.uptimeMillis()
        if (progress < 100 && now - session.lastProgressPostAt < 100L) return
        session.lastProgressPostAt = now
        postPreview(session) {
            progressBar.progress = progress
            progressText.text = UiText.get(R.string.waex_ui_downloading_1_s_2_s_3186d141, "Downloading… %1\$s%%\n%2\$s", progress, sizeInfo)
        }
    }

    private fun displayImage(context: Context, container: FrameLayout, bitmap: Bitmap) {
        try {
            val imageView = ZoomableImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setImageBitmap(bitmap)
            }
            container.addView(imageView)
        } catch (e: Exception) {
            Toast.makeText(context, UiText.get(R.string.waex_ui_error_displaying_image_1_s_9844d874, "Error displaying image: %1\$s", e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayLocalMedia(
        session: PreviewSession,
        file: File, mimeType: String, context: Context,
        contentContainer: FrameLayout, loadingContainer: LinearLayout
    ) {
        try {
            checkCurrent(session)
            if (!file.isFile || file.length() <= 0L) throw Exception("Downloaded media file is unavailable")
            val bitmap = if (mimeType.startsWith("image")) BitmapFactory.decodeFile(file.absolutePath) else null
            postPreview(session) {
                loadingContainer.visibility = View.GONE
                if (mimeType.startsWith("image")) {
                    if (bitmap != null) displayImage(context, contentContainer, bitmap)
                    else Toast.makeText(context, UiText.get(R.string.waex_ui_the_full_quality_image_could_not_be_de_355cd5b2, "The full-quality image could not be decoded"), Toast.LENGTH_LONG).show()
                } else {
                    displayVideo(session, context, contentContainer, file)
                }
            }
        } catch (t: Throwable) {
            handleError(t, session, context)
        }
    }

    private fun displayVideo(session: PreviewSession, context: Context, container: FrameLayout, file: File) {
        try {
            val videoContainer = RelativeLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            val videoView = VideoView(context).apply {
                this.id = View.generateViewId()
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT)
                }
            }
            session.videoView = videoView

            val controls = createVideoControls(session, context, videoView)
            videoContainer.addView(videoView)
            videoContainer.addView(controls)
            container.addView(videoContainer)

            videoView.setVideoURI(Uri.fromFile(file))
            videoView.setOnPreparedListener { mp ->
                if (!isCurrent(session)) {
                    runCatching { videoView.stopPlayback() }
                    return@setOnPreparedListener
                }
                session.mediaPlayer = mp
                mp.isLooping = false
                val isMuted = prefs.getBoolean("video_preview_muted", false)
                mp.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
                videoView.start()
                updateVideoDuration(controls, mp.duration)
            }

            videoView.setOnCompletionListener {
                if (!isCurrent(session)) return@setOnCompletionListener
                videoView.seekTo(0)
                updatePlayPauseButton(controls, false)
            }

            videoView.setOnErrorListener { _, _, _ ->
                if (isCurrent(session)) {
                    Toast.makeText(context, UiText.get(R.string.waex_ui_error_playing_video_14f39a6f, "Error playing video"), Toast.LENGTH_SHORT).show()
                }
                true
            }
        } catch (e: Exception) {
            Toast.makeText(context, UiText.get(R.string.waex_ui_error_displaying_video_1_s_6a5f9a50, "Error displaying video: %1\$s", e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun createVideoControls(session: PreviewSession, context: Context, videoView: VideoView): LinearLayout {
        session.speed = 1.0f
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(
                dipToPixels(context, 16f),
                dipToPixels(context, 8f),
                dipToPixels(context, 16f),
                dipToPixels(context, 16f)
            )
        }

        val seekBar = SeekBar(context).apply {
            this.id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            max = 100
            progress = 0
        }
        controls.addView(seekBar)

        val buttonsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dipToPixels(context, 8f)
            }
        }

        val playPauseBtn = ImageButton(context).apply {
            this.id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(dipToPixels(context, 48f), dipToPixels(context, 48f))
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(AndroidR.drawable.ic_media_pause)
            tag = "playing"
        }
        buttonsContainer.addView(playPauseBtn)

        val currentTime = TextView(context).apply {
            this.id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dipToPixels(context, 8f)
            }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = "00:00"
        }
        buttonsContainer.addView(currentTime)

        val separator = TextView(context).apply {
            text = " / "
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        buttonsContainer.addView(separator)

        val totalTime = TextView(context).apply {
            this.id = View.generateViewId()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = "00:00"
        }
        buttonsContainer.addView(totalTime)

        val speedBtn = TextView(context).apply {
            text = "1.0x"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dipToPixels(context, 8f), dipToPixels(context, 4f), dipToPixels(context, 8f), dipToPixels(context, 4f))
            background = GradientDrawable().apply {
                cornerRadius = dipToPixels(context, 12f).toFloat()
                setColor(0x66000000)
            }
            layoutParams = LinearLayout.LayoutParams(dipToPixels(context, 44f), dipToPixels(context, 24f)).apply {
                leftMargin = dipToPixels(context, 16f)
            }
        }

        speedBtn.setOnClickListener {
            if (!isCurrent(session)) return@setOnClickListener
            session.speed = when (session.speed) {
                1.0f -> 1.5f
                1.5f -> 2.0f
                2.0f -> 0.5f
                else -> 1.0f
            }
            speedBtn.text = "${session.speed}x"
            session.mediaPlayer?.let { mp ->
                try {
                    val wasPlaying = mp.isPlaying
                    mp.playbackParams = mp.playbackParams.setSpeed(session.speed)
                    if (!wasPlaying) {
                        mp.pause()
                        updatePlayPauseButton(controls, false)
                    } else {
                        updatePlayPauseButton(controls, true)
                    }
                } catch (_: Exception) {}
            }
        }
        buttonsContainer.addView(speedBtn)

        val muteBtn = ImageButton(context).apply {
            this.id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(dipToPixels(context, 40f), dipToPixels(context, 40f)).apply {
                leftMargin = dipToPixels(context, 8f)
            }
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val isMuted = prefs.getBoolean("video_preview_muted", false)
            setImageResource(if (isMuted) AndroidR.drawable.ic_lock_silent_mode else AndroidR.drawable.ic_lock_silent_mode_off)
            tag = if (isMuted) "muted" else "unmuted"
        }

        muteBtn.setOnClickListener {
            if (!isCurrent(session)) return@setOnClickListener
            val currentMuted = "muted" == muteBtn.tag
            val newMuted = !currentMuted
            muteBtn.setImageResource(if (newMuted) AndroidR.drawable.ic_lock_silent_mode else AndroidR.drawable.ic_lock_silent_mode_off)
            muteBtn.tag = if (newMuted) "muted" else "unmuted"
            prefs.edit().putBoolean("video_preview_muted", newMuted).apply()
            session.mediaPlayer?.let { mp ->
                try {
                    mp.setVolume(if (newMuted) 0f else 1f, if (newMuted) 0f else 1f)
                } catch (_: Exception) {}
            }
        }
        buttonsContainer.addView(muteBtn)
        controls.addView(buttonsContainer)

        playPauseBtn.setOnClickListener {
            if (!isCurrent(session)) return@setOnClickListener
            if (videoView.isPlaying) {
                videoView.pause()
                playPauseBtn.setImageResource(AndroidR.drawable.ic_media_play)
                playPauseBtn.tag = "paused"
            } else {
                videoView.start()
                playPauseBtn.setImageResource(AndroidR.drawable.ic_media_pause)
                playPauseBtn.tag = "playing"
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (isCurrent(session) && fromUser && videoView.duration > 0) {
                    val newPosition = (progress * videoView.duration) / 100
                    videoView.seekTo(newPosition)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val updateRunnable = object : Runnable {
            override fun run() {
                if (!isCurrent(session) || session.dialog?.isShowing != true) return
                if (videoView.isPlaying && videoView.duration > 0) {
                    val progress = (videoView.currentPosition * 100) / videoView.duration
                    seekBar.progress = progress
                    currentTime.text = formatTime(videoView.currentPosition)
                }
                mainHandler.postAtTime(this, session, SystemClock.uptimeMillis() + 500L)
            }
        }
        mainHandler.postAtTime(updateRunnable, session, SystemClock.uptimeMillis())

        return controls
    }

    private fun updatePlayPauseButton(controls: LinearLayout, isPlaying: Boolean) {
        val buttonsContainer = controls.getChildAt(1) as LinearLayout
        val playPauseBtn = buttonsContainer.getChildAt(0) as ImageButton
        playPauseBtn.setImageResource(if (isPlaying) AndroidR.drawable.ic_media_pause else AndroidR.drawable.ic_media_play)
        playPauseBtn.tag = if (isPlaying) "playing" else "paused"
    }

    private fun updateVideoDuration(controls: LinearLayout, duration: Int) {
        val buttonsContainer = controls.getChildAt(1) as LinearLayout
        val totalTime = buttonsContainer.getChildAt(3) as TextView
        totalTime.text = formatTime(duration)
    }

    private fun formatTime(milliseconds: Int): String {
        var seconds = milliseconds / 1000
        val minutes = seconds / 60
        seconds %= 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun handleError(e: Throwable, session: PreviewSession, context: Context) {
        if (!isCurrent(session)) return
        val message = if (e is InvocationTargetException) e.cause?.message else e.message
        XposedBridge.log("[WAEX][MediaPreview] Error: ${e.javaClass.simpleName}")
        postPreview(session) {
            Toast.makeText(context, message ?: UiText.get(R.string.waex_ui_unknown_preview_error_8c54371c, "Unknown preview error"), Toast.LENGTH_LONG).show()
            closePreview(session)
        }
    }

    private fun closePreview(session: PreviewSession) {
        val call: Call?
        val task: FutureTask<Unit>?
        val file: File?
        synchronized(session) {
            if (session.cancelled) return
            session.cancelled = true
            call = session.call
            task = session.task
            file = session.temporaryFile
            session.call = null
            session.task = null
            session.temporaryFile = null
        }
        if (activePreview === session) activePreview = null
        call?.cancel()
        task?.cancel(true)
        task?.let { session.executor.remove(it) }
        mainHandler.removeCallbacksAndMessages(session)

        val releaseUi = {
            session.videoView?.let { video ->
                video.setOnPreparedListener(null)
                video.setOnCompletionListener(null)
                video.setOnErrorListener(null)
                // VideoView owns the MediaPlayer; stopPlayback releases it and
                // clears VideoView's internal reference instead of releasing it twice.
                runCatching { video.stopPlayback() }
            }
            session.videoView = null
            session.mediaPlayer = null
            val dialog = session.dialog
            session.dialog = null
            // Activity teardown can detach the window before its dialog sends
            // onDismiss. Cleanup remains safe when that window is already gone.
            runCatching { if (dialog?.isShowing == true) dialog.dismiss() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) releaseUi()
        else mainHandler.post { releaseUi() }
        // Only a file created by this preview can be deleted; local host media
        // never enters temporaryFile. The worker also retries cleanup on cancel.
        file?.delete()
        // Keep the bounded executor alive for later previews in this process.
    }

    private fun decryptMedia(encryptedData: ByteArray, mediaKey: String?, mimeType: String): ByteArray? {
        val normalizedKey = normalizeMediaKey(mediaKey)
        if (normalizedKey == null) {
            XposedBridge.log("[WAEX][MediaPreview] Invalid media key length=${mediaKey?.length ?: 0}")
            throw IllegalArgumentException("Invalid media key.")
        }

        val keyBytes = ByteArray(32)
        for (i in 0 until 64 step 2) {
            keyBytes[i / 2] = ((Character.digit(normalizedKey[i], 16) shl 4) +
                Character.digit(normalizedKey[i + 1], 16)).toByte()
        }

        val typeKey = MEDIA_KEYS[mimeType] ?: (MEDIA_KEYS[mimeType.substringBefore("/")] ?: MEDIA_KEYS["document"]!!)
        val derivedKey = HKDFv3().deriveSecrets(keyBytes, ByteArray(32), typeKey, 112)
        val iv = derivedKey.copyOfRange(0, 16)
        val aesKey = derivedKey.copyOfRange(16, 48)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))

        val dataLength = encryptedData.size - 10
        return cipher.doFinal(encryptedData, 0, dataLength)
    }

    private fun resolveFMessageFromKeyTag(tag: Any?): Any? {
        if (tag == null) return null
        return try {
            val keyClass = tag.javaClass
            val field = keyClass.declaredFields.firstOrNull { it.type.name.contains("FMessage") }
            if (field != null) {
                field.isAccessible = true
                field.get(tag)
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveMessageDeep(source: View): Any? {
        var current: View? = source
        repeat(16) {
            val view = current ?: return@repeat
            runCatching { XposedHelpers.callMethod(view, "getFMessage") }.getOrNull()?.let { return it }
            runCatching { XposedHelpers.callMethod(view, "getMessage") }.getOrNull()?.let { return it }
            val tag = view.tag
            if (tag != null && tag !is String) {
                resolveFMessageFromKeyTag(tag)?.let { return it }
                if (extractRowId(tag) > 0L || extractKeyId(tag) != null) return tag
            }
            current = view.parent as? View
        }
        return null
    }

    private fun findKeyIdInViewHierarchy(source: View): String? {
        var current: View? = source
        repeat(16) {
            val view = current ?: return@repeat
            extractKeyId(view.tag)?.let { return it }
            current = view.parent as? View
        }
        return null
    }

    private fun extractRowId(message: Any?): Long {
        if (message == null) return 0L
        val preferred = listOf("rowId", "_id", "sortId", "A0j", "A0k", "A0l", "A0m", "A0n", "A00", "A01")
        var type: Class<*>? = message.javaClass
        while (type != null && type != Any::class.java) {
            for (name in preferred) {
                try {
                    val field = type.getDeclaredField(name)
                    field.isAccessible = true
                    val value = field.get(message)
                    if (value is Long && value > 0L) return value
                    if (value is Int && value > 0) return value.toLong()
                } catch (_: Throwable) {}
            }
            type = type.superclass
        }
        return 0L
    }

    private fun extractKeyId(message: Any?): String? {
        if (message == null) return null
        extractMessageIdFromKey(message)?.let { direct ->
            if (looksLikeMessageKey(message.javaClass)) return direct
        }
        var type: Class<*>? = message.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive || field.type == String::class.java) continue
                try {
                    field.isAccessible = true
                    val candidate = field.get(message) ?: continue
                    val representation = candidate.toString()
                    if (candidate.javaClass.name.contains("Key", true) ||
                        representation.startsWith("Key(") || looksLikeMessageKey(candidate.javaClass)) {
                        extractMessageIdFromKey(candidate)?.let { return it }
                        KEY_ID_REGEX.find(representation)?.groupValues?.getOrNull(1)?.let { return it }
                    }
                } catch (_: Throwable) {}
            }
            type = type.superclass
        }
        KEY_ID_REGEX.find(message.toString())?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun looksLikeMessageKey(type: Class<*>): Boolean {
        var hasString = false
        var hasBoolean = false
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                if (!Modifier.isStatic(field.modifiers)) {
                    if (field.type == String::class.java) hasString = true
                    if (field.type == Boolean::class.javaPrimitiveType || field.type == Boolean::class.java) hasBoolean = true
                }
            }
            current = current.superclass
        }
        return hasString && hasBoolean
    }

    private fun extractMessageIdFromKey(key: Any): String? {
        var best: String? = null
        var current: Class<*>? = key.javaClass
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type != String::class.java) continue
                runCatching {
                    field.isAccessible = true
                    field.get(key) as? String
                }.getOrNull()?.takeIf { it.length >= 6 && !it.contains('@') && !it.contains('/') }?.let {
                    if (it.length >= 12) return it
                    if (best == null) best = it
                }
            }
            current = current.superclass
        }
        return best
    }

    private fun isNewsletterSource(message: Any?): Boolean {
        if (message == null) return false
        val str = message.toString()
        return str.contains("newsletter", ignoreCase = true)
    }

    private data class MediaInfo(
        val url: String? = null,
        val mimeType: String? = null,
        val mediaKey: String? = null,
        val directPath: String? = null,
        val fileLength: Long = 0L,
        val localFile: File? = null
    ) {
        fun isUsable(): Boolean = localFile?.let { it.isFile && it.length() > 0L } == true ||
            !url.isNullOrBlank() || !directPath.isNullOrBlank()
    }

    private fun mergeMediaInfo(current: MediaInfo, cursor: Cursor): MediaInfo = MediaInfo(
        url = cursor.getString(0) ?: current.url,
        mimeType = cursor.getString(1) ?: current.mimeType,
        mediaKey = normalizeMediaKey(cursor.getBlob(2)) ?: current.mediaKey,
        directPath = cursor.getString(3) ?: current.directPath,
        fileLength = cursor.getLong(4).takeIf { it > 0L } ?: current.fileLength,
        localFile = cursor.getString(5)?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
            ?: current.localFile
    )

    private fun extractMediaInfo(message: Any?): MediaInfo {
        if (message == null) return MediaInfo()
        var url: String? = null
        var mimeType: String? = null
        var mediaKey: String? = null
        var directPath: String? = null
        var fileLength = 0L
        var localFile: File? = null
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun inspect(value: Any?, depth: Int) {
            if (value == null || depth > 3 || !visited.add(value)) return
            if (value is File) {
                if (value.isFile && value.length() > 0L && (localFile == null || value.length() > localFile!!.length())) {
                    localFile = value
                }
                return
            }
            val className = value.javaClass.name
            if (className.startsWith("android.") || className.startsWith("java.util.") ||
                (className.startsWith("java.lang.") && value !is String)) return

            var current: Class<*>? = value.javaClass
            while (current != null && current != Any::class.java) {
                for (field in current.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    val fieldValue = runCatching {
                        field.isAccessible = true
                        field.get(value)
                    }.getOrNull() ?: continue
                    val name = field.name.lowercase(Locale.ROOT)
                    when (fieldValue) {
                        is File -> inspect(fieldValue, depth + 1)
                        is ByteArray -> if (mediaKey == null && fieldValue.size == 32 &&
                            (name.contains("key") || current.simpleName.contains("Media", true))) {
                            mediaKey = normalizeMediaKey(fieldValue)
                        }
                        is String -> when {
                            name.contains("key") && normalizeMediaKey(fieldValue) != null ->
                                if (mediaKey == null) mediaKey = normalizeMediaKey(fieldValue)
                            fieldValue.startsWith("http://") || fieldValue.startsWith("https://") ->
                                if (url == null) url = fieldValue
                            fieldValue.startsWith("/") && (name.contains("direct") || fieldValue.contains("/mms/")) ->
                                if (directPath == null) directPath = fieldValue
                            fieldValue.contains('/') && !fieldValue.contains(' ') &&
                                (name.contains("mime") || fieldValue.startsWith("image/") || fieldValue.startsWith("video/")) ->
                                if (mimeType == null) mimeType = fieldValue
                        }
                        is Long -> if (fieldValue > fileLength && (name.contains("length") || name.contains("size"))) fileLength = fieldValue
                        is Int -> if (fieldValue > fileLength && (name.contains("length") || name.contains("size"))) fileLength = fieldValue.toLong()
                        else -> if (!fieldValue.javaClass.isPrimitive &&
                            !fieldValue.javaClass.name.startsWith("java.") &&
                            (depth == 0 || name.contains("media") || fieldValue.javaClass.name.contains("Media", true))) {
                            inspect(fieldValue, depth + 1)
                        }
                    }
                }
                current = current.superclass
            }
        }

        inspect(message, 0)
        return MediaInfo(url, mimeType, mediaKey, directPath, fileLength, localFile)
    }

    private fun normalizeMediaKey(value: Any?): String? {
        val bytes = when (value) {
            is ByteArray -> if (value.size == 32) value
                else return normalizeMediaKey(value.toString(Charsets.UTF_8))
            is String -> {
                val cleaned = value.trim()
                if (cleaned.length == 64 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    return cleaned.uppercase(Locale.ROOT)
                }
                decodeMediaKeyString(cleaned)
            }
            else -> null
        } ?: return null
        if (bytes.size != 32) return null
        return bytes.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun decodeMediaKeyString(value: String): ByteArray? {
        if (value.isBlank()) return null
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) {
            val decoded = runCatching { Base64.decode(value, flags) }.getOrNull()
            if (decoded?.size == 32) return decoded
        }
        return null
    }

    private fun inferMimeType(file: File?): String {
        val name = file?.name?.lowercase(Locale.ROOT).orEmpty()
        return if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".3gp") ||
            name.endsWith(".webm") || name.endsWith(".mov")) "video/mp4" else "image/jpeg"
    }

    private fun extractBitmapFromView(view: View?): Bitmap? {
        if (view == null) return null
        if (view is ImageView) {
            val d = view.drawable
            if (d is BitmapDrawable && d.bitmap != null && !d.bitmap.isRecycled) {
                return d.bitmap
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = extractBitmapFromView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun showBitmap(context: Context, sourceBitmap: Bitmap) {
        val dialog = Dialog(context, AndroidR.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
        }
        val close = TextView(context).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = UiText.get(R.string.waex_ui_close_preview_7d8ab368, "Close preview")
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            setOnClickListener { dialog.dismiss() }
        }
        val imageView = ZoomableImageView(context).apply {
            setImageBitmap(sourceBitmap)
            setBackgroundColor(Color.BLACK)
        }
        container.addView(imageView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        container.addView(close, FrameLayout.LayoutParams(
            dipToPixels(context, 44f),
            dipToPixels(context, 44f),
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dipToPixels(context, 18f)
            marginEnd = dipToPixels(context, 16f)
        })
        dialog.setContentView(container)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun getWppId(context: Context, name: String): Int {
        return context.resources.getIdentifier(name, "id", context.packageName)
    }

    private fun dipToPixels(context: Context, dip: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dip, context.resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val TAG_INSTALLED = 0x7f0f7b20
        private const val BUTTON_TAG = "waex_media_preview"
        private const val MEDIA_PREVIEW_WRAPPER_TAG = "wpp_media_preview_wrapper"
        private val KEY_ID_REGEX = Regex("(?:messageId|messageID|keyId|id)=([A-Za-z0-9_-]{6,})")

        private val CONTROL_IDS = listOf(
            "invisible_press_surface", "video_control_frame_view", "control_frame_new",
            "control_frame", "control_frame_view", "mms_control_frame_new", "mms_control_frame"
        )

        private val MEDIA_KEYS = hashMapOf(
            "image" to "WhatsApp Image Keys".toByteArray(Charsets.UTF_8),
            "video" to "WhatsApp Video Keys".toByteArray(Charsets.UTF_8),
            "audio" to "WhatsApp Audio Keys".toByteArray(Charsets.UTF_8),
            "document" to "WhatsApp Document Keys".toByteArray(Charsets.UTF_8),
            "image/webp" to "WhatsApp Image Keys".toByteArray(Charsets.UTF_8),
            "image/jpeg" to "WhatsApp Image Keys".toByteArray(Charsets.UTF_8),
            "image/png" to "WhatsApp Image Keys".toByteArray(Charsets.UTF_8),
            "video/mp4" to "WhatsApp Video Keys".toByteArray(Charsets.UTF_8),
            "audio/aac" to "WhatsApp Audio Keys".toByteArray(Charsets.UTF_8),
            "audio/ogg" to "WhatsApp Audio Keys".toByteArray(Charsets.UTF_8),
            "audio/wav" to "WhatsApp Audio Keys".toByteArray(Charsets.UTF_8)
        )

        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile
        private var previewExecutor: ThreadPoolExecutor = createPreviewExecutor()

        private fun createPreviewExecutor(): ThreadPoolExecutor =
            ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), { runnable ->
                Thread(runnable, "WAEX-MediaPreview").apply { isDaemon = true }
            })

        private fun getPreviewExecutor(): ThreadPoolExecutor {
            var executor = previewExecutor
            if (!executor.isShutdown && !executor.isTerminated) return executor
            synchronized(MediaPreviewHook::class.java) {
                executor = previewExecutor
                if (executor.isShutdown || executor.isTerminated) {
                    executor = createPreviewExecutor()
                    previewExecutor = executor
                }
                return executor
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private class ZoomableImageView(context: Context) : ImageView(context) {
        private val matrixInstance = Matrix()
        private val savedMatrix = Matrix()

        private var mode = NONE
        private val lastTouch = PointF()
        private var oldDist = 1f
        private val midPoint = PointF()
        private var minScale = 0.5f
        private var maxScale = 10f
        private var baseScale = 1f
        private var isDoubleTapZoomed = false
        private var lastTapTime = 0L

        init {
            scaleType = ScaleType.MATRIX
            setOnTouchListener { _, event ->
                val action = event.action and MotionEvent.ACTION_MASK
                val pointerCount = event.pointerCount

                if (pointerCount > 1 && parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true)
                }

                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        savedMatrix.set(matrixInstance)
                        lastTouch.set(event.x, event.y)
                        mode = DRAG

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                            handleDoubleTap(event.x, event.y)
                            lastTapTime = 0
                            return@setOnTouchListener true
                        } else {
                            lastTapTime = currentTime
                        }
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> {
                        oldDist = spacing(event)
                        if (oldDist > 10f) {
                            savedMatrix.set(matrixInstance)
                            midPoint(midPoint, event)
                            mode = ZOOM
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                        mode = NONE
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (mode == DRAG) {
                            matrixInstance.set(savedMatrix)
                            val dx = event.x - lastTouch.x
                            val dy = event.y - lastTouch.y

                            val currentScale = getCurrentScale()
                            if (currentScale > baseScale) {
                                matrixInstance.postTranslate(dx, dy)
                                constrainTranslation()
                            }
                        } else if (mode == ZOOM && pointerCount > 1) {
                            val newDist = spacing(event)
                            if (newDist > 10f) {
                                matrixInstance.set(savedMatrix)
                                val scale = newDist / oldDist
                                val currentScale = getCurrentScale()
                                val targetScale = currentScale * scale

                                if (targetScale in minScale..maxScale) {
                                    matrixInstance.postScale(scale, scale, midPoint.x, midPoint.y)
                                    constrainTranslation()
                                }
                            }
                        }
                    }
                }

                imageMatrix = matrixInstance
                true
            }
        }

        private fun getCurrentScale(): Float {
            val values = FloatArray(9)
            matrixInstance.getValues(values)
            return values[Matrix.MSCALE_X]
        }

        private fun handleDoubleTap(x: Float, y: Float) {
            if (isDoubleTapZoomed) {
                resetScale()
                isDoubleTapZoomed = false
            } else {
                matrixInstance.postScale(2.5f, 2.5f, x, y)
                constrainTranslation()
                isDoubleTapZoomed = true
            }
        }

        private fun resetScale() {
            matrixInstance.reset()
            val d = drawable ?: return
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val drawableWidth = d.intrinsicWidth.toFloat()
            val drawableHeight = d.intrinsicHeight.toFloat()

            if (drawableWidth > 0 && drawableHeight > 0) {
                val scaleX = viewWidth / drawableWidth
                val scaleY = viewHeight / drawableHeight
                baseScale = minOf(scaleX, scaleY)
                minScale = baseScale * 0.5f

                matrixInstance.postScale(baseScale, baseScale)
                val reduxX = (viewWidth - drawableWidth * baseScale) / 2f
                val reduxY = (viewHeight - drawableHeight * baseScale) / 2f
                matrixInstance.postTranslate(reduxX, reduxY)
            }
        }

        private fun constrainTranslation() {
            val d = drawable ?: return
            val values = FloatArray(9)
            matrixInstance.getValues(values)
            val transX = values[Matrix.MTRANS_X]
            val transY = values[Matrix.MTRANS_Y]
            val scaleX = values[Matrix.MSCALE_X]
            val scaleY = values[Matrix.MSCALE_Y]

            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val drawableWidth = d.intrinsicWidth * scaleX
            val drawableHeight = d.intrinsicHeight * scaleY

            val minTransX: Float
            val maxTransX: Float
            if (drawableWidth <= viewWidth) {
                minTransX = (viewWidth - drawableWidth) / 2f
                maxTransX = minTransX
            } else {
                minTransX = viewWidth - drawableWidth
                maxTransX = 0f
            }

            val minTransY: Float
            val maxTransY: Float
            if (drawableHeight <= viewHeight) {
                minTransY = (viewHeight - drawableHeight) / 2f
                maxTransY = minTransY
            } else {
                minTransY = viewHeight - drawableHeight
                maxTransY = 0f
            }

            val newTransX = transX.coerceIn(minTransX, maxTransX)
            val newTransY = transY.coerceIn(minTransY, maxTransY)

            values[Matrix.MTRANS_X] = newTransX
            values[Matrix.MTRANS_Y] = newTransY
            matrixInstance.setValues(values)
        }

        private fun spacing(event: MotionEvent): Float {
            val x = event.getX(0) - event.getX(1)
            val y = event.getY(0) - event.getY(1)
            return Math.sqrt((x * x + y * y).toDouble()).toFloat()
        }

        private fun midPoint(point: PointF, event: MotionEvent) {
            val x = event.getX(0) + event.getX(1)
            val y = event.getY(0) + event.getY(1)
            point.set(x / 2, y / 2)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (changed) {
                resetScale()
            }
        }

        companion object {
            private const val DOUBLE_TAP_TIMEOUT = 300L
            private const val NONE = 0
            private const val DRAG = 1
            private const val ZOOM = 2
        }
    }
}
