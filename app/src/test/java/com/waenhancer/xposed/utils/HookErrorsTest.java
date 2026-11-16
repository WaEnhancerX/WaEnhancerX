package com.waenhancer.xposed.utils;

import static org.junit.Assert.assertEquals;
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
    public void logLineNamesSiteExceptionAndOriginFrame() {
        HookErrors.swallowed("StatusDownloadHook", new IllegalArgumentException("bad file"));

        String line = logged.get(0);
        assertTrue(line.startsWith("[WAEX][swallowed] StatusDownloadHook: "));
        assertTrue(line.contains("IllegalArgumentException: bad file"));
        assertTrue(line.contains(" at "));
    }
}
