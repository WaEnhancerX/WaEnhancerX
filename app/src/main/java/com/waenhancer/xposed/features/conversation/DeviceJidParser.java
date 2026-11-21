package com.waenhancer.xposed.features.conversation;

/** WhatsApp JID parsing without Android or Xposed dependencies, so it is unit-testable. */
final class DeviceJidParser {

    private DeviceJidParser() {}

    /**
     * Device number from a WhatsApp JID string, {@code user[.agent][:device]@server}, e.g.
     * {@code 421900123456:2@s.whatsapp.net} or {@code 123456789012345:3@lid}. Device 0 is the
     * phone. Without ":device" a device JID means the phone; any other JID says nothing.
     */
    static int deviceNumber(String raw, boolean isDeviceJid) {
        if (raw == null) return -1;
        int at = raw.indexOf('@');
        String user = at >= 0 ? raw.substring(0, at) : raw;
        int colon = user.lastIndexOf(':');
        if (colon < 0) return isDeviceJid && !user.isEmpty() ? 0 : -1;
        String device = user.substring(colon + 1);
        if (device.isEmpty() || device.length() > 4) return -1;
        for (int i = 0; i < device.length(); i++) {
            if (!Character.isDigit(device.charAt(i))) return -1;
        }
        return Integer.parseInt(device);
    }
}
