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
 * Every page is asked for with the browser's own cookies where there are any, so a video shared
 * with friends, or posted in a group the reader belongs to, is read as that reader sees it. Signed
 * out there are none and the requests go exactly as they always did: a public video needs no
 * session, and Facebook's own anonymous cookie is what keeps the owner's page from being a login
 * wall.
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
    private fun headersFor(url: String, asDesktop: Boolean = false): Map<String, String> = buildMap {
        put("Accept", Network.ACCEPT_HTML)
        if (asDesktop) put("User-Agent", Facebook.DESKTOP_USER_AGENT)
        val held = cookies.cookiesFor(url)?.takeIf { it.isNotBlank() }
        when {
            held != null -> put("Cookie", held)
            asDesktop -> put("Cookie", Facebook.SESSION_COOKIE)
        }
    }

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? =
        if (url.contains("share")) {
            scrapeShareLink(url)
        } else {
            val videoId = longNumber.findAll(url).joinToString("") { it.value }
            videoPage(reelUrl(videoId), headersFor(Facebook.REEL_URL))
        }

    private suspend fun scrapeShareLink(url: String): ScrapedMediaDto? = coroutineScope {
        val page = fetcher.getText(url, headersFor(url)).getOrThrow()
        val canonical = page.substringAfter("<link rel=\"canonical\"", "")
            .substringAfter("href=\"", "")
            .substringBefore("\"", "")
        val owner = canonical.substringAfter(".facebook.com/", "").substringBefore("/", "")
        if (owner.isBlank()) return@coroutineScope null
        val videoId = videoIdInPath.find(canonical.replace(owner, ""))?.groupValues?.get(1).orEmpty()

        val ownerUrl = "${Facebook.PAGE_URL}$owner/videos/$videoId/?=null"
        val fromOwnerPage = async { videoPage(ownerUrl, headersFor(ownerUrl, asDesktop = true)) }
        val fromReel = async { videoPage(reelUrl(videoId), headersFor(Facebook.REEL_URL)) }
        fromOwnerPage.await() ?: fromReel.await()
    }

    private suspend fun videoPage(pageUrl: String, headers: Map<String, String>): ScrapedMediaDto? {
        val html = fetcher.getText(pageUrl, headers).getOrNull() ?: return null
        return FacebookPageParser.parse(html)
    }

    private fun reelUrl(videoId: String): String = Facebook.REEL_URL + videoId.replace("/", "")
}
