package com.waenhancer.features

import com.waenhancer.api.contracts.WaexFeature
import dagger.Module
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface FeaturesModule {
    @Multibinds
    fun bindFeatures(): Set<WaexFeature>
}
