package com.markhoor.mediadownloader.data.scraper.facebook

import com.markhoor.mediadownloader.core.Constants.Facebook
import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A post is read as a stranger first and as the reader second.
 *
 * Facebook serves a public post to a stranger in the shape these parsers know and serves the same
 * page to a session as the signed-in app, which names no file at all - so asking as the reader
 * first cost a signed-in reader the parse. The session is the second question, and the only one
 * that can reach a post shared with friends or left in a group.
 */
class FacebookCookiesTest {

    private val shareUrl = "https://www.facebook.com/share/p/1EiEaDvtZ7/"
    private val reelUrl = "https://www.facebook.com/reel/123456789012345"
    private val reelPage = "${Facebook.REEL_URL}123456789012345"
    private val postUrl = "${Facebook.PAGE_URL}2943938342600974/posts/4463780570616736"

    private val sharePage = """
        <html><script>{"story_fbid":"4463780570616736","actor":"x","id":"2943938342600974"}</script></html>
    """.trimIndent()

    private val postPage = """
        <html><head><meta property="og:image" content="https://scontent.test/photo.jpg">
        <meta property="og:title" content="A post"></head></html>
    """.trimIndent()

    private val videoPage =
        """<html><script>"browser_native_hd_url":"https://video.test/hd.mp4"</script></html>"""

    private object NoInstagram : SiteScraper() {
        override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = null
    }

    private fun serverOver(vararg served: Pair<String, String>) =
        FakeMediaServer(served.associate { (url, body) -> url to body.toByteArray() })

    /** A site that shows a page only to a request carrying a session, and an empty one otherwise. */
    private fun behindLogin(vararg served: Pair<String, String>): Pair<HttpClient, MutableList<String?>> {
        val pages = served.toMap()
        val sessions = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            val session = request.headers["Cookie"]?.takeIf { it.contains("c_user") }
            sessions += session
            val body = pages[request.url.toString()].takeIf { session != null }
            respond(body ?: "<html><body>This content isn't available</body></html>", HttpStatusCode.OK)
        })
        return client to sessions
    }

    // region Read as a stranger first

    @Test
    fun `a public post is read without the session, even when there is one`() = runTest {
        val server = serverOver(shareUrl to sharePage, postUrl to postPage)
        val scraper = FacebookShareScraper(
            HttpFetcher(server.client, HttpClientFactory.json),
            instagram = NoInstagram,
            cookies = { "c_user=1; xs=abc" },
        )

        scraper.scrape(shareUrl).getOrThrow()

        assertNull("the reader's session was spent on a post anyone can see", server.lastHeaders["Cookie"])
    }

    @Test
    fun `a public video is read without the session, even when there is one`() = runTest {
        val server = serverOver(reelPage to videoPage)
        val scraper = FacebookVideoScraper(
            HttpFetcher(server.client, HttpClientFactory.json),
            cookies = { "c_user=1; xs=abc" },
        )

        scraper.scrape(reelUrl).getOrThrow()

        assertNull(server.lastHeaders["Cookie"])
    }

    // endregion

    // region And as the reader when nothing else answers

    @Test
    fun `a post only the reader can see is read with the reader's session`() = runTest {
        val (client, sessions) = behindLogin(shareUrl to sharePage, postUrl to postPage)
        val scraper = FacebookShareScraper(
            HttpFetcher(client, HttpClientFactory.json),
            instagram = NoInstagram,
            cookies = { "c_user=1; xs=abc" },
        )

        val media = scraper.scrape(shareUrl).getOrThrow()

        assertEquals("https://scontent.test/photo.jpg", media.qualities.single().url)
        assertNull("the first read was made as a stranger", sessions.first())
        assertTrue("and the second as the reader", sessions.any { it != null })
    }

    @Test
    fun `a video only the reader can see is read with the reader's session`() = runTest {
        val (client, sessions) = behindLogin(reelPage to videoPage)
        val scraper = FacebookVideoScraper(HttpFetcher(client, HttpClientFactory.json), cookies = { "c_user=1; xs=abc" })

        scraper.scrape(reelUrl).getOrThrow()

        assertNull(sessions.first())
        assertTrue(sessions.any { it != null })
    }

    /** With no session of the reader's own there is only ever the one read, as before. */
    @Test
    fun `signed out, a page is asked for once and without a cookie`() = runTest {
        val server = serverOver(reelPage to videoPage)
        val scraper = FacebookVideoScraper(HttpFetcher(server.client, HttpClientFactory.json), cookies = { null })

        scraper.scrape(reelUrl).getOrThrow()

        assertNull(server.lastHeaders["Cookie"])
        assertEquals(1, server.requests.count { it.startsWith(reelPage) })
    }

    // endregion
}
