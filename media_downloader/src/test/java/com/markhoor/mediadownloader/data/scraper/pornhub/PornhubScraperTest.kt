package com.markhoor.mediadownloader.data.scraper.pornhub

import com.markhoor.mediadownloader.core.Constants.Pornhub
import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch page as the site serves it: a player object listing one master playlist per height,
 * plus one entry pointing at the endpoint that answers with plain files - and, in the markup, the
 * pre-roll the page also plays.
 */
class PornhubScraperTest {

    private val watchUrl = "https://www.pornhub.com/view_video.php?viewkey=abc123"
    private val filesUrl = "https://www.pornhub.com/video/get_media?s=signed&v=abc123"

    private val page = """
        <html><body>
        <video src="https://htl-cdn.adtng.com/creatives/1177880_video_with_sound.mp4"></video>
        <script>
        var flashvars_977717220311377955 = {"video_title":"A night at the café &amp; more",
        "video_duration":141,"image_url":"https://cdn.test/cover.jpg","mediaDefinitions":[
        {"group":1,"height":1080,"width":1920,"format":"hls","videoUrl":"https://cdn.test/1080/master.m3u8","quality":"1080"},
        {"group":1,"height":240,"width":426,"format":"hls","videoUrl":"https://cdn.test/240/master.m3u8","quality":"240"},
        {"group":1,"height":720,"width":1280,"format":"hls","videoUrl":"https://cdn.test/720/master.m3u8","quality":"720"},
        {"group":1,"height":2160,"width":3840,"format":"mp4","videoUrl":"$filesUrl","quality":[]}]};
        </script></body></html>
    """.trimIndent()

    private val files = """
        [{"height":240,"width":426,"format":"mp4","videoUrl":"https://cdn.test/240.mp4?hash=a","quality":"240"},
         {"height":720,"width":1280,"format":"mp4","videoUrl":"https://cdn.test/720.mp4?hash=b","quality":"720"},
         {"height":1080,"width":1920,"format":"mp4","videoUrl":"https://cdn.test/1080.mp4?hash=c","quality":"1080"}]
    """.trimIndent()

    private fun scraperOver(vararg served: Pair<String, String>): Pair<PornhubScraper, FakeMediaServer> {
        val server = FakeMediaServer(served.associate { (url, body) -> url to body.toByteArray() })
        return PornhubScraper(HttpFetcher(server.client, HttpClientFactory.json)) to server
    }

    @Test
    fun `the plain files are offered, best first, and the advert on the page is not`() = runTest {
        val (scraper, _) = scraperOver(watchUrl to page, filesUrl to files)

        val media = scraper.scrape(watchUrl).getOrThrow()

        assertEquals(listOf("1080p", "720p", "240p"), media.qualities.map { it.label })
        assertEquals("https://cdn.test/1080.mp4?hash=c", media.qualities.first().url)
        assertTrue(media.qualities.none { it.url.contains("adtng.com") })
    }

    @Test
    fun `the title, cover and running time come from the player object`() = runTest {
        val (scraper, _) = scraperOver(watchUrl to page, filesUrl to files)

        val media = scraper.scrape(watchUrl).getOrThrow()

        assertEquals("A night at the café & more", media.title)
        assertEquals("https://cdn.test/cover.jpg", media.thumbnailUrl)
        assertEquals(141_000L, media.durationMillis)
    }

    @Test
    fun `without the files, the best stream is handed over on its own to be expanded`() = runTest {
        val (scraper, _) = scraperOver(watchUrl to page)   // the files endpoint answers 404

        val media = scraper.scrape(watchUrl).getOrThrow()

        val stream = media.qualities.single()
        assertEquals("https://cdn.test/1080/master.m3u8", stream.url)
        assertEquals("1080p", stream.label)
    }

    @Test
    fun `a page that carries no player object is a failure, not an empty result`() = runTest {
        val (scraper, _) = scraperOver(watchUrl to "<html><body>Video unavailable</body></html>")

        assertTrue(scraper.scrape(watchUrl).isFailure)
    }

    /**
     * A network that filters this site resets the connection instead of answering, and does it to
     * some attempts and not others - so the page is asked for again rather than given up on.
     */
    private fun scraperResetting(times: Int): PornhubScraper {
        var resets = 0
        val served = mapOf(watchUrl to page, filesUrl to files)
        val client = HttpClient(MockEngine { request ->
            val asked = request.url.toString()
            if (asked == watchUrl && resets++ < times) throw SocketException("Connection reset")
            served[asked]
                ?.let { respond(it, HttpStatusCode.OK) }
                ?: respond("", HttpStatusCode.NotFound)
        })
        return PornhubScraper(HttpFetcher(client, HttpClientFactory.json))
    }

    @Test
    fun `a connection reset is not the answer, so the page is asked for again`() = runTest {
        val media = scraperResetting(times = Pornhub.PAGE_ATTEMPTS - 1).scrape(watchUrl).getOrThrow()

        assertEquals("1080p", media.qualities.first().label)
    }

    @Test
    fun `a page that is reset every time is a failure`() = runTest {
        assertTrue(scraperResetting(times = Pornhub.PAGE_ATTEMPTS).scrape(watchUrl).isFailure)
    }
}
