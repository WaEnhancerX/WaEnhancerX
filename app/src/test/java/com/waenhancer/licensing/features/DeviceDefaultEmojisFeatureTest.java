package com.waenhancer.licensing.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.licensing.features.DeviceDefaultEmojisFeature;

import org.junit.Test;

public class DeviceDefaultEmojisFeatureTest {

    @Test
    public void testFeatureClassPresentInLicensingModule() {
        assertNotNull(DeviceDefaultEmojisFeature.class);
    }

    @Test
    public void testPreferenceKeyRegisteredInSchema() {
        assertTrue("disable_defemojis must be registered in PreferenceSchema",
                PreferenceSchema.isKnown("disable_defemojis"));
        PreferenceSchema.Entry entry = PreferenceSchema.entry("disable_defemojis");
        assertNotNull(entry);
        assertEquals(PreferenceSchema.Type.BOOLEAN, entry.type);
        assertEquals(PreferenceSchema.Sensitivity.PUBLIC_SETTING, entry.sensitivity);
    }
}
