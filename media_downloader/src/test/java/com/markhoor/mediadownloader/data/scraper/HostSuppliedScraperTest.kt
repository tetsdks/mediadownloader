package com.markhoor.mediadownloader.data.scraper

import com.markhoor.mediadownloader.MediaSource
import com.markhoor.mediadownloader.data.browser.SilentScraper
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.domain.usecase.CheckSiteAccessUseCase
import com.markhoor.mediadownloader.domain.models.SiteAccess
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam a host app plugs its own reader into. What matters is that it is treated exactly like
 * the module's own readers - and that it cannot take their place.
 */
class HostSuppliedScraperTest {

    private class FakeSource(
        override val hosts: Set<String>,
        private val reads: (String) -> Boolean = { true },
        private val answer: (String) -> MediaModel? = { null },
    ) : MediaSource {
        val asked = mutableListOf<String>()

        override fun handles(url: String): Boolean = reads(url)

        override suspend fun read(url: String): MediaModel? {
            asked += url
            return answer(url)
        }
    }

    private fun media(vararg qualities: MediaQualityModel) = MediaModel(
        title = "A video",
        thumbnailUrl = "https://cdn.test/cover.jpg",
        qualities = qualities.toList(),
        sourceUrl = "ignored",
        durationMillis = 92_500,
    )

    private fun quality(label: String, headers: Map<String, String> = emptyMap()) = MediaQualityModel(
        url = "https://cdn.test/$label.mp4",
        label = label,
        type = MediaType.Video,
        sizeBytes = 1_000,
        audioUrl = "https://cdn.test/$label.m4a",
        headers = headers,
    )

    private fun resolverWith(vararg sources: MediaSource) = ScraperResolver(
        facebookVideo = SilentScraper(),
        facebookShare = SilentScraper(),
        instagram = SilentScraper(),
        pornhub = SilentScraper(),
        linkedIn = SilentScraper(),
        tikTok = SilentScraper(),
        twitter = SilentScraper(),
        dailymotion = SilentScraper(),
        pinterest = SilentScraper(),
        getInDevice = SilentScraper(),
        hostSupplied = sources.map(::HostSuppliedScraper),
    )

    @Test
    fun `a link on the source's host is given to it, subdomains included`() {
        val source = FakeSource(setOf("example.com"))
        val resolver = resolverWith(source)

        assertEquals(1, resolver.scrapersFor("https://m.example.com/watch?v=1").size)
        assertEquals(1, resolver.scrapersFor("https://example.com/watch?v=1").size)
        assertTrue("a lookalike host is not it", resolver.scrapersFor("https://notexample.com/v/1").isEmpty())
    }

    @Test
    fun `a source is asked which of its links it actually reads`() {
        // Its site's feed is on its hosts and is not one of its posts. Without this the feed reads
        // as a page holding one video: the script draws one button on it instead of one per card.
        val source = FakeSource(setOf("example.com"), reads = { it.contains("/watch?v=") })
        val resolver = resolverWith(source)

        assertEquals(1, resolver.scrapersFor("https://example.com/watch?v=1").size)
        assertTrue("the feed is not a post", resolver.scrapersFor("https://example.com/").isEmpty())
    }

    @Test
    fun `a source cannot stand in for a site this module already reads`() {
        // Its hosts say tiktok, and tiktok is read here: the module's own reader answers alone.
        val source = FakeSource(setOf("tiktok.com"))
        val resolver = resolverWith(source)

        val scrapers = resolver.scrapersFor("https://www.tiktok.com/@someone/video/123")

        assertTrue("the module's own, not the app's", scrapers.none { it is HostSuppliedScraper })
    }

    @Test
    fun `what the source found is handed on whole`() = runTest {
        val source = FakeSource(setOf("example.com"), answer = {
            media(quality("1080p", mapOf("Referer" to "https://example.com/")), quality("720p"))
        })

        val scraped = HostSuppliedScraper(source).scrape("https://example.com/v/1").getOrThrow()

        assertEquals(listOf("1080p", "720p"), scraped.qualities.map { it.label })
        assertEquals("https://cdn.test/1080p.m4a", scraped.qualities.first().audioUrl)
        assertEquals("A video", scraped.title)
        assertEquals(92_500L, scraped.durationMillis)
        assertEquals("the first quality's headers carry the download", mapOf("Referer" to "https://example.com/"), scraped.headers)
    }

    @Test
    fun `a source that throws is a source that found nothing`() = runTest {
        val source = FakeSource(setOf("example.com"), answer = { error("its extractor broke") })

        val result = HostSuppliedScraper(source).scrape("https://example.com/v/1")

        assertTrue(result.isFailure)
    }

    @Test
    fun `a link off its hosts is never given to it`() = runTest {
        val source = FakeSource(setOf("example.com"), answer = { media(quality("1080p")) })

        val result = HostSuppliedScraper(source).scrape("https://elsewhere.com/v/1")

        assertTrue(result.isFailure)
        assertTrue("not even asked", source.asked.isEmpty())
    }

    @Test
    fun `a source's host counts as supported in strict mode, and a blocked one stays blocked`() {
        val access = CheckSiteAccessUseCase(
            strictSupportedSitesOnly = true,
            extraBlockedHosts = emptySet(),
            extraSupportedHosts = setOf("example.com", "youtube.com"),
        )

        assertEquals(SiteAccess.Allowed, access("https://example.com/v/1"))
        assertEquals("a reader does not lift the switch", SiteAccess.Blocked, access("https://www.youtube.com/watch?v=1"))
    }

    @Test
    fun `a source with no hosts reads nothing`() = runTest {
        val source = FakeSource(emptySet(), answer = { media(quality("1080p")) })

        assertNull(HostSuppliedScraper(source).scrape("https://example.com/v/1").getOrNull())
        assertTrue("not even asked", source.asked.isEmpty())
    }
}
