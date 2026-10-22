package com.waenhancer.xposed.features.customization;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.waenhancer.xposed.utils.ViewClassSelector;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE)
public class ViewClassSelectorTest {
    @Test public void unrelatedAnchorMatchesDoNotBecomeViewTargets() {
        assertNull(ViewClassSelector.uniqueView(Arrays.asList(String.class, Object.class, null)));
    }
    @Test public void oneViewSurvivesNonViewMatchesAndDuplicateResults() {
        assertSame(TextView.class, ViewClassSelector.uniqueView(Arrays.asList(String.class, TextView.class, TextView.class)));
    }
    @Test public void distinctViewsAreRejectedAsAmbiguous() {
        assertThrows(IllegalStateException.class, () -> ViewClassSelector.uniqueView(Arrays.asList(TextView.class, FrameLayout.class)));
    }
    @Test public void parentAndChildMatchesAreStillAmbiguous() {
        assertThrows(IllegalStateException.class, () -> ViewClassSelector.uniqueView(Arrays.asList(View.class, TextView.class)));
    }
    @Test public void missingTargetStaysMissing() {
        assertNull(ViewClassSelector.uniqueView(Collections.emptyList()));
    }
}
