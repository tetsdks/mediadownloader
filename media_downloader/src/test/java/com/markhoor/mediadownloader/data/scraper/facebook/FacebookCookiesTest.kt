package com.markhoor.mediadownloader.data.scraper.facebook

import com.markhoor.mediadownloader.core.Constants.Facebook
import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A post is read as the reader sees it: with the browser's session where there is one, and exactly
 * as before where there is not. A post shared with friends, or one in a group, is invisible to a
 * request carrying no session - which is what these two scrapers used to send.
 */
class FacebookCookiesTest {

    private val shareUrl = "https://www.facebook.com/share/p/1EiEaDvtZ7/"
    private val reelUrl = "https://www.facebook.com/reel/123456789012345"

    /** Enough of a share page for the scraper to find the post behind it. */
    private val sharePage = """
        <html><script>{"story_fbid":"4463780570616736","actor":"x","id":"2943938342600974"}</script></html>
    """.trimIndent()

    private val postPage = """
        <html><head><meta property="og:image" content="https://scontent.test/photo.jpg">
        <meta property="og:title" content="A post"></head></html>
    """.trimIndent()

    private val postUrl = "${Facebook.PAGE_URL}2943938342600974/posts/4463780570616736"

    private object NoInstagram : SiteScraper() {
        override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = null
    }

    private fun serverOver(vararg served: Pair<String, String>) =
        FakeMediaServer(served.associate { (url, body) -> url to body.toByteArray() })

    @Test
    fun `a share link is read with the browser's session when there is one`() = runTest {
        val server = serverOver(shareUrl to sharePage, postUrl to postPage)
        val scraper = FacebookShareScraper(
            HttpFetcher(server.client, HttpClientFactory.json),
            instagram = NoInstagram,
            cookies = { "c_user=1; xs=abc" },
        )

        scraper.scrape(shareUrl).getOrThrow()

        assertEquals("c_user=1; xs=abc", server.lastHeaders["Cookie"])
    }

    @Test
    fun `signed out, a share link is asked for exactly as it always was`() = runTest {
        val server = serverOver(shareUrl to sharePage, postUrl to postPage)
        val scraper = FacebookShareScraper(
            HttpFetcher(server.client, HttpClientFactory.json),
            instagram = NoInstagram,
            cookies = { null },
        )

        scraper.scrape(shareUrl).getOrThrow()

        assertNull(server.lastHeaders["Cookie"])
    }

    @Test
    fun `a reel is read with the browser's session when there is one`() = runTest {
        val page = """<html><script>"browser_native_hd_url":"https://video.test/hd.mp4"</script></html>"""
        val server = serverOver("${Facebook.REEL_URL}123456789012345" to page)
        val scraper = FacebookVideoScraper(
            HttpFetcher(server.client, HttpClientFactory.json),
            cookies = { "c_user=1; xs=abc" },
        )

        scraper.scrape(reelUrl).getOrThrow()

        assertEquals("c_user=1; xs=abc", server.lastHeaders["Cookie"])
    }

    /** With no session of the reader's own, a reel page is asked for without one, as before. */
    @Test
    fun `signed out, a reel page carries no cookie of its own`() = runTest {
        val page = """<html><script>"browser_native_hd_url":"https://video.test/hd.mp4"</script></html>"""
        val server = serverOver("${Facebook.REEL_URL}123456789012345" to page)
        val scraper = FacebookVideoScraper(
            HttpFetcher(server.client, HttpClientFactory.json),
            cookies = { null },
        )

        scraper.scrape(reelUrl).getOrThrow()

        assertNull(server.lastHeaders["Cookie"])
    }
}
