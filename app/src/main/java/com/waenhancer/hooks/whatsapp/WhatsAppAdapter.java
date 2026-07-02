package com.waenhancer.hooks.whatsapp;

import com.waenhancer.api.contracts.ClientType;
import com.waenhancer.api.contracts.WaexCapability;
import com.waenhancer.api.contracts.WaexHookAdapter;

import java.util.EnumSet;
import java.util.Set;

public final class WhatsAppAdapter implements WaexHookAdapter {

    @Override
    public ClientType getSupportedClientType() {
        return ClientType.WHATSAPP;
    }

    @Override
    public Set<WaexCapability> getCapabilities() {
        return EnumSet.of(
                WaexCapability.CHAT_MESSAGES,
                WaexCapability.STATUS,
                WaexCapability.MEDIA,
                WaexCapability.CALLS,
                WaexCapability.CHANNELS,
                WaexCapability.COMMUNITIES
        );
    }

    @Override
    public boolean isCompatible(long versionCode) {
        return versionCode >= 226220200L && versionCode <= 226220300L;
    }

    @Override
    public void initializeHooks() {
        // Empty scaffolding implementation
    }
}
