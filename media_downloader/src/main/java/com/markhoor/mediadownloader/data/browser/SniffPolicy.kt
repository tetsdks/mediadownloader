package com.markhoor.mediadownloader.data.browser

import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.isAdvertMediaUrl
import com.markhoor.mediadownloader.core.isDailymotionMetadataLink
import com.markhoor.mediadownloader.core.isDirectMediaUrl
import com.markhoor.mediadownloader.core.isHlsPlaylistUrl
import com.markhoor.mediadownloader.core.isIgnoredStream
import com.markhoor.mediadownloader.core.isImdbVideoPage
import com.markhoor.mediadownloader.core.isRefusedRequest
import com.markhoor.mediadownloader.core.isSiteOf
import com.markhoor.mediadownloader.core.isSniffedSingleMediaPage
import com.markhoor.mediadownloader.core.isTikTokGridPage
import com.markhoor.mediadownloader.core.isTikTokVideoFileUrl
import com.markhoor.mediadownloader.core.isUnderAnyOf
import com.markhoor.mediadownloader.core.isVideoFileUrl
import com.markhoor.mediadownloader.core.normalizedHost
import com.markhoor.mediadownloader.data.scraper.ScraperResolver
import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.domain.usecase.CheckSiteAccessUseCase

/** What a request the page made turned out to be. */
internal sealed interface SniffedRequest {
    data object Ignore : SniffedRequest

    /** A file or stream that can be downloaded as it is. */
    data class Media(val url: String) : SniffedRequest

    /** A link the parser reads better than the request itself (Dailymotion's metadata). */
    data class ParserLink(val url: String) : SniffedRequest
}

/**
 * The rules of the browser: which pages may offer downloads, which script each page gets, which
 * requests are media, and where the host's own button belongs. Pure decisions, no state beyond a
 * memo - these run for every resource a page loads.
 *
 * Every rule starts from [allowsDownloads], so a blocked site never gets a script, a sniffed
 * request or a button, whatever else is true of it.
 */
internal class SniffPolicy(
    private val checkSiteAccess: CheckSiteAccessUseCase,
    private val resolver: ScraperResolver,
) {

    /** One immutable pair behind @Volatile: asked for every resource, from more than one thread. */
    @Volatile
    private var lastAccess: Pair<String, SiteAccess>? = null

    fun siteAccess(pageUrl: String): SiteAccess {
        lastAccess?.let { (url, access) -> if (url == pageUrl) return access }
        return checkSiteAccess(pageUrl).also { lastAccess = pageUrl to it }
    }

    fun allowsDownloads(pageUrl: String): Boolean = siteAccess(pageUrl) == SiteAccess.Allowed

    /** A link one of the parsers can read. */
    fun isParserLink(url: String): Boolean = resolver.scrapersFor(url).isNotEmpty()

    /** The path a card must lead to for the parser to read it on [pageUrl], or "" when any will do. */
    fun postPathFor(pageUrl: String): String {
        val host = pageUrl.normalizedHost() ?: return ""
        return Browser.PARSER_POST_PATHS.entries.firstOrNull { (domain, _) -> host.isUnderAnyOf(setOf(domain)) }
            ?.value.orEmpty()
    }

    /** Sites whose own script draws a button on each post and reports through the bridge. */
    fun hasOwnScript(pageUrl: String): Boolean = pageUrl.isSiteOf(
        "facebook.com", "instagram.com", "x.com", "twitter.com", "threads.net", "threads.com",
    )

    /**
     * The script for [pageUrl], or `null` for none. [resourceUrl] is what triggered the injection:
     * the site scripts skip style sheets and icons, which arrive before anything worth scanning.
     */
    fun scriptFor(pageUrl: String, resourceUrl: String): PageScript? {
        if (!pageUrl.startsWith("http") || !allowsDownloads(pageUrl)) return null
        val worthScanning = listOf(".png", ".gif", ".css", ".ico").none { resourceUrl.contains(it) }
        return when {
            pageUrl.isSiteOf("facebook.com") -> PageScript.Facebook.takeIf { worthScanning }
            pageUrl.isSiteOf("instagram.com") -> PageScript.Instagram.takeIf { worthScanning }
            pageUrl.isSiteOf("x.com", "twitter.com") -> PageScript.Twitter.takeIf { worthScanning }
            pageUrl.isSiteOf("threads.net", "threads.com") -> PageScript.Threads.takeIf { worthScanning }
            pageUrl.isSiteOf("tiktok.com") && !pageUrl.isTikTokGridPage() -> PageScript.TikTok
            else -> PageScript.Generic
        }
    }

    /** The generic script's pages: the host's button then waits for its first answer. */
    fun usesGenericScript(pageUrl: String): Boolean =
        pageUrl.startsWith("http") && allowsDownloads(pageUrl) && !hasOwnScript(pageUrl) &&
            !(pageUrl.isSiteOf("tiktok.com") && !pageUrl.isTikTokGridPage())

    /** The host's own floating button may appear here, once there is media. */
    fun allowsNativeButton(pageUrl: String): Boolean = allowsDownloads(pageUrl) && !hasOwnScript(pageUrl)

    /**
     * One media's own page rather than a feed: there the page's own name and artwork are the
     * media's, and a playing element's source is the download.
     */
    fun pageShowsOneMedia(pageUrl: String): Boolean =
        !pageUrl.isTikTokGridPage() &&
            (isParserLink(pageUrl) || pageUrl.isImdbVideoPage() || isSniffedSite(pageUrl) && pageUrl.isSniffedSingleMediaPage())

    /** A site the parser reads, by host: on its feeds a card's own page is what to hand over. */
    fun isParserSite(pageUrl: String): Boolean =
        pageUrl.normalizedHost()?.isUnderAnyOf(Browser.PARSER_SITE_HOSTS) == true

    /** Whether requests on [pageUrl] are listened to at all; the script sites report for themselves. */
    fun sniffsRequestsOn(pageUrl: String): Boolean = !hasOwnScript(pageUrl)

    /**
     * Whether [requestUrl], made by [pageUrl], may be taken. On a page the parser reads, the parser
     * answers instead - except TikTok's video files, which are the post being watched.
     */
    fun mayTake(requestUrl: String, pageUrl: String): Boolean =
        requestUrl.isNotBlank() && allowsDownloads(pageUrl) && !requestUrl.isRefusedRequest() &&
            (!isParserLink(pageUrl) || requestUrl.isTikTokVideoFileUrl())

    fun classify(requestUrl: String, pageUrl: String): SniffedRequest = when {
        requestUrl.isAdvertMediaUrl() || checkSiteAccess.blocksHostOf(requestUrl) -> SniffedRequest.Ignore
        requestUrl.isHlsPlaylistUrl() ->
            if (requestUrl.isIgnoredStream()) SniffedRequest.Ignore else SniffedRequest.Media(requestUrl)
        requestUrl.isDirectMediaUrl() -> SniffedRequest.Media(requestUrl)
        requestUrl.isVideoFileUrl() -> SniffedRequest.Media(requestUrl)
        // On a video's own page the parser already read it; anywhere else (a feed, an embed) the
        // player's metadata request is the only way to the video.
        requestUrl.isDailymotionMetadataLink() && !pageUrl.contains("/video/") -> SniffedRequest.ParserLink(requestUrl)
        else -> SniffedRequest.Ignore
    }

    private fun isSniffedSite(pageUrl: String): Boolean =
        pageUrl.normalizedHost()?.isUnderAnyOf(Browser.SNIFFED_SITES) == true
}
