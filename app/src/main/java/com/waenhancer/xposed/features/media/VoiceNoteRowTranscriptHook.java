package com.waenhancer.xposed.features.media;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.components.WaexBottomSheet;
import com.waenhancer.xposed.utils.ActivityTracker;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XCallback;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adds a circular CC (closed-caption) button to the side of each voice note row in a WhatsApp
 * conversation — styled exactly like WhatsApp's own forward button. Tapping it opens a
 * transcript sheet via the WAEX transcription provider.
 *
 * <p>The button is injected into the row's outer FrameLayout container (the same view that
 * WhatsApp's forward/action_button lives in for text rows). For voice note rows the parent is
 * identified by the presence of a child with resource-id {@code conversation_row_voice_note}.
 * A single tagged ImageButton per row is reused on recycling.</p>
 */
public final class VoiceNoteRowTranscriptHook extends BaseFeature {
    private static final String TAG = "[WAEX][VoiceRowTranscript]";
    static final String PREF_KEY = "voice_note_transcript_button";

    /** Tag key for the injected CC button on the row's parent FrameLayout. */
    private static final String FIELD_CC_BTN  = "waex_cc_btn";
    /** Tag key on the CC button itself — holds the voice note file path at bind time. */
    private static final String FIELD_FILE    = "waex_stt_file";
    /** Tag key on the CC button — marks whether it was added by us (vs. a recycled non-voice row). */
    private static final String FIELD_INJECTED = "waex_cc_injected";
    private static final int TAG_CC_BTN = 0x57414343;   // 'WACC'
    private static final int TAG_FILE = 0x57414346;     // 'WACF'
    private static final int TAG_LISTENER = 0x5741434C; // 'WACL'

    private static final AtomicBoolean LOOKUP_WARNED = new AtomicBoolean(false);

    /** Message class -> field path to its File; an empty array means "has no file". */
    private static final Map<Class<?>, Field[]> FILE_PATHS = new ConcurrentHashMap<>();
    private static final Field[] NO_PATH = new Field[0];
    /** Path -> time it was last seen on disk; spares a storage call per row while scrolling. */
    private static final Map<String, Long> SEEN_ON_DISK = new ConcurrentHashMap<>();
    private static final long SEEN_ON_DISK_MS = 60_000;

    /** getView methods already hooked. */
    private final java.util.Set<Method> hookedGetViews =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private WeakReference<ListView> listViewRef = new WeakReference<>(null);
    private boolean readyRefreshPending;
    private final AtomicBoolean disabled = new AtomicBoolean(false);

    // Cached resource IDs (looked up once — getIdentifier is slow)
    private static volatile int sVoiceNoteRowId;
    private static volatile int sAudioPlayerId;
    private static volatile int sAudioRowId;
    private static volatile int sMainLayoutId;
    private static volatile int sActionButtonId;

    // Button size / margin in dp
    private static final int BTN_SIZE_DP = 36;
    private static final int BTN_MARGIN_DP = 8;

    public VoiceNoteRowTranscriptHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                                      @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull @Override public String getName() { return "Voice Note Transcript Button"; }

