package com.markhoor.mediadownloader.data.scraper.imdb

import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A video's page on IMDb, read rather than sniffed - and read with the browser's own cookies,
 * without which the site answers a bot check instead of the page.
 */
class ImdbScraperTest {

    private val videoUrl = "https://www.imdb.com/video/vi2055653913/"

    private val page = """
        <html><head><script type="application/ld+json">
        {"@type":"VideoObject","name":"La bola negra - Trailer #1 | IMDb",
        "thumbnailUrl":"https://m.media-amazon.com/images/M/cover._V1_.jpg","duration":"PT2M3S"}
        </script></head><body><script>{"playbackURLs":[
        {"displayName":{"value":"1080p","__typename":"LocalizedString"},"videoMimeType":"MP4","videoDefinition":"DEF_1080p","url":"https://imdb-video.media-imdb.com/mc/vi20/vi20_1080p.mp4?Expires=1"},
        {"displayName":{"value":"480p","__typename":"LocalizedString"},"videoMimeType":"MP4","videoDefinition":"DEF_480p","url":"https://imdb-video.media-imdb.com/mc/vi20/vi20_480p.mp4?Expires=1"}]}
        </script></body></html>
    """.trimIndent()

    private fun scraperOver(
        vararg served: Pair<String, String>,
        cookie: String? = null,
    ): Pair<ImdbScraper, FakeMediaServer> {
        val server = FakeMediaServer(served.associate { (url, body) -> url to body.toByteArray() })
        return ImdbScraper(
            HttpFetcher(server.client, HttpClientFactory.json),
            cookies = { cookie },
        ) to server
    }

    @Test
    fun `a video's page gives every height it was encoded at, largest first`() = runTest {
        val (scraper, _) = scraperOver(videoUrl to page)

        val media = scraper.scrape(videoUrl).getOrThrow()

        assertEquals(listOf("1080p", "480p"), media.qualities.map { it.label })
        assertEquals("https://imdb-video.media-imdb.com/mc/vi20/vi20_1080p.mp4?Expires=1", media.qualities.first().url)
    }

    @Test
    fun `the page's own words name the video, picture it and time it`() = runTest {
        val (scraper, _) = scraperOver(videoUrl to page)

        val media = scraper.scrape(videoUrl).getOrThrow()

        assertEquals("La bola negra - Trailer #1 | IMDb", media.title)
        assertEquals("https://m.media-amazon.com/images/M/cover._V1_.jpg", media.thumbnailUrl)
        assertEquals(123_000L, media.durationMillis)
    }

    /**
     * The signed files are handed out by a cdn that asks for nothing, so a download started from
     * one outlives the session the page was read with.
     */
    @Test
    fun `nothing is asked of the download itself`() = runTest {
        val (scraper, _) = scraperOver(videoUrl to page)

        assertTrue(scraper.scrape(videoUrl).getOrThrow().headers.isEmpty())
    }

    @Test
    fun `the browser's cookies are sent with the request, and a browser's name`() = runTest {
        val (scraper, server) = scraperOver(videoUrl to page, cookie = "session-id=1; ubid-main=2")

        scraper.scrape(videoUrl).getOrThrow()

        assertTrue(server.requests.any { it.startsWith(videoUrl) })
        assertEquals("session-id=1; ubid-main=2", server.lastHeaders[Network.HEADER_COOKIE])
        assertTrue(server.lastHeaders[Network.HEADER_USER_AGENT].orEmpty().contains("Mozilla/5.0"))
    }

    @Test
    fun `a page with nothing to play is a failure, not an empty result`() = runTest {
        val (scraper, _) = scraperOver(videoUrl to "<html><body>Video unavailable</body></html>")

        assertTrue(scraper.scrape(videoUrl).isFailure)
    }
}
