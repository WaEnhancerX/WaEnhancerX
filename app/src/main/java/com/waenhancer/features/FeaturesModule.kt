package com.waenhancer.features

import com.waenhancer.api.contracts.WaexFeature
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FeaturesModule {

    @Provides
    @Singleton
    @IntoSet
    fun provideHideSeenFeature(feature: HideSeenFeature): WaexFeature = feature

    @Provides
    @Singleton
    @IntoSet
    fun provideFreezeLastSeenFeature(feature: FreezeLastSeenFeature): WaexFeature = feature

    @Provides
    @Singleton
    @IntoSet
    fun provideFileSizeSpooferFeature(feature: FileSizeSpooferFeature): WaexFeature = feature

    @Provides
    @Singleton
    @IntoSet
    fun provideMessageBomberFeature(feature: MessageBomberFeature): WaexFeature = feature

    @Provides
    @Singleton
    @IntoSet
    fun provideAudioTranscriptionFeature(feature: AudioTranscriptionFeature): WaexFeature = feature

    @Provides
    @Singleton
    @IntoSet
    fun provideCompatibilityModeFeature(feature: CompatibilityModeFeature): WaexFeature = feature
}
