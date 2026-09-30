package com.markhoor.mediadownloader.data.scraper.facebook

import com.markhoor.mediadownloader.core.Constants.Facebook
import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.data.network.CookieSource
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * A Facebook video, reel or `share/v`/`share/r` link.
 *
 * A share link names no video id, so its page is read for the canonical url, which gives both the
 * owner and the id; the owner's video page and the reel page are then read side by side, and the
 * owner's page wins when both answer. Any other link carries the id itself.
 *
 * Read as a stranger first, and only then as the reader. A public video is served to a stranger in
 * the shape this reads - `browser_native_hd_url` and its SD twin - while the same page fetched with
 * a session comes back as the signed-in app, which names neither: measured on the device, the same
 * reel gave HD 10.0 MB and SD 2.3 MB read as a stranger and nothing at all read as the reader, so
 * the press fell back to the one stream the player had been heard fetching. The session is
 * therefore the second question, not the first, and it is only asked when there is one and the
 * stranger's read came back empty - which is what a video shared with friends, or posted in a group
 * the reader belongs to, looks like from outside.
 */
internal class FacebookVideoScraper(
    private val fetcher: HttpFetcher,
    private val cookies: CookieSource,
) : SiteScraper() {

    private val videoIdInPath = Regex("""/(\d+)/""")
    private val longNumber = Regex("""\d{11,}""")

    /**
     * @param asDesktop the owner's page is only served whole to a desktop browser carrying a
     *   session; Facebook's own anonymous cookie stands in when the reader has none of their own.
     */
    private fun headersFor(session: String?, asDesktop: Boolean = false): Map<String, String> = buildMap {
        put("Accept", Network.ACCEPT_HTML)
        if (asDesktop) put("User-Agent", Facebook.DESKTOP_USER_AGENT)
        when {
            session != null -> put("Cookie", session)
            asDesktop -> put("Cookie", Facebook.SESSION_COOKIE)
        }
    }

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? =
        read(url, session = null) ?: sessionFor(url)?.let { read(url, session = it) }

    /** The browser's cookies for this site, or `null` when the reader has never signed in. */
    private fun sessionFor(url: String): String? = cookies.cookiesFor(url)?.takeIf { it.isNotBlank() }

    private suspend fun read(url: String, session: String?): ScrapedMediaDto? =
        if (url.contains("share")) {
            scrapeShareLink(url, session)
        } else {
            val videoId = longNumber.findAll(url).joinToString("") { it.value }
            videoPage(reelUrl(videoId), headersFor(session))
        }

    private suspend fun scrapeShareLink(url: String, session: String?): ScrapedMediaDto? = coroutineScope {
        val page = fetcher.getText(url, headersFor(session)).getOrThrow()
        val canonical = page.substringAfter("<link rel=\"canonical\"", "")
            .substringAfter("href=\"", "")
            .substringBefore("\"", "")
        val owner = canonical.substringAfter(".facebook.com/", "").substringBefore("/", "")
        if (owner.isBlank()) return@coroutineScope null
        val videoId = videoIdInPath.find(canonical.replace(owner, ""))?.groupValues?.get(1).orEmpty()

        val ownerUrl = "${Facebook.PAGE_URL}$owner/videos/$videoId/?=null"
        val fromOwnerPage = async { videoPage(ownerUrl, headersFor(session, asDesktop = true)) }
        val fromReel = async { videoPage(reelUrl(videoId), headersFor(session)) }
        fromOwnerPage.await() ?: fromReel.await()
    }

    private suspend fun videoPage(pageUrl: String, headers: Map<String, String>): ScrapedMediaDto? {
        val html = fetcher.getText(pageUrl, headers).getOrNull() ?: return null
        return FacebookPageParser.parse(html)
    }

    private fun reelUrl(videoId: String): String = Facebook.REEL_URL + videoId.replace("/", "")
}
