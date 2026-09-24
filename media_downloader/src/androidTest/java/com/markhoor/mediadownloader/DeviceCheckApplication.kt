package com.markhoor.mediadownloader

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Stands in for a host app in the device check: initializes the module in `onCreate`.
 * `-Pandroid.testInstrumentationRunnerArguments.performanceMode=LowEnd` runs the check as a
 * low-end device would; a process WorkManager starts on its own has no arguments and uses Auto.
 */
class DeviceCheckApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val requested = runCatching { InstrumentationRegistry.getArguments().getString("performanceMode") }.getOrNull()
        val mode = PerformanceMode.entries.firstOrNull { it.name == requested } ?: PerformanceMode.Auto
        MediaDownloader.initialize(this, MediaDownloaderConfig(performanceMode = mode))
    }
}
