package com.waenhancer.xposed.features.conversation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MessageDeviceSourceParseTest {

    @Test
    public void deviceNumberComesAfterTheColon() {
        assertEquals(2, MessageDeviceSourceHook.parseDeviceIdFromRawString("421900123456:2@s.whatsapp.net", true));
        assertEquals(3, MessageDeviceSourceHook.parseDeviceIdFromRawString("123456789012345:3@lid", false));
        // Older agent form: user.agent:device; the agent must not be taken for the device.
        assertEquals(5, MessageDeviceSourceHook.parseDeviceIdFromRawString("421900123456.0:5@s.whatsapp.net", true));
        assertEquals(0, MessageDeviceSourceHook.parseDeviceIdFromRawString("421900123456:0@s.whatsapp.net", false));
    }

    @Test
    public void noColonMeansPhoneOnlyForADeviceJid() {
        assertEquals(0, MessageDeviceSourceHook.parseDeviceIdFromRawString("421900123456@s.whatsapp.net", true));
        assertEquals(-1, MessageDeviceSourceHook.parseDeviceIdFromRawString("421900123456@s.whatsapp.net", false));
        assertEquals(-1, MessageDeviceSourceHook.parseDeviceIdFromRawString("120363000000000000@g.us", false));
    }

    @Test
    public void malformedValuesAreUnknown() {
        assertEquals(-1, MessageDeviceSourceHook.parseDeviceIdFromRawString(null, true));
        assertEquals(-1, MessageDeviceSourceHook.parseDeviceIdFromRawString("4219:x@s.whatsapp.net", true));
        assertEquals(-1, MessageDeviceSourceHook.parseDeviceIdFromRawString("4219:@s.whatsapp.net", true));
        assertEquals(-1, MessageDeviceSourceHook.parseDeviceIdFromRawString("4219:99999@s.whatsapp.net", true));
        assertEquals(-1, MessageDeviceSourceHook.parseDeviceIdFromRawString("@s.whatsapp.net", true));
    }
}
