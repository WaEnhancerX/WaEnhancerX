package com.waenhancer.core.engine;

import android.content.Context;
import android.content.SharedPreferences;
import com.waenhancer.api.contracts.*;
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
    public static WaexFeatureRegistry provideFeatureRegistry(java.util.Set<WaexFeature> featuresSet) {
        WaexFeatureRegistry registry = new WaexFeatureRegistryImpl();
        for (WaexFeature feature : featuresSet) {
            registry.registerFeature(feature);
        }
        return registry;
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
