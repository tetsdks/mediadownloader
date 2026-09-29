package com.markhoor.mediadownloader.data.scraper.tube

import com.markhoor.mediadownloader.core.Constants.Tube
import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.net.SocketException
import org.junit.Test

/**
 * One reader over many sites: the page is fetched once and handed to whichever shape it is written
 * in. Only the fetching and the choosing are here; the shapes themselves are [TubePlayerParserTest].
 */
class TubeScraperTest {

    private val watchUrl = "https://www.pornhub.com/view_video.php?viewkey=abc123"
    private val filesUrl = "https://www.pornhub.com/video/get_media?s=signed&v=abc123"

    /** Aylo's shape: streams per height, plus the entry that leads to the plain files. */
    private val ayloPage = """
        <html><body>
        <video src="https://htl-cdn.adtng.com/creatives/1177880_video_with_sound.mp4"></video>
        <script>
        var flashvars_977717220311377955 = {"video_title":"A night at the café &amp; more",
        "video_duration":141,"image_url":"https://cdn.test/cover.jpg","mediaDefinitions":[
        {"height":1080,"width":1920,"format":"hls","videoUrl":"https://cdn.test/1080/master.m3u8","quality":"1080"},
        {"height":720,"width":1280,"format":"hls","videoUrl":"https://cdn.test/720/master.m3u8","quality":"720"},
        {"height":2160,"width":3840,"format":"mp4","videoUrl":"$filesUrl","quality":[]}]};
        </script></body></html>
    """.trimIndent()

    private val ayloFiles = """
        [{"height":240,"width":426,"format":"mp4","videoUrl":"https://cdn.test/240.mp4?hash=a","quality":"240"},
         {"height":1080,"width":1920,"format":"mp4","videoUrl":"https://cdn.test/1080.mp4?hash=c","quality":"1080"}]
    """.trimIndent()

    private fun scraperOver(vararg served: Pair<String, String>) = TubeScraper(
        HttpFetcher(
            FakeMediaServer(served.associate { (url, body) -> url to body.toByteArray() }).client,
            HttpClientFactory.json,
        ),
        isRestrictedSite = { true },
    )

    // region Which links it claims

    @Test
    fun `it claims a video's page on a site of the category, and nothing else`() {
        val tube = scraperOver()

        assertTrue(tube.reads(watchUrl))
        assertTrue(tube.reads("https://www.xnxx.com/video-1ajfxte3/desi_stepsister"))
        assertTrue(tube.reads("https://xhamster.com/videos/some-slug-xh7FMCw"))
        assertTrue(tube.reads("https://spankbang.com/abcde/video/indian"))
        // The sites' own pages that show no single video.
        assertFalse(tube.reads("https://www.pornhub.com/"))
        assertFalse(tube.reads("https://xhamster.com/categories"))
    }

    @Test
    fun `a site outside the category is never claimed, whatever its address looks like`() {
        val tube = TubeScraper(
            HttpFetcher(FakeMediaServer(emptyMap()).client, HttpClientFactory.json),
            isRestrictedSite = { false },
        )

        assertFalse(tube.reads("https://www.youtube.com/watch?v=abc"))
        assertFalse(tube.reads("https://example.com/video/1"))
    }

    // endregion

    // region What it hands back

    @Test
    fun `the plain files are preferred to the streams, best first`() = runTest {
        val media = scraperOver(watchUrl to ayloPage, filesUrl to ayloFiles).scrape(watchUrl).getOrThrow()

        assertEquals(listOf("1080p", "240p"), media.qualities.map { it.label })
        assertEquals("https://cdn.test/1080.mp4?hash=c", media.qualities.first().url)
    }

    @Test
    fun `the title, cover and running time come from the page's own player`() = runTest {
        val media = scraperOver(watchUrl to ayloPage, filesUrl to ayloFiles).scrape(watchUrl).getOrThrow()

        assertEquals("A night at the café & more", media.title)
        assertEquals("https://cdn.test/cover.jpg", media.thumbnailUrl)
        assertEquals(141_000L, media.durationMillis)
    }

    @Test
    fun `the advert the page also plays is not among the qualities`() = runTest {
        val media = scraperOver(watchUrl to ayloPage, filesUrl to ayloFiles).scrape(watchUrl).getOrThrow()

        assertTrue(media.qualities.none { it.url.contains("adtng.com") })
    }

    @Test
    fun `without the files, the best stream is handed over on its own to be expanded`() = runTest {
        val media = scraperOver(watchUrl to ayloPage).scrape(watchUrl).getOrThrow()   // files answer 404

        assertEquals("https://cdn.test/1080/master.m3u8", media.qualities.single().url)
    }

    @Test
    fun `a site written in another shape is read by that shape instead`() = runTest {
        val url = "https://www.xnxx.com/video-1ajfxte3/desi"
        val page = """
            <html><head><title>Desi - XNXX.COM</title></head><script>
            html5player.setVideoTitle('Desi Stepsister');
            html5player.setVideoUrlHigh('https://mp4.example/abc/video_360p.mp4');
            </script></html>
        """.trimIndent()

        val media = scraperOver(url to page).scrape(url).getOrThrow()

        assertEquals("https://mp4.example/abc/video_360p.mp4", media.qualities.single().url)
        assertEquals("360p", media.qualities.single().label)
        assertEquals("Desi Stepsister", media.title)
    }

    @Test
    fun `a page written in no shape it knows is a failure, not an empty result`() = runTest {
        val media = scraperOver(watchUrl to "<html><body>Video unavailable</body></html>")

        assertTrue(media.scrape(watchUrl).isFailure)
    }

    @Test
    fun `every request says which browser and which site it came from`() = runTest {
        val media = scraperOver(watchUrl to ayloPage, filesUrl to ayloFiles).scrape(watchUrl).getOrThrow()

        // The site it came from, as the module writes a host: without the www it shares.
        assertEquals("https://pornhub.com/", media.headers[Tube.HEADER_REFERER])
        assertTrue(media.headers.values.any { it.contains("Mozilla/5.0") })
    }

    // endregion

    // region Filtered networks

    /**
     * A network that filters these sites resets the connection instead of answering, and does it to
     * some attempts and not others - so the page is asked for again rather than given up on.
     */
    private fun scraperResetting(times: Int): TubeScraper {
        var resets = 0
        val served = mapOf(watchUrl to ayloPage, filesUrl to ayloFiles)
        val client = HttpClient(MockEngine { request ->
            val asked = request.url.toString()
            if (asked == watchUrl && resets++ < times) throw SocketException("Connection reset")
            served[asked]?.let { respond(it, HttpStatusCode.OK) } ?: respond("", HttpStatusCode.NotFound)
        })
        return TubeScraper(HttpFetcher(client, HttpClientFactory.json), isRestrictedSite = { true })
    }

    @Test
    fun `a connection reset is not the answer, so the page is asked for again`() = runTest {
        val media = scraperResetting(times = Tube.PAGE_ATTEMPTS - 1).scrape(watchUrl).getOrThrow()

        assertEquals("1080p", media.qualities.first().label)
    }

    @Test
    fun `a page that is reset every time is a failure`() = runTest {
        assertTrue(scraperResetting(times = Tube.PAGE_ATTEMPTS).scrape(watchUrl).isFailure)
    }

    // endregion
}
