package com.markhoor.mediadownloader.data.scraper.facebook

import com.markhoor.mediadownloader.core.Constants.Facebook
import com.markhoor.mediadownloader.core.Constants.Network
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
 */
internal class FacebookVideoScraper(private val fetcher: HttpFetcher) : SiteScraper() {

    private val videoIdInPath = Regex("""/(\d+)/""")
    private val longNumber = Regex("""\d{11,}""")

    private val reelHeaders = mapOf("Accept" to Network.ACCEPT_HTML)
    private val ownerPageHeaders = mapOf(
        "Cookie" to Facebook.SESSION_COOKIE,
        "User-Agent" to Facebook.DESKTOP_USER_AGENT,
        "Accept" to Network.ACCEPT_HTML,
    )

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? =
        if (url.contains("share")) {
            scrapeShareLink(url)
        } else {
            videoPage(reelUrl(videoId = longNumber.findAll(url).joinToString("") { it.value }), reelHeaders)
        }

    private suspend fun scrapeShareLink(url: String): ScrapedMediaDto? = coroutineScope {
        val page = fetcher.getText(url).getOrThrow()
        val canonical = page.substringAfter("<link rel=\"canonical\"", "")
            .substringAfter("href=\"", "")
            .substringBefore("\"", "")
        val owner = canonical.substringAfter(".facebook.com/", "").substringBefore("/", "")
        if (owner.isBlank()) return@coroutineScope null
        val videoId = videoIdInPath.find(canonical.replace(owner, ""))?.groupValues?.get(1).orEmpty()

        val fromOwnerPage = async { videoPage("${Facebook.PAGE_URL}$owner/videos/$videoId/?=null", ownerPageHeaders) }
        val fromReel = async { videoPage(reelUrl(videoId), reelHeaders) }
        fromOwnerPage.await() ?: fromReel.await()
    }

    private suspend fun videoPage(pageUrl: String, headers: Map<String, String>): ScrapedMediaDto? {
        val html = fetcher.getText(pageUrl, headers).getOrNull() ?: return null
        return FacebookPageParser.parse(html)
    }

    private fun reelUrl(videoId: String): String = Facebook.REEL_URL + videoId.replace("/", "")
}
