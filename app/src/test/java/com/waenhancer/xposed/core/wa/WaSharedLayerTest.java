package com.waenhancer.xposed.core.wa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.waenhancer.xposed.features.privacy.PlayedReceiptBatch;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class WaSharedLayerTest {

    // ─── WaJid ───────────────────────────────────────────────────────────

    @Test public void rawJidDropsDeviceSuffixes() {
        assertEquals("123@s.whatsapp.net", WaJid.raw("123.0:5@s.whatsapp.net"));
        assertEquals("123@s.whatsapp.net", WaJid.raw("123:7@s.whatsapp.net"));
        assertEquals("456@lid", WaJid.raw("456:2@lid"));
        assertEquals("1-2@g.us", WaJid.raw("1-2@g.us"));
        assertNull(WaJid.raw("not a jid"));
        assertNull(WaJid.raw(null));
    }

    @Test public void rawJidUsesTheGetRawStringContract() {
        assertEquals("789@s.whatsapp.net", WaJid.raw(new FakeJid("789.0:1@s.whatsapp.net")));
        assertTrue(WaJid.isJid(new FakeJid("x@lid")));
        assertFalse(WaJid.isJid("x@lid"));
        assertFalse(WaJid.isJid(new Object()));
    }

    @Test public void jidKinds() {
        assertTrue(WaJid.isGroup("1-2@g.us"));
        assertTrue(WaJid.isStatus("status@broadcast"));
        assertTrue(WaJid.isLid("1@lid"));
        assertTrue(WaJid.isNewsletter("1@newsletter"));
        assertFalse(WaJid.isGroup("1@s.whatsapp.net"));
    }

    public static final class FakeJid {
        private final String raw;
        FakeJid(String raw) { this.raw = raw; }
        public String getRawString() { return raw; }
    }

    // ─── WaMessage key layout ────────────────────────────────────────────

    static final class ValidKey { final Object chat = null; final String id = null; final boolean fromMe = false; }
    static final class TwoStrings { final String a = null; final String b = null; final boolean fromMe = false; final Object c = null; }
    static final class ExtraPrimitive { final Object chat = null; final String id = null; final boolean fromMe = false; final int hash = 0; }
    static final class MissingBoolean { final Object chat = null; final String id = null; }

    @Test public void keyLayoutRequiresExactlyJidIdAndDirection() {
        WaMessage.KeyLayout layout = WaMessage.KeyLayout.of(ValidKey.class);
        assertNotNull(layout);
        assertEquals("id", layout.id.getName());
        assertEquals("fromMe", layout.fromMe.getName());
        assertEquals("chat", layout.remoteJid.getName());
        assertNull(WaMessage.KeyLayout.of(TwoStrings.class));
        assertNull(WaMessage.KeyLayout.of(ExtraPrimitive.class));
        assertNull(WaMessage.KeyLayout.of(MissingBoolean.class));
    }

    @Test public void mediaTypeClassification() {
        assertTrue(WaMessage.isVoiceNoteType(2));
        for (int type : new int[]{42, 43, 82}) assertTrue(WaMessage.isViewOnceType(type));
        assertFalse(WaMessage.isViewOnceType(2));
        assertFalse(WaMessage.isVoiceNoteType(-1));
    }

    // ─── SafeHooks ───────────────────────────────────────────────────────

    public void voidTarget() {}
    public boolean booleanTarget() { return true; }

    @Test public void onlyVoidMethodsMayBeSkipped() throws Exception {
        SafeHooks.requireVoid(getClass().getMethod("voidTarget"));
        Method nonVoid = getClass().getMethod("booleanTarget");
        try {
            SafeHooks.requireVoid(nonVoid);
            fail("a non-void method must be rejected at install time");
        } catch (NoSuchMethodException expected) {
            // WhatsApp changed the signature: the feature fails instead of crashing the host.
        }
    }

    @Test public void failingConditionNeverInterferes() {
        assertFalse(SafeHooks.test(() -> { throw new IllegalStateException("broken read"); }));
        assertTrue(SafeHooks.test(() -> true));
    }

    // ─── PlayedReceiptBatch ──────────────────────────────────────────────

    @Test public void batchUntouchedWhenNothingIsHidden() {
        List<String> hidden = new ArrayList<>();
        assertNull(PlayedReceiptBatch.keep(set("text", "image"), m -> false, hidden::add));
        assertTrue(hidden.isEmpty());
    }

    @Test public void hiddenMessagesAreRemovedOthersStillSent() {
        List<String> hidden = new ArrayList<>();
        Set<String> kept = PlayedReceiptBatch.keep(set("voice", "text", "once"),
                m -> !m.equals("text"), hidden::add);
        assertEquals(set("text"), kept);
        assertEquals(Arrays.asList("voice", "once"), hidden);
    }

    @Test public void fullyHiddenBatchIsEmpty() {
        Set<String> kept = PlayedReceiptBatch.keep(set("voice"), m -> true, m -> {});
        assertNotNull(kept);
        assertTrue(kept.isEmpty());
    }

    @Test public void unreadableMessageIsNeverHeldBack() {
        Set<String> kept = PlayedReceiptBatch.keep(set("bad", "voice"), m -> {
            if (m.equals("bad")) throw new IllegalStateException("unreadable");
            return true;
        }, m -> {});
        assertEquals(set("bad"), kept);
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
