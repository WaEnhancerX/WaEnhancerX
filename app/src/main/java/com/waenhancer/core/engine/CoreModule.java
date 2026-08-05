package com.waenhancer.core.engine;

import com.waenhancer.api.contracts.*;
import com.waenhancer.core.preferences.WaexPreferenceManagerImpl;
import com.waenhancer.core.licensing.WaexLicenseManagerImpl;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;

@Module
@InstallIn(SingletonComponent.class)
public final class CoreModule {

    @Provides
    @Singleton
    public static WaexPreferenceManager providePreferenceManager() {
        return new WaexPreferenceManagerImpl();
    }

    @Provides
    @Singleton
    public static WaexPreferenceRepository providePreferenceRepository(com.waenhancer.core.preferences.WaexPreferenceRepositoryImpl impl) {
        return impl;
    }

    @Provides
    @Singleton
    public static WaexLicenseManager provideLicenseManager() {
        return new WaexLicenseManagerImpl();
    }
}