    @Override
    public void hook() {
        VoiceTranscriptionBridge.attach(context);
        XposedHelpers.findAndHookMethod(ListView.class, "setAdapter", ListAdapter.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                safe(() -> onSetAdapter((ListView) param.thisObject, (ListAdapter) param.args[0])).run();
            }
        });
    }

    private void onSetAdapter(ListView listView, @Nullable ListAdapter adapter) {
        Activity activity = ActivityTracker.getCurrentActivity();
        if (activity == null || !activity.getClass().getSimpleName().contains("Conversation")) return;
        if (adapter instanceof HeaderViewListAdapter) {
            adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
        }
        if (adapter == null || !isEnabled(PREF_KEY, true)) return;
        listViewRef = new WeakReference<>(listView);
        refreshReadiness();

        Method getView = findGetViewMethod(adapter.getClass());
        if (getView == null || !hookedGetViews.add(getView)) return;
        try {
            XposedBridge.hookMethod(getView, new XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof ListAdapter) || disabled.get()) return;
                    safe(() -> decorate((ListAdapter) param.thisObject, (int) param.args[0],
                            param.getResult())).run();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " failed to hook getView: " + t.getMessage());
        }
    }

    @Nullable
    private Method findGetViewMethod(Class<?> adapterClass) {
        try {
            return adapterClass.getMethod("getView", int.class, View.class, ViewGroup.class);
        } catch (Throwable ignored) {}
        try {
            return XposedHelpers.findMethodExactIfExists(adapterClass, "getView",
                    int.class, View.class, ViewGroup.class);
        } catch (Throwable ignored) {}
        try {
            return XposedHelpers.findMethodBestMatch(adapterClass, "getView",
                    int.class, View.class, ViewGroup.class);
        } catch (Throwable ignored) {}
        return null;
    }

    /** Readiness needs IPC; check off the UI thread and redraw rows once it turns true. */
    private void refreshReadiness() {
        if (readyRefreshPending) return;
        readyRefreshPending = true;
        VoiceTranscriptionBridge.worker().post(safe(() -> {
            VoiceTranscriptionBridge.ready(context);
            VoiceTranscriptionBridge.main().post(safe(() -> {
                readyRefreshPending = false;
                ListView list = listViewRef.get();
                if (list != null) list.invalidateViews();
            }));
        }));
    }

    // ─── Row decoration ─────────────────────────────────────────────────

    private void decorate(ListAdapter adapter, int position, Object rendered) {
        if (!(rendered instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) rendered;
        if (!isEnabled(PREF_KEY, true)) {
            removeCcButton(row);
            return;
        }

        // Fast path: if not a voice note row, remove any recycled CC button and exit immediately
        ViewGroup voiceNoteRow = findVoiceNoteRow(row);
        if (voiceNoteRow == null) {
            removeCcButton(row);
            return;
        }

        Object item;
        try { item = adapter.getItem(position); } catch (Throwable t) { item = null; }
        File voiceNote = voiceNoteOf(item);
        if (voiceNote == null) {
            removeCcButton(row);
            return;
        }

        injectOrUpdateCcButton(row, voiceNote);
    }

    /**
     * Finds the voice note or audio message container inside the row.
     * Checks if the row itself has resource-id `conversation_row_voice_note`, or if it contains
     * `conversation_row_audio_player_view` or `conversation_row_audio`.
     */
    @Nullable
    private ViewGroup findVoiceNoteRow(ViewGroup row) {
        int id = sVoiceNoteRowId;
        Context ctx = row.getContext();
        if (id == 0) {
            id = ctx.getResources().getIdentifier("conversation_row_voice_note", "id", ctx.getPackageName());
            if (id != 0) sVoiceNoteRowId = id;
        }
        if (id != 0 && (row.getId() == id || row.findViewById(id) != null)) {
            return row;
        }
        int audioPlayerId = sAudioPlayerId;
        if (audioPlayerId == 0) {
            audioPlayerId = ctx.getResources().getIdentifier("conversation_row_audio_player_view", "id", ctx.getPackageName());
            if (audioPlayerId != 0) sAudioPlayerId = audioPlayerId;
        }
        if (audioPlayerId != 0 && row.findViewById(audioPlayerId) != null) {
            return row;
        }
        int audioRowId = sAudioRowId;
        if (audioRowId == 0) {
            audioRowId = ctx.getResources().getIdentifier("conversation_row_audio", "id", ctx.getPackageName());
            if (audioRowId != 0) sAudioRowId = audioRowId;
        }
        if (audioRowId != 0 && (row.getId() == audioRowId || row.findViewById(audioRowId) != null)) {
            return row;
        }
        return null;
    }

    @Nullable
    private static View findMainLayout(ViewGroup row) {
        int id = sMainLayoutId;
        if (id == 0) {
            Context ctx = row.getContext();
            id = ctx.getResources().getIdentifier("main_layout", "id", ctx.getPackageName());
            if (id != 0) sMainLayoutId = id;
        }
        if (id != 0) {
            View main = row.findViewById(id);
            if (main != null) return main;
        }
        // Fallback: search for child containing conversation_row_audio_player_view
        int audioPlayerId = row.getContext().getResources().getIdentifier("conversation_row_audio_player_view", "id", row.getContext().getPackageName());
        if (audioPlayerId != 0) {
            View player = row.findViewById(audioPlayerId);
            if (player != null) {
                View parent = (View) player.getParent();
                while (parent != null && parent != row) {
                    if (parent.getParent() == row) return parent;
                    parent = (View) parent.getParent();
                }
            }
        }
        return null;
    }

    /** Remove the CC button if it was previously injected into this recycled row. */
    private static void removeCcButton(ViewGroup row) {
        Object existing = row.getTag(TAG_CC_BTN);
        if (existing == null) return;
        if (existing instanceof ImageButton) {
            ImageButton btn = (ImageButton) existing;
            Object listener = btn.getTag(TAG_LISTENER);
            if (listener instanceof View.OnLayoutChangeListener) {
                row.removeOnLayoutChangeListener((View.OnLayoutChangeListener) listener);
            }
            ViewGroup parent = (ViewGroup) btn.getParent();
            if (parent != null) parent.removeView(btn);
            row.setTag(TAG_CC_BTN, null);
        }
    }

    private void injectOrUpdateCcButton(ViewGroup row, File voiceNote) {
        // Reuse an already-injected button (recycled row)
        Object existing = row.getTag(TAG_CC_BTN);
        if (existing instanceof ImageButton) {
            ImageButton btn = (ImageButton) existing;
            btn.setTag(TAG_FILE, voiceNote.getAbsolutePath());
            btn.setVisibility(View.VISIBLE);
            positionCcButton(row, btn);
            return;
        }

        ViewGroup container = findOuterContainer(row);
        if (container == null) container = row; // fallback: inject directly in row

        Context ctx = row.getContext();
        int sizePx = dp(ctx, BTN_SIZE_DP);

        // Circular background: subtle translucent white circle (or dark in dark mode)
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.argb(220, 255, 255, 255));
        // Ripple for press feedback
        GradientDrawable rippleMask = new GradientDrawable();
        rippleMask.setShape(GradientDrawable.OVAL);
        rippleMask.setColor(Color.WHITE);
        android.content.res.ColorStateList rippleColor =
                android.content.res.ColorStateList.valueOf(Color.argb(80, 0, 0, 0));
        android.graphics.drawable.RippleDrawable background =
                new android.graphics.drawable.RippleDrawable(rippleColor, circle, rippleMask);

        // Build the CC icon matching close-caption-svgrepo-com.svg
        CaptionDrawable icon = new CaptionDrawable(dp(ctx, 22), Color.argb(220, 30, 30, 30));

        ImageButton btn = new ImageButton(ctx);
        btn.setBackground(background);
        btn.setImageDrawable(icon);
        btn.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
        btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        btn.setContentDescription("Transcribe voice note");
        btn.setElevation(dp(ctx, 2));

        ViewGroup.LayoutParams lp = new ViewGroup.MarginLayoutParams(sizePx, sizePx);

        btn.setTag(TAG_FILE, voiceNote.getAbsolutePath());

        btn.setOnClickListener(v -> safe(() -> {
            Object path = v.getTag(TAG_FILE);
            if (path instanceof String) showTranscript((String) path);
        }).run());

        container.addView(btn, lp);
        row.setTag(TAG_CC_BTN, btn);

        // Listen for layout changes on the row to guarantee correct side-by-side positioning
        View.OnLayoutChangeListener listener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            positionCcButton(row, btn);
        };
        row.addOnLayoutChangeListener(listener);
        btn.setTag(TAG_LISTENER, listener);

        positionCcButton(row, btn);
    }

    private static void positionCcButton(ViewGroup row, ImageButton btn) {
        View mainLayout = findMainLayout(row);
        int rowWidth = row.getWidth();
        int rowHeight = row.getHeight();
        if (rowWidth <= 0 || rowHeight <= 0) return;

        Context ctx = row.getContext();
        int btnSize = dp(ctx, BTN_SIZE_DP);
        int margin = dp(ctx, BTN_MARGIN_DP);

        int left;
        int top;

        if (mainLayout != null && mainLayout.getWidth() > 0) {
            int mainLeft = mainLayout.getLeft();
            int mainRight = mainLayout.getRight();
            int mainTop = mainLayout.getTop();
            int mainHeight = mainLayout.getHeight();

            top = mainTop + (mainHeight - btnSize) / 2;

            // Check if outgoing (bubble aligned to right edge) or incoming (bubble aligned to left edge)
            boolean isOutgoing = (rowWidth - mainRight) < mainLeft;
            if (isOutgoing) {
                // Outgoing: CC button on the LEFT side of bubble
                left = mainLeft - btnSize - margin;
                if (left < margin) left = margin;
            } else {
                // Incoming: CC button on the RIGHT side of bubble
                left = mainRight + margin;
                if (left + btnSize > rowWidth - margin) {
                    left = rowWidth - btnSize - margin;
                }
            }
        } else {
            // Fallback default position: right-aligned, vertically centered
            left = rowWidth - btnSize - margin;
            top = (rowHeight - btnSize) / 2;
        }

        btn.layout(left, top, left + btnSize, top + btnSize);
    }

    /**
     * Walks up the view tree from {@code row} to find an ancestor FrameLayout that is wider
     * than the row itself — this is the overlay container WhatsApp uses for action buttons.
     */
    @Nullable
    private static ViewGroup findOuterContainer(ViewGroup row) {
        ViewGroup parent = row.getParent() instanceof ViewGroup ? (ViewGroup) row.getParent() : null;
        if (parent instanceof FrameLayout && parent.getWidth() >= row.getWidth()) return parent;
        // Try two levels up
        if (parent != null) {
            ViewGroup grandparent = parent.getParent() instanceof ViewGroup ? (ViewGroup) parent.getParent() : null;
            if (grandparent instanceof FrameLayout) return grandparent;
        }
        return null;
    }

    // ─── Finding the voice note file of a message ───────────────────────

    @Nullable
    static File voiceNoteOf(@Nullable Object message) {
        if (message == null) return null;
        Field[] path = FILE_PATHS.computeIfAbsent(message.getClass(), VoiceNoteRowTranscriptHook::findFilePath);
        if (path.length == 0) return null;
        try {
            Object value = message;
            for (Field field : path) {
                if (value == null) return null;
                value = field.get(value);
            }
            if (!(value instanceof File)) return null;
            File file = (File) value;
            return isAudioFile(file) && existsRecently(file) ? file : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean existsRecently(File file) {
        String path = file.getPath();
        long now = System.currentTimeMillis();
        Long seen = SEEN_ON_DISK.get(path);
        if (seen != null && now - seen < SEEN_ON_DISK_MS) return true;
        if (!file.isFile()) { SEEN_ON_DISK.remove(path); return false; }
        if (SEEN_ON_DISK.size() > 512) SEEN_ON_DISK.clear();
        SEEN_ON_DISK.put(path, now);
        return true;
    }

    static boolean isAudioFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        File parent = file.getParentFile();
        String parentPath = parent == null ? "" : parent.getPath();
        boolean hasAudioExt = name.endsWith(".opus") || name.endsWith(".ogg")
                || name.endsWith(".mp3") || name.endsWith(".m4a")
                || name.endsWith(".aac") || name.endsWith(".wav");
        if (!hasAudioExt) return false;
        return name.startsWith("ptt-") || name.startsWith("aud-")
                || parentPath.contains("Voice Notes")
                || parentPath.contains("Audio");
    }

    private static Field[] findFilePath(Class<?> messageClass) {
        try {
            for (Field outer : instanceFields(messageClass)) {
                if (outer.getType() == File.class) return accessible(outer);
                if (!isHostType(outer.getType())) continue;
                for (Field inner : instanceFields(outer.getType())) {
                    if (inner.getType() == File.class) return accessible(outer, inner);
                }
            }
        } catch (Throwable t) {
            if (LOOKUP_WARNED.compareAndSet(false, true)) {
                XposedBridge.log(TAG + " File lookup failed: " + t.getClass().getSimpleName());
            }
        }
        return NO_PATH;
    }

    private static boolean isHostType(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type.isInterface()) return false;
        String name = type.getName();
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")
                || name.startsWith("androidx.") || name.startsWith("kotlin."));
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) fields.add(field);
            }
        }
        return fields;
    }

    private static Field[] accessible(Field... fields) {
        for (Field field : fields) field.setAccessible(true);
        return fields;
    }

    // ─── Transcript sheet ────────────────────────────────────────────────

    private static final class LocaleOption {
        final String code;
        final String label;
        LocaleOption(String code, String label) {
            this.code = code;
            this.label = label;
        }
    }

    private static final List<LocaleOption> WHATSAPP_LOCALES = new ArrayList<>();
    static {
        WHATSAPP_LOCALES.add(new LocaleOption("", "Auto-detect"));
        WHATSAPP_LOCALES.add(new LocaleOption("af", "Afrikaans"));
        WHATSAPP_LOCALES.add(new LocaleOption("sq", "Albanian (Shqip)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ar", "Arabic (العربية)"));
        WHATSAPP_LOCALES.add(new LocaleOption("az", "Azerbaijani (Azərbaycan)"));
        WHATSAPP_LOCALES.add(new LocaleOption("bn", "Bengali (বাংলা)"));
        WHATSAPP_LOCALES.add(new LocaleOption("bg", "Bulgarian (Български)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ca", "Catalan (Català)"));
        WHATSAPP_LOCALES.add(new LocaleOption("zh", "Chinese (Simplified, 简体中文)"));
        WHATSAPP_LOCALES.add(new LocaleOption("zh-TW", "Chinese (Traditional, 繁體中文)"));
        WHATSAPP_LOCALES.add(new LocaleOption("hr", "Croatian (Hrvatski)"));
        WHATSAPP_LOCALES.add(new LocaleOption("cs", "Czech (Čeština)"));
        WHATSAPP_LOCALES.add(new LocaleOption("da", "Danish (Dansk)"));
        WHATSAPP_LOCALES.add(new LocaleOption("nl", "Dutch (Nederlands)"));
        WHATSAPP_LOCALES.add(new LocaleOption("en", "English"));
        WHATSAPP_LOCALES.add(new LocaleOption("et", "Estonian (Eesti)"));
        WHATSAPP_LOCALES.add(new LocaleOption("tl", "Filipino (Tagalog)"));
        WHATSAPP_LOCALES.add(new LocaleOption("fi", "Finnish (Suomi)"));
        WHATSAPP_LOCALES.add(new LocaleOption("fr", "French (Français)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ka", "Georgian (ქართული)"));
        WHATSAPP_LOCALES.add(new LocaleOption("de", "German (Deutsch)"));
        WHATSAPP_LOCALES.add(new LocaleOption("el", "Greek (Ελληνικά)"));
        WHATSAPP_LOCALES.add(new LocaleOption("gu", "Gujarati (ગુજરાતી)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ha", "Hausa"));
        WHATSAPP_LOCALES.add(new LocaleOption("he", "Hebrew (עברית)"));
        WHATSAPP_LOCALES.add(new LocaleOption("hi", "Hindi (हिन्दी)"));
        WHATSAPP_LOCALES.add(new LocaleOption("hu", "Hungarian (Magyar)"));
        WHATSAPP_LOCALES.add(new LocaleOption("id", "Indonesian (Bahasa Indonesia)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ga", "Irish (Gaeilge)"));
        WHATSAPP_LOCALES.add(new LocaleOption("it", "Italian (Italiano)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ja", "Japanese (日本語)"));
        WHATSAPP_LOCALES.add(new LocaleOption("kn", "Kannada (ಕನ್ನಡ)"));
        WHATSAPP_LOCALES.add(new LocaleOption("kk", "Kazakh (Қазақ тілі)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ko", "Korean (한국어)"));
        WHATSAPP_LOCALES.add(new LocaleOption("lo", "Lao (ລາວ)"));
        WHATSAPP_LOCALES.add(new LocaleOption("lv", "Latvian (Latviešu)"));
        WHATSAPP_LOCALES.add(new LocaleOption("lt", "Lithuanian (Lietuvių)"));
        WHATSAPP_LOCALES.add(new LocaleOption("mk", "Macedonian (Македонски)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ms", "Malay (Bahasa Melayu)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ml", "Malayalam (മലയാളം)"));
        WHATSAPP_LOCALES.add(new LocaleOption("mr", "Marathi (मराठी)"));
        WHATSAPP_LOCALES.add(new LocaleOption("no", "Norwegian (Norsk)"));
        WHATSAPP_LOCALES.add(new LocaleOption("fa", "Persian (فارسی)"));
        WHATSAPP_LOCALES.add(new LocaleOption("pl", "Polish (Polski)"));
        WHATSAPP_LOCALES.add(new LocaleOption("pt", "Portuguese (Português)"));
        WHATSAPP_LOCALES.add(new LocaleOption("pa", "Punjabi (ਪੰਜਾਬੀ)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ro", "Romanian (Română)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ru", "Russian (Русский)"));
        WHATSAPP_LOCALES.add(new LocaleOption("sr", "Serbian (Српски)"));
        WHATSAPP_LOCALES.add(new LocaleOption("sk", "Slovak (Slovenčina)"));
        WHATSAPP_LOCALES.add(new LocaleOption("sl", "Slovenian (Slovenščina)"));
        WHATSAPP_LOCALES.add(new LocaleOption("es", "Spanish (Español)"));
        WHATSAPP_LOCALES.add(new LocaleOption("sw", "Swahili (Kiswahili)"));
        WHATSAPP_LOCALES.add(new LocaleOption("sv", "Swedish (Svenska)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ta", "Tamil (தமிழ்)"));
        WHATSAPP_LOCALES.add(new LocaleOption("te", "Telugu (తెలుగు)"));
        WHATSAPP_LOCALES.add(new LocaleOption("th", "Thai (ไทย)"));
        WHATSAPP_LOCALES.add(new LocaleOption("tr", "Turkish (Türkçe)"));
        WHATSAPP_LOCALES.add(new LocaleOption("uk", "Ukrainian (Українська)"));
        WHATSAPP_LOCALES.add(new LocaleOption("ur", "Urdu (اردو)"));
        WHATSAPP_LOCALES.add(new LocaleOption("uz", "Uzbek (Oʻzbekcha)"));
        WHATSAPP_LOCALES.add(new LocaleOption("vi", "Vietnamese (Tiếng Việt)"));
    }

    private void showTranscript(String path) {
        Activity activity = ActivityTracker.getCurrentActivity();
        if (activity == null || activity.isFinishing()) return;
        File note = new File(path);
        String title = UiText.get(R.string.waex_ui_voice_note_transcript, "Voice note transcript");
        String cached = VoiceTranscriptionBridge.cached(note);
        VoiceTranscriptionBridge.log("CC tapped (" + Math.max(1, note.length() / 1024) + " KB"
                + (cached != null ? ", cached)" : ")"));

        TextView body = new TextView(activity);
        body.setTextIsSelectable(true);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        body.setTextColor(themeTextColor(activity));

        View languageDropdown = buildLanguageSelector(activity, note, body);

        if (cached != null) {
            body.setText(displayText(cached, note));
            showSheet(activity, title, body, note, languageDropdown);
            return;
        }

        body.setText(UiText.get(R.string.waex_ui_transcribing_voice_note, "Transcribing voice note…"));
        showSheet(activity, title, body, note, languageDropdown);
        startTranscription(note, body);
    }

    private void startTranscription(File note, TextView body) {
        VoiceTranscriptionBridge.interactive().post(safe(() -> {
            Boolean ready = VoiceTranscriptionBridge.ready(context);
            if (ready == null || !ready) {
                VoiceTranscriptionBridge.main().post(safe(() ->
                        body.setText(ready == null
                                ? "Could not reach WAEX — is the module active?"
                                : "No transcription provider configured.\nOpen WAEX → Audio & AI to set one up.")));
                return;
            }
            VoiceTranscriptionBridge.Result result = VoiceTranscriptionBridge.transcribe(context, note);
            String shown = result.text != null ? displayText(result.text, note) : null;
            VoiceTranscriptionBridge.main().post(safe(() -> body.setText(shown != null
                    ? shown
                    : UiText.get(R.string.waex_ui_transcription_failed, "Transcription failed")
                            + "\n" + result.error)));
        }));
    }

    private View buildLanguageSelector(Activity activity, File note, TextView body) {
        String currentLang = prefs.getString("transcription_language", "");
        int initialIndex = 0;
        for (int i = 0; i < WHATSAPP_LOCALES.size(); i++) {
            if (WHATSAPP_LOCALES.get(i).code.equalsIgnoreCase(currentLang)) {
                initialIndex = i;
                break;
            }
        }

        android.widget.Spinner spinner = new android.widget.Spinner(activity, android.widget.Spinner.MODE_DROPDOWN);
        List<String> labels = new ArrayList<>();
        for (LocaleOption opt : WHATSAPP_LOCALES) {
            labels.add(opt.label);
        }

        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                activity, android.R.layout.simple_spinner_item, labels) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                view.setTextColor(themeTextColor(activity));
                view.setSingleLine(true);
                view.setEllipsize(android.text.TextUtils.TruncateAt.END);
                int padH = dp(activity, 10);
                int padV = dp(activity, 4);
                view.setPadding(padH, padV, padH, padV);
                return view;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                view.setTextColor(themeTextColor(activity));
                int pad = dp(activity, 14);
                view.setPadding(pad, pad, pad, pad);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(initialIndex, false);

        // Styling the spinner background as a sleek WhatsApp-themed pill button
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(themeBgColor(activity));
        pillBg.setCornerRadius(dp(activity, 16));
        pillBg.setStroke(dp(activity, 1), 0x22808080);
        spinner.setBackground(pillBg);
        int padH = dp(activity, 6);
        int padV = dp(activity, 2);
        spinner.setPadding(padH, padV, padH, padV);

        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean isFirstCall = true;

            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (isFirstCall) {
                    isFirstCall = false;
                    return;
                }
                LocaleOption selected = WHATSAPP_LOCALES.get(position);
                String current = prefs.getString("transcription_language", "");
                if (!selected.code.equals(current)) {
                    prefs.edit().putString("transcription_language", selected.code).apply();
                    VoiceTranscriptionBridge.invalidateCache(note);
                    body.setText(UiText.get(R.string.waex_ui_transcribing_voice_note, "Transcribing voice note…"));
                    startTranscription(note, body);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        return spinner;
    }

    private static int themeBgColor(Activity activity) {
        return isDarkTheme(activity) ? 0xFF202C33 : 0xFFF0F2F5;
    }

    private static boolean isDarkTheme(Context ctx) {
        try {
            int mode = ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void showSheet(Activity activity, String title, TextView body, File note, View topRightView) {
        new WaexBottomSheet(activity)
                .asBottomSheet()
                .setTitle(title)
                .setTopRightView(topRightView)
                .setView(body)
                .setPositiveButton(UiText.get(R.string.waex_ui_copy, "Copy"), (dialog, which) -> {
                    String text = VoiceTranscriptionBridge.cached(note);
                    if (text == null || text.isEmpty()) return;
                    ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                    if (clipboard == null) return;
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, text));
                    Toast.makeText(activity, UiText.get(R.string.waex_ui_transcript_copied,
                            "Transcript copied"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(UiText.get(R.string.waex_ui_close, "Close"),
                        (dialog, which) -> dialog.dismiss())
                .show();
    }

    private Runnable safe(Runnable work) {
        return VoiceTranscriptionBridge.guard(TAG, disabled, work);
    }

    private static String displayText(String text, File note) {
        return text.isEmpty()
                ? UiText.get(R.string.waex_ui_no_speech_detected, "No speech detected.")
                        + " " + VoiceTranscriptionBridge.describeAudio(note)
                : text;
    }

    private static int themeTextColor(Activity activity) {
        TypedArray attrs = activity.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try { return attrs.getColor(0, 0xFF808080); } finally { attrs.recycle(); }
    }

    // ─── Utilities ───────────────────────────────────────────────────────

    private static int dp(Context ctx, int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }

    private static float sp(Context ctx, int sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                ctx.getResources().getDisplayMetrics());
    }
}
