package com.media.downloader

import android.app.Application
import com.markhoor.mediadownloader.MediaDownloader
import com.markhoor.mediadownloader.MediaDownloaderConfig
import com.markhoor.mediadownloader.PerformanceMode

/**
 * The one thing a host must do. It has to happen here and not in an Activity: WorkManager can start
 * a download in a fresh process - after a reboot, or after Android killed the app - before any
 * screen exists, and the worker needs the module already initialized.
 */
class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        MediaDownloader.initialize(
            this,
            MediaDownloaderConfig(
                // Downloads are offered only on the built-in supported sites. The browser screen's
                // chip shows this changing as you navigate.
                strictSupportedSitesOnly = true,
                twitterApiKey = BuildConfig.TWEELOAD_API_KEY.ifBlank { null },
                // Instagram shows a post to nobody who is not signed in. The user's own session in
                // the browser is always preferred; this stands in when they have none, and comes
                // from local.properties (`instagram.cookie`) so no session is ever committed.
                instagramCookie = BuildConfig.INSTAGRAM_COOKIE.ifBlank { null },
                downloadFolderName = "Media Downloader Demo",
                notificationActivity = MainActivity::class.java,
                performanceMode = PerformanceMode.Auto,
                // Google Play removes apps that download from either. They stay off here.
                allowYouTube = false,
                allowAdultSites = false,
            ),
        )
    }
}
