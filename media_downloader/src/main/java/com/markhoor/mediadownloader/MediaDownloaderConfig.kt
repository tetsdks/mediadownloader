package com.markhoor.mediadownloader

import android.app.Activity
import androidx.annotation.DrawableRes
import com.markhoor.mediadownloader.core.Constants.Storage
import com.markhoor.mediadownloader.domain.policy.RestrictedCategory

/**
 * How the host app wants the downloader to behave. Passed once to [MediaDownloader.initialize].
 *
 * @param strictSupportedSitesOnly `true` offers downloads only on the built-in supported sites;
 *   `false` offers them on any site where media is found. Blocked sites are refused either way.
 * @param extraBlockedHosts hosts to refuse on top of the built-in block list, e.g. `"example.com"`,
 *   in every configuration - the allow switches below do not lift them.
 * @param twitterApiKey the tweeload api key used to read X/Twitter posts. Keep it out of source
 *   control (e.g. in `local.properties` → `BuildConfig`). Without it X links are not parsed.
 * @param downloadFolderName the folder under the public Download folder that files are saved in.
 * @param notificationIcon the small icon of download notifications; a monochrome drawable.
 * @param performanceMode how hard the module may work the device; [PerformanceMode.Auto] detects it.
 * @param allowYouTube offer downloads from YouTube (its pages and player hosts), which are refused
 *   by default. Google Play removes apps that download from YouTube: keep this `false` in any build
 *   published there.
 * @param allowAdultSites offer downloads from adult sites (the built-in list, their mirrors, and hosts
 *   named after them), which are refused by default. Google Play's sexual-content policy does not
 *   allow apps that do this: keep this `false` in any build published there.
 * @param notificationActivity the screen a download notification opens, with
 *   [MediaDownloader.EXTRA_DOWNLOAD_ID]; the app's launcher activity when `null`. Set it when the
 *   launcher is a splash screen that would not pass the extra on.
 */
data class MediaDownloaderConfig(
    val strictSupportedSitesOnly: Boolean = true,
    val extraBlockedHosts: Set<String> = emptySet(),
    val twitterApiKey: String? = null,
    val downloadFolderName: String = Storage.DEFAULT_ROOT_FOLDER,
    @param:DrawableRes val notificationIcon: Int = android.R.drawable.stat_sys_download,
    val performanceMode: PerformanceMode = PerformanceMode.Auto,
    val notificationActivity: Class<out Activity>? = null,
    val allowYouTube: Boolean = false,
    val allowAdultSites: Boolean = false,
) {
    /** The restricted categories this configuration lifts. */
    internal val allowedRestrictions: Set<RestrictedCategory>
        get() = buildSet {
            if (allowYouTube) add(RestrictedCategory.YouTube)
            if (allowAdultSites) add(RestrictedCategory.Adult)
        }

    override fun toString(): String =
        "MediaDownloaderConfig(strictSupportedSitesOnly=$strictSupportedSitesOnly, " +
            "extraBlockedHosts=$extraBlockedHosts, twitterApiKey=${if (twitterApiKey == null) "none" else "set"}, " +
            "downloadFolderName=$downloadFolderName, notificationIcon=$notificationIcon, performanceMode=$performanceMode, " +
            "notificationActivity=${notificationActivity?.simpleName}, allowYouTube=$allowYouTube, " +
            "allowAdultSites=$allowAdultSites)"
}
