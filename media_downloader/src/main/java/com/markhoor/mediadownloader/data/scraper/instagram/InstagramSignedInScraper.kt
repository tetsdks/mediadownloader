package com.markhoor.mediadownloader.data.scraper.instagram

import com.markhoor.mediadownloader.core.Constants.Instagram
import com.markhoor.mediadownloader.core.isThreadsMediaPage
import com.markhoor.mediadownloader.data.network.CookieSource
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper

/**
 * The post as the signed-in user sees it: the page is fetched with the WebView's own cookies, so
 * private and age-gated posts the user can open are readable too. The most reliable source when
 * the user is signed in, which is why [InstagramScraper] prefers it.
 */
internal class InstagramSignedInScraper(
    private val fetcher: HttpFetcher,
    private val cookies: CookieSource,
) : SiteScraper() {

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val shortcode = InstagramPageParser.shortcodeOf(url).ifBlank { return null }
        val pageUrl = if (url.isThreadsMediaPage()) url else "${Instagram.REEL_URL}$shortcode/"
        val headers = buildMap {
            put("User-Agent", Instagram.SIGNED_IN_USER_AGENT)
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            put("Accept-Language", "en")
            put("sec-fetch-mode", "cors")
            put("sec-fetch-site", "same-origin")
            cookies.cookiesFor(pageUrl)?.let { put("Cookie", it) }
        }
        val html = fetcher.getText(pageUrl, headers, requireSuccess = true).getOrThrow()
        if (!html.startsWith("<!DOCTYPE") && !html.startsWith("<html")) return null
        return InstagramPageParser.parseSignedInPage(html)
    }
}
