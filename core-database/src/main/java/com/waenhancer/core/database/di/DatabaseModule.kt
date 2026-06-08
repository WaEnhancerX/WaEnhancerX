package com.waenhancer.core.database.di

import android.content.Context
import androidx.room.Room
import com.waenhancer.core.database.AppDatabase
import com.waenhancer.core.database.dao.WaexToolDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "waex.db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideWaexToolDao(db: AppDatabase): WaexToolDao {
        return db.waexToolDao()
    }
}
