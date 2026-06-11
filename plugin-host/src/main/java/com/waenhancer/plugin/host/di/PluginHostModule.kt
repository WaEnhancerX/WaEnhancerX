package com.waenhancer.plugin.host.di

import android.content.Context
import com.waenhancer.plugin.host.PluginManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginHostModule {

    @Provides
    @Singleton
    fun providePluginManager(
        @ApplicationContext context: Context
    ): PluginManager {
        return PluginManager(context)
    }
}
