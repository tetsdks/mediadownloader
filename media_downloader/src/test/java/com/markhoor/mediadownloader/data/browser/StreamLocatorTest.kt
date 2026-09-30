package com.markhoor.mediadownloader.data.browser

import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLocatorTest {

    private val playlist = "#EXTM3U\n#EXTINF:4,\nsegment1.ts\n".toByteArray()

    private fun locator(files: Map<String, ByteArray>) =
        FakeMediaServer(files).let { StreamLocator(HttpFetcher(it.client, HttpClientFactory.json), playlistProbesAtOnce = 3, blocksHostOf = testAccess()::blocksHostOf) }

    @Test
    fun `the playlist beside a segment is found, nearest folder first`() = runTest {
        val segment = "https://cdn.test/v/42/5x/segment1.ts?tok=1"
        val found = locator(
            mapOf(
                "https://cdn.test/v/42/rendition.m3u8?tok=1" to playlist,
                "https://cdn.test/v/42/5x/index.m3u8?tok=1" to playlist,
            ),
        ).playlistForSegment(segment, emptyMap(), altQuery = "")
        assertEquals("https://cdn.test/v/42/5x/index.m3u8?tok=1", found)
    }

    @Test
    fun `the page's playlist signature is tried when the segment's does not open it`() = runTest {
        val found = locator(mapOf("https://cdn.test/v/42/rendition.m3u8?page=2" to playlist))
            .playlistForSegment("https://cdn.test/v/42/5x/segment1.ts?tok=1", emptyMap(), altQuery = "page=2")
        assertEquals("https://cdn.test/v/42/rendition.m3u8?page=2", found)
    }

    @Test
    fun `an answer that is not a playlist is not one`() = runTest {
        val found = locator(mapOf("https://cdn.test/v/42/index.m3u8" to "<html>denied</html>".toByteArray()))
            .playlistForSegment("https://cdn.test/v/42/segment1.ts", emptyMap(), altQuery = "")
        assertNull(found)
    }

    @Test
    fun `brightcove's answer gives its adaptive master`() = runTest {
        val api = "https://edge.api.brightcove.com/playback/v1/accounts/1/videos/2"
        val answer = """{"sources":[{"src":"https:\/\/m.test\/clear\/r.m3u8"},{"src":"https:\/\/m.test\/master.m3u8"},{"src":"https:\/\/m.test\/v.mp4"}]}"""
        assertEquals("https://m.test/master.m3u8", locator(mapOf(api to answer.toByteArray())).brightcoveStream(api, emptyMap()))
    }

    @Test
    fun `a card's page is read for its file, never for itself or an embed`() = runTest {
        val page = "https://site.test/watch/a"
        fun html(video: String) = """<html><head><meta property="og:video" content="$video"></head></html>""".toByteArray()
        assertEquals("https://cdn.test/a.mp4", locator(mapOf(page to html("https://cdn.test/a.mp4"))).mediaOnPage(page, emptyMap()))
        assertNull(locator(mapOf(page to html("https://site.test/watch/a/"))).mediaOnPage(page, emptyMap()))
        assertNull(locator(mapOf(page to html("https://site.test/embed/a"))).mediaOnPage(page, emptyMap()))
    }

    @Test
    fun `bitchute's api names the file its page does not`() = runTest {
        val subject = locator(
            mapOf("https://api.bitchute.com/api/beta/video/media" to """{"media_url":"https:\/\/seed.bitchute.com\/a.mp4"}""".toByteArray()),
        )
        assertEquals("abc123", subject.bitchuteVideoId("https://www.bitchute.com/video/abc123/"))
        assertNull(subject.bitchuteVideoId("https://www.bitchute.com/channel/abc/"))
        assertEquals("https://seed.bitchute.com/a.mp4", subject.bitchuteMedia("abc123"))
    }

    @Test
    fun `a playlist of subtitles is told from a video's`() = runTest {
        val subject = locator(
            mapOf(
                "https://m.test/subs/rendition.m3u8" to "#EXTM3U\n#EXTINF:30,\nsegment0.vtt\n".toByteArray(),
                "https://m.test/video/rendition.m3u8" to playlist,
            ),
        )
        assertTrue(subject.isSubtitlePlaylist("https://m.test/subs/rendition.m3u8", emptyMap()))
        assertFalse(subject.isSubtitlePlaylist("https://m.test/video/rendition.m3u8", emptyMap()))
    }
}
