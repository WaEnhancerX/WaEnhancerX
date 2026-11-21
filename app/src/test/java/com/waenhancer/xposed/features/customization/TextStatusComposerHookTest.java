package com.waenhancer.xposed.features.customization;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TextStatusComposerHookTest {
    @Test public void acceptsBothWhatsAppParameterOrders() {
        assertTrue(ComposerSignaturePolicy.accepts(
                new String[]{"android.view.View", "android.os.Bundle"}));
        assertTrue(ComposerSignaturePolicy.accepts(
                new String[]{"android.os.Bundle", "android.view.View"}));
        assertFalse(ComposerSignaturePolicy.accepts(
                new String[]{"android.os.Bundle"}));
        assertTrue(ComposerSignaturePolicy.acceptsCreateView(
                "onCreateView", "android.view.View",
                new String[]{"android.view.LayoutInflater", "android.view.ViewGroup", "android.os.Bundle"}));
    }
}
