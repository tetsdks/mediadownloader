package com.markhoor.mediadownloader.data.scraper.linkedin

import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A post read from LinkedIn's own page, and the shortener the app hands out - which is followed by
 * the request itself, so what arrives here is whatever page it was written for.
 */
class LinkedInScraperTest {

    private val postUrl = "https://www.linkedin.com/posts/someone_a-post-activity-7510718612652101633-BCXX"
    private val shortUrl = "https://lnkd.in/p/ekARMhxS"

    private val postPage = """
        <html><head><title>Stop dropping product images onto white backgrounds | Someone</title>
        <meta property="og:url" content="$postUrl">
        <meta property="og:image" content="https://dms.licdn.com/playlist/vid/v2/D56/thumbnail.jpg">
        </head><body>
        <video data-poster-url="https://dms.licdn.com/playlist/vid/v2/D56/poster.jpg"
               data-sources="[{&quot;src&quot;:&quot;https://dms.licdn.com/playlist/vid/v2/D56/mp4-720p-30fp-crf28/video.mp4&quot;,&quot;data-bitrate&quot;:2000000},{&quot;src&quot;:&quot;https://dms.licdn.com/playlist/vid/v2/D56/mp4-360p-30fp-crf28/video.mp4&quot;,&quot;data-bitrate&quot;:500000}]"></video>
        </body></html>
    """.trimIndent()

    /** What a `lnkd.in` link leads to when the post only mentioned somebody else's page. */
    private val someoneElsesPage = """
        <html><head><title>A blog post</title>
        <meta property="og:url" content="https://someblog.example/2026/a-post">
        <meta property="og:image" content="https://someblog.example/cover.jpg">
        </head><body></body></html>
    """.trimIndent()

    private fun scraperOver(vararg served: Pair<String, String>) = LinkedInScraper(
        HttpFetcher(
            FakeMediaServer(served.associate { (url, body) -> url to body.toByteArray() }).client,
            HttpClientFactory.json,
        ),
    )

    @Test
    fun `a post's page gives every encode, largest first`() = runTest {
        val media = scraperOver(postUrl to postPage).scrape(postUrl).getOrThrow()

        assertEquals(listOf("720p", "360p"), media.qualities.map { it.label })
        assertTrue(media.qualities.all { it.type == MediaType.Video })
        assertEquals("https://dms.licdn.com/playlist/vid/v2/D56/poster.jpg", media.thumbnailUrl)
        assertTrue(media.title!!.startsWith("Stop dropping product images"))
    }

    /**
     * The link the app hands out when a post is shared. The request follows it, so the scraper is
     * handed the post's own page under the short address.
     */
    @Test
    fun `a short link that leads to a post is read as that post`() = runTest {
        val media = scraperOver(shortUrl to postPage).scrape(shortUrl).getOrThrow()

        assertEquals("720p", media.qualities.first().label)
    }

    /**
     * LinkedIn wraps every outside link a post mentions in a `lnkd.in` of its own, so such a link
     * lands as often on somebody else's site as on a post. Read without this check, that page's
     * cover picture was offered as the post's media.
     */
    @Test
    fun `a short link that leads somewhere else is not this scraper's to offer`() = runTest {
        assertTrue(scraperOver(shortUrl to someoneElsesPage).scrape(shortUrl).isFailure)
    }

    @Test
    fun `a post with no player hands over its picture`() = runTest {
        val page = """
            <html><head><title>A picture post | Someone</title>
            <meta property="og:url" content="$postUrl">
            <meta property="og:image" content="https://media.licdn.com/dms/image/v2/D56/feedshare.jpg">
            </head><body></body></html>
        """.trimIndent()

        val media = scraperOver(postUrl to page).scrape(postUrl).getOrThrow()

        assertEquals(MediaType.Image, media.qualities.single().type)
        assertEquals("https://media.licdn.com/dms/image/v2/D56/feedshare.jpg", media.qualities.single().url)
    }
}
