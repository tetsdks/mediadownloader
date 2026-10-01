package com.markhoor.mediadownloader.data.scraper.facebook

import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A group's cover photo is not the group post's picture.
 *
 * Facebook shows a group's post to nobody outside the group and serves the group's own card
 * instead, whose `og:image` is the group's cover - so a share link to a group post handed the
 * reader a photograph that was nowhere in the post. The group's front page is asked what its cover
 * is, and a match is refused.
 */
class FacebookGroupPhotoTest {

    private val shareUrl = "https://www.facebook.com/share/p/1EiEaDvtZ7/"
    private val groupFront = "https://www.facebook.com/groups/2943938342600974/"
    private val cover = "https://scontent.test/v/t39.30808-6/278395917_518487713186254_n.jpg"
    private val photo = "https://scontent.test/v/t39.30808-6/780981845_122124632054918752_n.jpg"

    private object NoInstagram : SiteScraper() {
        override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = null
    }

    private fun page(ownUrl: String, picture: String) = """
        <html><head>
        <meta property="og:url" content="$ownUrl">
        <meta property="og:title" content="Imran Khan | Facebook">
        <meta property="og:image" content="$picture?oh=${picture.hashCode()}&amp;oe=6AC3B4E4">
        </head><body></body></html>
    """.trimIndent()

    private fun scraperOver(vararg served: Pair<String, String>): Pair<SiteScraper, FakeMediaServer> {
        val server = FakeMediaServer(served.associate { (url, body) -> url to body.toByteArray() })
        return FacebookShareScraper(
            HttpFetcher(server.client, HttpClientFactory.json),
            instagram = NoInstagram,
            cookies = { null },
        ) to server
    }

    @Test
    fun `a group post offering the group's cover is refused`() = runTest {
        val (scraper, _) = scraperOver(
            shareUrl to page("${groupFront}posts/4463780570616736/", cover),
            groupFront to page(groupFront, cover),
        )

        assertTrue(
            "the group's cover was handed over as the post's picture",
            scraper.scrape(shareUrl).isFailure,
        )
    }

    @Test
    fun `a group post naming its own picture is read`() = runTest {
        val (scraper, _) = scraperOver(
            shareUrl to page("${groupFront}posts/4463780570616736/", photo),
            groupFront to page(groupFront, cover),
        )

        val media = scraper.scrape(shareUrl).getOrThrow()

        assertTrue(media.qualities.single().url.startsWith(photo))
    }

    /** A post outside a group has no cover to confuse, and no second page is read. */
    @Test
    fun `a post outside a group is read without asking anything else`() = runTest {
        val (scraper, server) = scraperOver(
            shareUrl to page("https://www.facebook.com/61560/posts/4463780570616736/", photo),
        )

        val media = scraper.scrape(shareUrl).getOrThrow()

        assertTrue(media.qualities.single().url.startsWith(photo))
        assertEquals(1, server.requests.size)
        assertFalse(server.requests.any { it.contains("/groups/") })
    }
}
