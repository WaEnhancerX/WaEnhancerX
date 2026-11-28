package com.waenhancer.xposed.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HookErrorsTest {

    private final List<String> logged = new ArrayList<>();

    @Before
    public void reset() {
        logged.clear();
        HookErrors.resetForTest(logged::add);
    }

    @Test
    public void logsOnlyFirstOccurrencesPerSiteButCountsAll() {
        for (int i = 0; i < 10; i++) {
            HookErrors.swallowed("AntiRevokeHook", new IllegalStateException("boom"));
        }
        assertEquals(HookErrors.MAX_LOGGED_PER_SITE, logged.size());
        assertEquals(10, HookErrors.count("AntiRevokeHook", IllegalStateException.class));
        assertTrue(logged.get(HookErrors.MAX_LOGGED_PER_SITE - 1).contains("further repeats"));
    }

    @Test
    public void sitesAndExceptionTypesAreTrackedSeparately() {
        HookErrors.swallowed("A", new IllegalStateException("x"));
        HookErrors.swallowed("A", new NullPointerException("y"));
        HookErrors.swallowed("B", new IllegalStateException("z"));

        assertEquals(3, logged.size());
        assertEquals(1, HookErrors.count("A", NullPointerException.class));
        assertEquals(0, HookErrors.count("B", NullPointerException.class));
        assertEquals(3, HookErrors.total());
    }

    @Test
    public void aFailingLoggerNeverPropagatesButStillCounts() {
        HookErrors.resetForTest(line -> {
            throw new IllegalStateException("logger is broken");
        });

        HookErrors.swallowed("MainHook", new IllegalArgumentException("original"));

        assertEquals(1, HookErrors.count("MainHook", IllegalArgumentException.class));
    }

    @Test
    public void logLineNamesSiteAndExceptionWithoutPrivatePayload() {
        IllegalArgumentException error = new IllegalArgumentException("SYNTHETIC_MESSAGE_MARKER");
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                "SYNTHETIC_FRAME_MARKER", "SYNTHETIC_METHOD_MARKER", "SYNTHETIC_FILE_MARKER", 7)});
        HookErrors.swallowed("StatusDownloadHook", error);

        String line = logged.get(0);
        assertTrue(line.startsWith("[WAEX][swallowed] StatusDownloadHook: "));
        assertTrue(line.contains(IllegalArgumentException.class.getName()));
        assertFalse(line.contains("SYNTHETIC_MESSAGE_MARKER"));
        assertFalse(line.contains("SYNTHETIC_FRAME_MARKER"));
        assertFalse(line.contains("SYNTHETIC_METHOD_MARKER"));
        assertFalse(line.contains("SYNTHETIC_FILE_MARKER"));
    }

    @Test
    public void noteIsRateLimitedAndOnlyBuildsItsMessageWhileUnderTheLimit() {
        int[] built = {0};
        for (int i = 0; i < 10; i++) {
            HookErrors.note("FloatingBottomBarHook", "labels", () -> {
                built[0]++;
                return "label group hidden";
            });
        }
        assertEquals(HookErrors.MAX_LOGGED_PER_SITE, logged.size());
        assertEquals(HookErrors.MAX_LOGGED_PER_SITE, built[0]);
        assertTrue(logged.get(0).startsWith("[WAEX][note] FloatingBottomBarHook labels: label group hidden"));
    }

    @Test
    public void notesWithDifferentKeysAreIndependentAndLoggerFailureIsContained() {
        HookErrors.note("S", "a", () -> "one");
        HookErrors.note("S", "b", () -> "two");
        assertEquals(2, logged.size());

        HookErrors.resetForTest(line -> {
            throw new IllegalStateException("logger is broken");
        });
        HookErrors.note("S", "c", () -> "three");
    }
}
