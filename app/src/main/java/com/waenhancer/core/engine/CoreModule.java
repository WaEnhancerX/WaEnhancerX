package com.waenhancer.core.engine;

import com.waenhancer.api.contracts.*;
import com.waenhancer.compatibility.*;
import com.waenhancer.core.dashboard.WaexDashboardProviderImpl;
import com.waenhancer.core.feature.WaexFeatureRegistryImpl;
import com.waenhancer.core.preferences.WaexPreferenceManagerImpl;
import com.waenhancer.core.licensing.WaexLicenseManagerImpl;
import com.waenhancer.core.search.WaexSearchEngineImpl;
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
    public static WaexClientDetector provideClientDetector() {
        return new WaexClientDetectorImpl();
    }

    @Provides
    @Singleton
    public static WaexCapabilityRegistry provideCapabilityRegistry() {
        return new WaexCapabilityRegistryImpl();
    }

    @Provides
    @Singleton
    public static WaexVersionManager provideVersionManager() {
        return new WaexVersionManagerImpl();
    }

    @Provides
    @Singleton
    public static WaexFeatureRegistry provideFeatureRegistry() {
        return new WaexFeatureRegistryImpl();
    }

    @Provides
    @Singleton
    public static WaexCompatibilityProvider provideCompatibilityProvider() {
        return new WaexCompatibilityProviderImpl();
    }

    @Provides
    @Singleton
    public static WaexLicenseManager provideLicenseManager() {
        return new WaexLicenseManagerImpl();
    }

    @Provides
    @Singleton
    public static WaexDashboardProvider provideDashboardProvider() {
        return new WaexDashboardProviderImpl();
    }

    @Provides
    @Singleton
    public static WaexSearchEngine provideSearchEngine() {
        return new WaexSearchEngineImpl();
    }
}
