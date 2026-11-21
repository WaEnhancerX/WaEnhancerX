package com.waenhancer.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class ExclusivePreferencesTest {
    private static final String MODULE = ExclusivePreferences.MODULE_FLOATING_BAR;
    private static final String NATIVE = ExclusivePreferences.NATIVE_FLOATING_NAVIGATION;

    @Test
    public void partnersAreSymmetricAndOthersHaveNone() {
        assertEquals(NATIVE, ExclusivePreferences.partnerOf(MODULE));
        assertEquals(MODULE, ExclusivePreferences.partnerOf(NATIVE));
        assertNull(ExclusivePreferences.partnerOf("anti_revoke"));
        assertNull(ExclusivePreferences.partnerOf(null));
    }

    @Test
    public void switchingOneOnSwitchesTheOtherOffInEitherDirection() {
        Map<String, Boolean> nativeOn = ExclusivePreferences.expand(Collections.singletonMap(NATIVE, true));
        assertEquals(true, nativeOn.get(NATIVE));
        assertEquals(false, nativeOn.get(MODULE));

        Map<String, Boolean> moduleOn = ExclusivePreferences.expand(Collections.singletonMap(MODULE, true));
        assertEquals(true, moduleOn.get(MODULE));
        assertEquals(false, moduleOn.get(NATIVE));
    }

    @Test
    public void switchingOffAndUnrelatedKeysTouchNothingElse() {
        Map<String, Boolean> off = ExclusivePreferences.expand(Collections.singletonMap(NATIVE, false));
        assertEquals(Collections.singletonMap(NATIVE, false), off);

        Map<String, Boolean> unrelated = ExclusivePreferences.expand(Collections.singletonMap("anti_revoke", true));
        assertEquals(Collections.singletonMap("anti_revoke", true), unrelated);
    }

    @Test
    public void aBatchThatSwitchesBothOnEndsWithOnlyTheLastOn() {
        Map<String, Boolean> batch = new LinkedHashMap<>();
        batch.put(MODULE, true);
        batch.put(NATIVE, true);
        Map<String, Boolean> result = ExclusivePreferences.expand(batch);
        assertEquals(true, result.get(NATIVE));
        assertEquals(false, result.get(MODULE));
    }

    @Test
    public void restoreConflictKeepsTheModuleBarAndClearsTheNativeOne() {
        assertEquals(Collections.singletonMap(MODULE, NATIVE), ExclusivePreferences.clearedOnConflict());
    }
}
