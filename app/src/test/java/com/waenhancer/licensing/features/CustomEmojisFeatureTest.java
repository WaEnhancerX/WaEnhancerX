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
}

