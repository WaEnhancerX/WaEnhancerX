package com.waenhancer.data.di

import com.waenhancer.data.remote.WaexApiService
import com.waenhancer.data.repository.WaexRepositoryImpl
import com.waenhancer.domain.repository.WaexRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindWaexRepository(
        impl: WaexRepositoryImpl
    ): WaexRepository

    companion object {
        @Provides
        @Singleton
        fun provideWaexApiService(retrofit: Retrofit): WaexApiService {
            return retrofit.create(WaexApiService::class.java)
        }
    }
}
