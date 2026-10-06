package com.waenhancer.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WaexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.waenhancer.licensing.features.BootloaderSpooferFeature.syncDefaultKeyboxAsync(this)
    }
}
