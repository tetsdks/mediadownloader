package com.markhoor.mediadownloader.data.scraper.instagram

import com.markhoor.mediadownloader.core.Constants.Instagram
import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.CookieSource
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.SignInRequiredException
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instagram answers a signed-out request with a page that has no media in it at all - no
 * `video_versions`, an embed whose `contextJSON` is null - and its api answers
 * `401 {"require_login":true}`. So every reader asks with whatever session there is, and when
 * there is none and nothing was read, the host is told that signing in is the way out rather than
 * that the link is empty.
 */
class InstagramSessionTest {

    private val reel = "https://www.instagram.com/reel/DQN9eI6CF55"
    private val reelsPage = "https://www.instagram.com/reels/DQN9eI6CF55"
    private val session = "sessionid=the-user-own-session; csrftoken=live-token"

    private fun fetcherOver(served: Map<String, String>): Pair<HttpFetcher, FakeMediaServer> {
        val server = FakeMediaServer(served.mapValues { (_, body) -> body.toByteArray() })
        return HttpFetcher(server.client, HttpClientFactory.json) to server
    }

    @Test
    fun `the reel's page is asked for with the session the browser holds`() = runTest {
        val (fetcher, server) = fetcherOver(mapOf(reelsPage to "<html></html>"))

        InstagramPreviewScraper(fetcher, CookieSource { session }).scrape(reel)

        assertEquals(session, server.lastHeaders["Cookie"])
    }

    /** No session: the canned cookies are still better than none - they name a browser at least. */
    @Test
    fun `without a session the page is still asked for`() = runTest {
        val (fetcher, server) = fetcherOver(mapOf(reelsPage to "<html></html>"))

        InstagramPreviewScraper(fetcher, CookieSource { null }).scrape(reel)

        assertEquals(Instagram.PREVIEW_COOKIE, server.lastHeaders["Cookie"])
    }

    /**
     * The api answers `403` to a request that does not name the web client, however good the rest
     * of it is, and wants the csrf token echoed back out of the cookie it came in.
     */
    @Test
    fun `the api is asked as its own web client asks`() = runTest {
        val video = "https://instagram.test/v/t50.2886-16/reel.mp4"
        val answer = """
            {"data":{"xdt_shortcode_media":{"video_url":"$video","thumbnail_src":"https://instagram.test/c.jpg",
            "accessibility_caption":"A reel worth keeping as a title"}}}
        """.trimIndent()
        val (fetcher, server) = fetcherOver(mapOf(Instagram.GRAPHQL_URL to answer))

        val media = InstagramGraphQlScraper(fetcher, CookieSource { session }).scrape(reel).getOrThrow()

        assertEquals(video, media.qualities.single().url)
        assertEquals(Instagram.WEB_APP_ID, server.lastHeaders["X-IG-App-ID"])
        assertEquals("live-token", server.lastHeaders["X-CSRFToken"])
        assertEquals(session, server.lastHeaders["Cookie"])
    }

    @Test
    fun `a cookie with no csrf token sends no token header`() = runTest {
        val (fetcher, server) = fetcherOver(mapOf(Instagram.GRAPHQL_URL to "{}"))

        InstagramGraphQlScraper(fetcher, CookieSource { "sessionid=only-this" }).scrape(reel)

        assertEquals(Instagram.WEB_APP_ID, server.lastHeaders["X-IG-App-ID"])
        assertEquals(null, server.lastHeaders["X-CSRFToken"])
    }

    @Test
    fun `signed out, a reel nothing could read asks for a sign-in`() = runTest {
        val failure = scraperReadingNothing(cookies = CookieSource { null }).scrape(reel).exceptionOrNull()

        assertTrue("expected a sign-in to be asked for, got $failure", failure is SignInRequiredException)
        assertEquals("Instagram", (failure as SignInRequiredException).site)
    }

    /** Signed in, the same empty answer means this post could not be read, which is not the same. */
    @Test
    fun `signed in, a reel nothing could read is simply not found`() = runTest {
        val failure = scraperReadingNothing(cookies = CookieSource { session }).scrape(reel).exceptionOrNull()

        assertTrue("expected an ordinary failure, got $failure", failure != null)
        assertTrue("a signed-in user is not told to sign in", failure !is SignInRequiredException)
    }

    @Test
    fun `a Threads post asks for a sign-in to Threads`() = runTest {
        val post = "https://www.threads.com/@someone/post/Dd6YnykGgJb"

        val failure = scraperReadingNothing(cookies = CookieSource { null }).scrape(post).exceptionOrNull()

        assertEquals("Threads", (failure as? SignInRequiredException)?.site)
    }

    private object FoundNothing : SiteScraper() {
        override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = null
    }

    private fun scraperReadingNothing(cookies: CookieSource): InstagramScraper {
        val (fetcher, _) = fetcherOver(emptyMap())
        return InstagramScraper(
            fetcher = fetcher,
            signedIn = FoundNothing,
            preview = FoundNothing,
            embed = FoundNothing,
            graphQl = FoundNothing,
            getInDevice = FoundNothing,
            cookies = cookies,
        )
    }
}
