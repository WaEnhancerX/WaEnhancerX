package com.waenhancer.licensing.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.licensing.features.CustomEmojisFeature;

import org.junit.Test;

public class CustomEmojisFeatureTest {

    @Test
    public void testFeatureClassPresentInLicensingModule() {
        assertNotNull(CustomEmojisFeature.class);
    }

    @Test
    public void testPreferenceKeysRegisteredInSchema() {
        assertTrue("custom_emojis must be registered in PreferenceSchema",
                PreferenceSchema.isKnown("custom_emojis"));

        assertTrue("custom_emoji_enabled must be registered in PreferenceSchema",
                PreferenceSchema.isKnown("custom_emoji_enabled"));
        PreferenceSchema.Entry enabledEntry = PreferenceSchema.entry("custom_emoji_enabled");
        assertNotNull(enabledEntry);
        assertEquals(PreferenceSchema.Type.BOOLEAN, enabledEntry.type);
        assertEquals(PreferenceSchema.Sensitivity.PUBLIC_SETTING, enabledEntry.sensitivity);

        assertTrue("custom_emoji_font must be registered in PreferenceSchema",
                PreferenceSchema.isKnown("custom_emoji_font"));
        PreferenceSchema.Entry fontEntry = PreferenceSchema.entry("custom_emoji_font");
        assertNotNull(fontEntry);
        assertEquals(PreferenceSchema.Type.STRING, fontEntry.type);
        assertEquals(PreferenceSchema.Sensitivity.PUBLIC_SETTING, fontEntry.sensitivity);
    }

    @Test
    public void testExtractEmojiSequenceFromText() {
        assertEquals("😊", CustomEmojisFeature.extractEmojiSequenceFromText("Hello 😊 world", 6));
        assertEquals("👍🏻", CustomEmojisFeature.extractEmojiSequenceFromText("Thumbs up 👍🏻!", 10));
        assertEquals("👨\u200d👩\u200d👧\u200d👦", CustomEmojisFeature.extractEmojiSequenceFromText("Family 👨\u200d👩\u200d👧\u200d👦 here", 7));
        assertEquals("🇺🇸", CustomEmojisFeature.extractEmojiSequenceFromText("Flag 🇺🇸!", 5));
        assertEquals("1\uFE0F\u20E3", CustomEmojisFeature.extractEmojiSequenceFromText("Number 1\uFE0F\u20E3 test", 7));
    }

    @Test
    public void testExtractCodePointsFromVariousObjects() {
        // Direct int[]
        int[] direct = new int[]{0x1F60A};
        int[] res1 = CustomEmojisFeature.extractCodePoints(direct);
        assertNotNull(res1);
        assertEquals(1, res1.length);
        assertEquals(0x1F60A, res1[0]);

        // CharSequence
        int[] res2 = CustomEmojisFeature.extractCodePoints("😊");
        assertNotNull(res2);
        assertEquals(1, res2.length);
        assertEquals(0x1F60A, res2[0]);

        // Object with int[] field (simulating C169567ci / GP7)
        Object dummyEmojiDescriptor = new Object() {
            public int[] A00 = new int[]{0x1F44D, 0x1F3FB};
        };
        int[] res3 = CustomEmojisFeature.extractCodePoints(dummyEmojiDescriptor);
        assertNotNull(res3);
        assertEquals(2, res3.length);
        assertEquals(0x1F44D, res3[0]);
        assertEquals(0x1F3FB, res3[1]);

        // Object with CharSequence and pos field (simulating C32401bs / AbstractC32391br)
        Object dummyEmojiSeqWrapper = new Object() {
            public int A00 = 6;
            public CharSequence A02 = "Hello 😊 world";
        };
        int[] res4 = CustomEmojisFeature.extractCodePoints(dummyEmojiSeqWrapper);
        assertNotNull(res4);
        assertEquals(1, res4.length);
        assertEquals(0x1F60A, res4[0]);
    }
}

