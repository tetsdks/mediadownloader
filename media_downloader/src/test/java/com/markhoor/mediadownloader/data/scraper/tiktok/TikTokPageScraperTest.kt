package com.markhoor.mediadownloader.data.scraper.tiktok

import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TikTok's own page, read with the browser's session - the way a video that only the reader's
 * account may see can be read at all.
 */
class TikTokPageScraperTest {

    private val videoUrl = "https://www.tiktok.com/@someone/video/7106594312292453675"
    private val play = "https://v16-webapp-prime.tiktok.com/video/tos/alisg/aaa/?a=1988&bt=1046"
    private val download = "https://v16-webapp-prime.tiktok.com/video/tos/alisg/bbb/?a=1988&bt=1050"

    /** The page's own block, shortened to what is read out of it. */
    private val page = """
        <html><head><title>A video</title></head><body>
        <script id="api-data" type="application/json">
        {"videoDetail":{"statusCode":0,"itemInfo":{"itemStruct":{
        "id":"7106594312292453675","desc":"how many frogs did you find?",
        "video":{"duration":24,"cover":"https://p16.tiktokcdn.com/cover.jpeg",
        "playAddr":"$play","downloadAddr":"$download",
        "bitrateInfo":[{"GearName":"normal_540_0","PlayAddr":{"DataSize":2471142,"UrlList":["$play"]}}]}}}}}
        </script></body></html>
    """.trimIndent()

    private fun scraperOver(
        vararg served: Pair<String, String>,
        cookie: String? = null,
    ): Pair<TikTokPageScraper, FakeMediaServer> {
        val server = FakeMediaServer(served.associate { (url, body) -> url to body.toByteArray() })
        return TikTokPageScraper(
            HttpFetcher(server.client, HttpClientFactory.json),
            cookies = { cookie },
        ) to server
    }

    @Test
    fun `the page gives the player's own file and the watermarked one`() = runTest {
        val (scraper, _) = scraperOver(videoUrl to page)

        val media = scraper.scrape(videoUrl).getOrThrow()

        assertEquals(listOf(QualityLabels.HD, QualityLabels.WATERMARK), media.qualities.map { it.label })
        assertEquals(play, media.qualities.first().url)
        assertEquals(download, media.qualities.last().url)
    }

    /** The rates the page lists are the same video; only the size of the chosen one is wanted. */
    @Test
    fun `the size of the file the player was given is read off the page`() = runTest {
        val (scraper, _) = scraperOver(videoUrl to page)

        assertEquals(2_471_142L, scraper.scrape(videoUrl).getOrThrow().qualities.first().sizeBytes)
    }

    @Test
    fun `the page names the video, pictures it and times it`() = runTest {
        val (scraper, _) = scraperOver(videoUrl to page)

        val media = scraper.scrape(videoUrl).getOrThrow()

        assertEquals("how many frogs did you find?", media.title)
        assertEquals("https://p16.tiktokcdn.com/cover.jpeg", media.thumbnailUrl)
        assertEquals(24_000L, media.durationMillis)
    }

    /**
     * The files are refused to a request with no session however it is dressed up, so the session
     * the page was read with travels with them - along with a browser's name and where they came
     * from.
     */
    @Test
    fun `the download carries the session the page was read with`() = runTest {
        val (scraper, _) = scraperOver(videoUrl to page, cookie = "sessionid=1; tt_chain_token=2")

        val media = scraper.scrape(videoUrl).getOrThrow()

        assertEquals("sessionid=1; tt_chain_token=2", media.headers[Network.HEADER_COOKIE])
        assertEquals("https://www.tiktok.com/", media.headers[Network.HEADER_REFERER])
        assertTrue(media.headers[Network.HEADER_USER_AGENT].orEmpty().contains("Mozilla/5.0"))
    }

    @Test
    fun `the request itself carries the browser's cookies`() = runTest {
        val (scraper, server) = scraperOver(videoUrl to page, cookie = "sessionid=1")

        scraper.scrape(videoUrl).getOrThrow()

        assertEquals("sessionid=1", server.lastHeaders[Network.HEADER_COOKIE])
    }

    /** Signed out the page is still read; it just has nothing an account would have added. */
    @Test
    fun `no session is not a failure in itself`() = runTest {
        val (scraper, server) = scraperOver(videoUrl to page)

        assertTrue(scraper.scrape(videoUrl).isSuccess)
        assertTrue(server.lastHeaders[Network.HEADER_COOKIE].isNullOrBlank())
    }

    @Test
    fun `a page with no block of its own is a failure, not an empty result`() = runTest {
        val (scraper, _) = scraperOver(videoUrl to "<html><body>Video currently unavailable</body></html>")

        assertTrue(scraper.scrape(videoUrl).isFailure)
    }

    /** A page that names the same file twice offers it once. */
    @Test
    fun `one file named twice is offered once`() = runTest {
        val sameTwice = page.replace(download, play)
        val (scraper, _) = scraperOver(videoUrl to sameTwice)

        assertEquals(1, scraper.scrape(videoUrl).getOrThrow().qualities.size)
    }
}
