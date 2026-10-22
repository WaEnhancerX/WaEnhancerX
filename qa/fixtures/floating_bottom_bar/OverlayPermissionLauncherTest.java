package com.waenhancer.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE)
public class OverlayPermissionLauncherTest {
    private static final String PACKAGE = "com.waenhancer.app";

    @Test public void supportedOverlayScreenReceivesCurrentPackage() {
        List<Intent> intents = new ArrayList<>();
        assertTrue(OverlayPermissionLauncher.openOverlay(PACKAGE, intents::add));
        assertEquals(1, intents.size());
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intents.get(0).getAction());
        assertEquals("package:" + PACKAGE, intents.get(0).getDataString());
    }

    @Test public void missingOverlayActivityFallsBackToAppInfo() {
        assertFallback(false);
    }

    @Test public void restrictedOverlayActivityFallsBackToAppInfo() {
        assertFallback(true);
    }

    @Test public void unavailableFallbackReturnsFalseAfterTwoAttempts() {
        List<Intent> intents = new ArrayList<>();
        assertFalse(OverlayPermissionLauncher.openOverlay(PACKAGE, intent -> {
            intents.add(intent);
            throw new ActivityNotFoundException();
        }));
        assertEquals(2, intents.size());
    }

    @Test public void explicitAppInfoUsesOnlyTheAppDetailsAction() {
        List<Intent> intents = new ArrayList<>();
        assertTrue(OverlayPermissionLauncher.openAppInfo(PACKAGE, intents::add));
        assertEquals(1, intents.size());
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.get(0).getAction());
        assertEquals("package:" + PACKAGE, intents.get(0).getDataString());
    }

    @Test(expected = IllegalStateException.class)
    public void unrelatedLauncherErrorsAreNotReportedAsUnavailableScreens() {
        OverlayPermissionLauncher.openOverlay(PACKAGE, intent -> { throw new IllegalStateException("launcher lifecycle"); });
    }

    @Test public void openingSettingsDoesNotGrantOverlayAuthorization() {
        boolean before = Settings.canDrawOverlays(RuntimeEnvironment.getApplication());
        assertTrue(OverlayPermissionLauncher.openOverlay(PACKAGE, intent -> {}));
        assertEquals(before, Settings.canDrawOverlays(RuntimeEnvironment.getApplication()));
    }

    private static void assertFallback(boolean denied) {
        List<Intent> intents = new ArrayList<>();
        assertTrue(OverlayPermissionLauncher.openOverlay(PACKAGE, intent -> {
            intents.add(intent);
            if (intents.size() == 1) {
                if (denied) throw new SecurityException();
                throw new ActivityNotFoundException();
            }
        }));
        assertEquals(2, intents.size());
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.get(1).getAction());
        assertEquals("package:" + PACKAGE, intents.get(1).getDataString());
    }
}
