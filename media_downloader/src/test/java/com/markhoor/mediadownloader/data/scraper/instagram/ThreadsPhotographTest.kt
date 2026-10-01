package com.markhoor.mediadownloader.data.scraper.instagram

import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Threads post that is a photograph rather than a video.
 *
 * The page says so itself - `"video_versions":null,"media_type":1` - and carries the picture as the
 * first `image_versions2` candidate. Nothing read a post without a video, so a photograph on
 * Threads came back as media that could not be found. An Instagram reel is left alone: there the
 * same candidates are the video's cover, which must never be offered as the media.
 */
class ThreadsPhotographTest {

    private val postUrl = "https://www.threads.com/@someone/post/Dd6YnykGgJb"
    private val picture = "https://instagram.test/v/t39.30808-6/830883165_29005909612433125_n.jpg"

    private fun photoPage(caption: String) = """
        <html><head><meta property="og:image" content="$picture"></head><body><script>
        {"video_versions":null,"media_type":1,"caption":{"text":"$caption"},
        "image_versions2":{"candidates":[{"url":"$picture","width":1080}]}}
        </script></body></html>
    """.trimIndent()

    private fun scraperOver(vararg served: Pair<String, String>): InstagramPreviewScraper {
        val server = FakeMediaServer(served.associate { (url, body) -> url to body.toByteArray() })
        return InstagramPreviewScraper(HttpFetcher(server.client, HttpClientFactory.json))
    }

    @Test
    fun `a photograph on Threads is the post's own picture`() = runTest {
        val scraper = scraperOver(postUrl to photoPage("A line worth keeping as the title"))

        val media = scraper.scrape(postUrl).getOrThrow()

        val quality = media.qualities.single()
        assertEquals(picture, quality.url)
        assertEquals(MediaType.Image, quality.type)
        assertEquals("A line worth keeping as the title", media.title)
        assertTrue("a picture is its own artwork", media.thumbnailUrl == picture)
    }

    /** The same page shape on Instagram names a reel's cover, which is not the reel. */
    @Test
    fun `an Instagram page with no video is not read as its cover`() = runTest {
        val reelsUrl = "https://www.instagram.com/reels/Cxyz123/"
        val scraper = scraperOver(reelsUrl to photoPage("A reel's caption, long enough to keep"))

        assertTrue(scraper.scrape("https://www.instagram.com/reel/Cxyz123/").isFailure)
    }
}
