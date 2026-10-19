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
 * @param instagramCookie an Instagram `Cookie` header to read posts with when the user has not
 *   signed in to Instagram in the browser. Instagram serves a signed-out request a page with no
 *   media in it, so without a session no post can be read; the user's own session is always
 *   preferred and this is only the fallback. **It is a session, not an api key**: it belongs to
 *   whichever account made it, it is enough to act as that account, it stops working when that
 *   account signs out or changes its password, and Instagram may lock an account it sees used from
 *   many devices at once. Keep it out of source control, exactly like [twitterApiKey], and expect
 *   to replace it. Sending one is the host app's decision and its responsibility under
 *   Instagram's terms; the module ships none.
 * @param downloadFolderName the folder under the public Download folder that files are saved in.
 * @param notificationIcon the small icon of download notifications; a monochrome drawable.
 * @param performanceMode how hard the module may work the device; [PerformanceMode.Auto] detects it.
 * @param allowYouTube offer downloads from YouTube (its pages and player hosts), which are refused
 *   by default. Google Play removes apps that download from YouTube: keep this `false` in any build
 *   published there.
 * @param allowAdultSites offer downloads from adult sites (the built-in list, their mirrors, and hosts
 *   named after them), which are refused by default. Google Play's sexual-content policy does not
 *   allow apps that do this: keep this `false` in any build published there.
 * @param extraSources readers the host app supplies for links this module does not read itself;
 *   see [MediaSource]. The module's own readers always win, and a blocked site stays blocked.
 * @param guardCarrierProxies put a guard in front of the device's own proxy list for the whole
 *   process, so that a carrier's APN proxy cannot kill the app. Some carriers write a name rather
 *   than a host there - `Non défini `, `mytelnet ` - and OkHttp refuses a hostname with whitespace
 *   in it by throwing, on its own thread, where nothing can catch it. The guard drops only the
 *   names OkHttp would refuse; a request that needed that proxy fails as a failed request instead
 *   of as a dead process. It is a process-wide default, so it covers the host's own clients and
 *   its sdks' too, not only this module's. Turn it off to own that decision yourself - this
 *   module's own two clients carry the same guard either way.
 * @param notificationActivity the screen a download notification opens, with
 *   [MediaDownloader.EXTRA_DOWNLOAD_ID]; the app's launcher activity when `null`. Set it when the
 *   launcher is a splash screen that would not pass the extra on.
 */
data class MediaDownloaderConfig(
    val strictSupportedSitesOnly: Boolean = true,
    val extraBlockedHosts: Set<String> = emptySet(),
    val twitterApiKey: String? = null,
    val instagramCookie: String? = null,
    val downloadFolderName: String = Storage.DEFAULT_ROOT_FOLDER,
    @param:DrawableRes val notificationIcon: Int = android.R.drawable.stat_sys_download,
    val performanceMode: PerformanceMode = PerformanceMode.Auto,
    val notificationActivity: Class<out Activity>? = null,
    val allowYouTube: Boolean = false,
    val allowAdultSites: Boolean = false,
    val extraSources: List<MediaSource> = emptyList(),
    val guardCarrierProxies: Boolean = true,
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
            "instagramCookie=${if (instagramCookie.isNullOrBlank()) "none" else "set"}, " +
            "downloadFolderName=$downloadFolderName, notificationIcon=$notificationIcon, performanceMode=$performanceMode, " +
            "notificationActivity=${notificationActivity?.simpleName}, allowYouTube=$allowYouTube, " +
            "allowAdultSites=$allowAdultSites, extraSources=${extraSources.size})"
}
