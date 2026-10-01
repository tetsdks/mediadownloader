package com.markhoor.mediadownloader.data.scraper.instagram

import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Threads share link names no post, so the post's own address is read out of the page it serves
 * before any reader is asked - every one of them builds its request from that address.
 */
class ThreadsShareLinkTest {

    private val shareUrl = "https://www.threads.com/share/BATDAqdzRY/"
    private val postUrl = "https://www.threads.com/@royeabdulhameed/post/Dd6YnykGgJb"
    private val video = "https://instagram.test/v/830883165.mp4"

    private val sharePage = """
        <html><head>
        <meta property="og:type" content="article">
        <meta property="og:url" content="https://www.threads.com/&#064;royeabdulhameed/post/Dd6YnykGgJb">
        </head></html>
    """.trimIndent()

    /** Remembers what it was asked for, which is the whole point of the test. */
    private class Asked(private val answer: ScrapedMediaDto?) : SiteScraper() {
        val urls: MutableList<String> = mutableListOf()
        override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
            urls += url
            return answer
        }
    }

    private val media = ScrapedMediaDto(
        qualities = listOf(ScrapedQualityDto(video, MediaType.Video, QualityLabels.HD)),
        title = "A thread",
        thumbnailUrl = "https://instagram.test/cover.jpg",
    )

    @Test
    fun `a share link is read as the post it leads to`() = runTest {
        val server = FakeMediaServer(mapOf(shareUrl to sharePage.toByteArray()))
        val signedIn = Asked(media)
        val others = List(4) { Asked(null) }
        val scraper = InstagramScraper(
            fetcher = HttpFetcher(server.client, HttpClientFactory.json),
            signedIn = signedIn,
            preview = others[0],
            embed = others[1],
            graphQl = others[2],
            getInDevice = others[3],
        )

        val read = scraper.scrape(shareUrl).getOrThrow()

        assertEquals(video, read.qualities.single().url)
        assertEquals(listOf(postUrl), signedIn.urls)
        assertTrue(
            "every reader must be given the post's address, not the share's",
            others.all { asked -> asked.urls.all { it == postUrl } },
        )
    }

    @Test
    fun `a share link leading nowhere is not guessed at`() = runTest {
        val server = FakeMediaServer(mapOf(shareUrl to "<html><head></head></html>".toByteArray()))
        val signedIn = Asked(media)
        val scraper = InstagramScraper(
            fetcher = HttpFetcher(server.client, HttpClientFactory.json),
            signedIn = signedIn,
            preview = Asked(null),
            embed = Asked(null),
            graphQl = Asked(null),
            getInDevice = Asked(null),
        )

        assertTrue(scraper.scrape(shareUrl).isFailure)
        assertEquals(emptyList<String>(), signedIn.urls)
    }

    /** A post's own address is passed on untouched, and costs no extra request. */
    @Test
    fun `a post link is read without asking anything first`() = runTest {
        val server = FakeMediaServer(emptyMap())
        val signedIn = Asked(media)
        val scraper = InstagramScraper(
            fetcher = HttpFetcher(server.client, HttpClientFactory.json),
            signedIn = signedIn,
            preview = Asked(null),
            embed = Asked(null),
            graphQl = Asked(null),
            getInDevice = Asked(null),
        )

        scraper.scrape(postUrl).getOrThrow()

        assertEquals(listOf(postUrl), signedIn.urls)
        assertEquals(emptyList<String>(), server.requests)
    }
}
