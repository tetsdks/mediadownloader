package com.markhoor.mediadownloader.data.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperResolverTest {

    private class Named(val name: String) : SiteScraper() {
        override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = null
        override fun toString() = name
    }

    private fun resolver(twitter: SiteScraper? = Named("twitter")) = ScraperResolver(
        facebookVideo = Named("facebookVideo"),
        facebookShare = Named("facebookShare"),
        instagram = Named("instagram"),
        linkedIn = Named("linkedIn"),
        tikTok = Named("tikTok"),
        twitter = twitter,
        dailymotion = Named("dailymotion"),
        pinterest = Named("pinterest"),
        getInDevice = Named("getInDevice"),
    )

    private fun scrapersFor(url: String, twitter: SiteScraper? = Named("twitter")) =
        resolver(twitter).scrapersFor(url).map { it.toString() }

    @Test
    fun `each site's links reach its own scrapers`() {
        mapOf(
            "https://www.facebook.com/reel/123456789012345" to listOf("facebookVideo", "getInDevice"),
            "https://m.facebook.com/watch/?v=123" to listOf("facebookVideo", "getInDevice"),
            "https://www.facebook.com/share/v/abc/" to listOf("facebookVideo", "getInDevice"),
            "https://www.facebook.com/share/p/abc/" to listOf("facebookShare", "getInDevice"),
            "https://www.instagram.com/reel/abc/" to listOf("instagram"),
            "https://www.instagram.com/p/abc/" to listOf("instagram"),
            "https://www.threads.com/@someone/post/abc" to listOf("instagram"),
            "https://www.linkedin.com/posts/someone_activity-1" to listOf("linkedIn"),
            "https://www.tiktok.com/@someone/video/7684717011754093844" to listOf("tikTok", "getInDevice"),
            "https://www.tiktok.com/foryou" to listOf("tikTok", "getInDevice"),
            "https://x.com/someone/status/1234567890" to listOf("twitter"),
            "https://mobile.twitter.com/someone/status/987654321?s=20" to listOf("twitter"),
            "https://www.dailymotion.com/video/x8da0md" to listOf("dailymotion"),
            "https://www.dailymotion.com/player/metadata/video/x8da0md" to listOf("dailymotion"),
            "https://www.pinterest.com/pin/62628251062820323/" to listOf("pinterest"),
            "https://pin.it/abc123" to listOf("pinterest"),
        ).forEach { (url, expected) -> assertEquals(url, expected, scrapersFor(url)) }
    }

    @Test
    fun `links that name no post have no scraper`() {
        listOf(
            "https://www.threads.com/",
            "https://m.facebook.com/watch/",
            "https://x.com/someone",
            "https://www.instagram.com/someone/",
            "https://www.pinterest.com/search/pins/?q=cats",
            "https://www.reddit.com/r/videos/",
            "",
        ).forEach { assertTrue(it, scrapersFor(it).isEmpty()) }
    }

    /** A url that merely mentions a site is not that site: `sex.com` ends in the letters `x.com`. */
    @Test
    fun `lookalike hosts are not the site`() {
        listOf(
            "https://www.sex.com/a/status/1",
            "https://notx.com/u/status/1",
            "https://netflix.com/u/status/1",
            "https://example.com/?ref=https://www.instagram.com/reel/abc/",
        ).forEach { assertTrue(it, scrapersFor(it).isEmpty()) }
    }

    @Test
    fun `without a tweeload key X links have no scraper`() {
        assertTrue(scrapersFor("https://x.com/someone/status/1", twitter = null).isEmpty())
    }

    /** WebView urls can be megabytes; the answer must come back, and must still be right. */
    @Test
    fun `huge urls are answered, not thrown`() {
        assertEquals(listOf("twitter"), scrapersFor("https://x.com/" + "b".repeat(3_000_000) + "/status/1"))
        assertTrue(scrapersFor("https://sex.com/" + "b".repeat(3_000_000) + "/status/1").isEmpty())
        assertTrue(scrapersFor("data:image/png;base64," + "A".repeat(3_000_000)).isEmpty())
    }
}
