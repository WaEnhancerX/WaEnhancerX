package com.waenhancer.hooks.whatsapp;

import com.waenhancer.api.contracts.ClientType;
import com.waenhancer.api.contracts.WaexCapability;
import com.waenhancer.api.contracts.WaexHookAdapter;

import java.util.EnumSet;
import java.util.Set;

public final class WhatsAppBusinessAdapter implements WaexHookAdapter {

    @Override
    public ClientType getSupportedClientType() {
        return ClientType.WHATSAPP_BUSINESS;
    }

    @Override
    public Set<WaexCapability> getCapabilities() {
        return EnumSet.of(
                WaexCapability.CHAT_MESSAGES,
                WaexCapability.STATUS,
                WaexCapability.MEDIA,
                WaexCapability.CALLS,
                WaexCapability.CHANNELS,
                WaexCapability.BUSINESS_TOOLS
        );
    }

    @Override
    public boolean isCompatible(long versionCode) {
        return versionCode >= 226220400L && versionCode <= 226220600L;
    }

    @Override
    public void initializeHooks() {
        // Empty scaffolding implementation
    }
}
