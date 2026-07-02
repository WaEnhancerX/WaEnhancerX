package com.waenhancer.hooks.whatsapp;

import com.waenhancer.api.contracts.WaexHookAdapter;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import dagger.multibindings.IntoSet;
import javax.inject.Singleton;

@Module
@InstallIn(SingletonComponent.class)
public final class HooksModule {

    @Provides
    @Singleton
    @IntoSet
    public static WaexHookAdapter provideWhatsAppAdapter() {
        return new WhatsAppAdapter();
    }

    @Provides
    @Singleton
    @IntoSet
    public static WaexHookAdapter provideWhatsAppBusinessAdapter() {
        return new WhatsAppBusinessAdapter();
    }
}
