package com.waenhancer.xposed.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HostClassNamesTest {

    private static class ConversationActivity {}

    @Test
    public void matchesSimpleNameFragment() {
        assertTrue(HostClassNames.simpleNameContains(ConversationActivity.class, "Conversation"));
        assertFalse(HostClassNames.simpleNameContains(ConversationActivity.class, "Home"));
    }

    @Test
    public void repeatedCallsAgreeWithGetSimpleName() {
        for (int i = 0; i < 3; i++) {
            assertEquals(ConversationActivity.class.getSimpleName().contains("Activity"),
                    HostClassNames.simpleNameContains(ConversationActivity.class, "Activity"));
        }
    }

    @Test
    public void anonymousAndArrayTypesMatchPlatformBehavior() {
        Object anonymous = new Object() {};
        assertEquals(anonymous.getClass().getSimpleName().contains("Object"),
                HostClassNames.simpleNameContains(anonymous.getClass(), "Object"));
        assertEquals(String[].class.getSimpleName().contains("String"),
                HostClassNames.simpleNameContains(String[].class, "String"));
    }
}
