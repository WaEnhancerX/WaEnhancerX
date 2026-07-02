package com.waenhancer.core.engine;

import android.content.Context;
import android.content.SharedPreferences;
import com.waenhancer.api.contracts.*;
import com.waenhancer.core.compatibility.*;
import com.waenhancer.core.dashboard.WaexDashboardProviderImpl;
import com.waenhancer.core.feature.WaexFeatureRegistryImpl;
import com.waenhancer.core.preferences.WaexPreferenceManagerImpl;
import com.waenhancer.core.licensing.WaexLicenseManagerImpl;
import com.waenhancer.core.search.WaexSearchEngineImpl;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import java.util.Set;
import javax.inject.Singleton;

@Module
@InstallIn(SingletonComponent.class)
public final class CoreModule {

    @Provides
    @Singleton
    public static SharedPreferences provideSharedPreferences(@ApplicationContext Context context) {
        return context.getSharedPreferences("waex_preferences", Context.MODE_PRIVATE);
    }

    @Provides
    @Singleton
    public static WaexPreferenceManager providePreferenceManager(SharedPreferences sharedPreferences) {
        return new WaexPreferenceManagerImpl(sharedPreferences);
    }

    @Provides
    @Singleton
    public static WaexClientDetector provideClientDetector(@ApplicationContext Context context) {
        return new WaexClientDetectorImpl(context);
    }

    @Provides
    @Singleton
    public static WaexClientVersionDetector provideClientVersionDetector(
            @ApplicationContext Context context,
            WaexClientDetector clientDetector) {
        return new WaexClientVersionDetectorImpl(context, clientDetector);
    }

    @Provides
    @Singleton
    public static WaexVersionRegistry provideVersionRegistry() {
        return new WaexVersionRegistryImpl();
    }

    @Provides
    @Singleton
    public static WaexVersionGate provideVersionGate(
            WaexClientDetector clientDetector,
            WaexClientVersionDetector versionDetector,
            WaexVersionRegistry versionRegistry) {
        return new WaexVersionGateImpl(clientDetector, versionDetector, versionRegistry);
    }

    @Provides
    @Singleton
    public static WaexFeatureRegistry provideFeatureRegistry(
            Set<WaexFeature> featuresSet,
            WaexVersionGate versionGate) {
        WaexFeatureRegistry registry = new WaexFeatureRegistryImpl();
        if (versionGate.evaluate() == GateResult.ALLOWED) {
            for (WaexFeature feature : featuresSet) {
                registry.registerFeature(feature);
            }
        }
        return registry;
    }

    @Provides
    @Singleton
    public static WaexCompatibilityProvider provideCompatibilityProvider(
            WaexClientDetector clientDetector,
            WaexVersionGate versionGate,
            Set<WaexHookAdapter> adapters,
            WaexFeatureRegistry featureRegistry) {
        return new WaexCompatibilityProviderImpl(clientDetector, versionGate, adapters, featureRegistry);
    }

    @Provides
    @Singleton
    public static WaexLicenseManager provideLicenseManager(WaexPreferenceManager preferenceManager) {
        return new WaexLicenseManagerImpl(preferenceManager);
    }

    @Provides
    @Singleton
    public static WaexDashboardProvider provideDashboardProvider(WaexFeatureRegistry featureRegistry) {
        return new WaexDashboardProviderImpl(featureRegistry);
    }

    @Provides
    @Singleton
    public static WaexSearchEngine provideSearchEngine(WaexFeatureRegistry featureRegistry) {
        return new WaexSearchEngineImpl(featureRegistry);
    }
}
