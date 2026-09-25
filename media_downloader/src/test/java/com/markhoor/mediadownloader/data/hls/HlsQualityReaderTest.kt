package com.markhoor.mediadownloader.data.hls

import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.network.MediaSizeProbe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsQualityReaderTest {

    private val base = "https://cdn.test/v/"

    private fun readerFor(files: Map<String, String>): HlsQualityReader {
        val server = FakeMediaServer(files.mapValues { (_, text) -> text.toByteArray() })
        return HlsQualityReader(HttpFetcher(server.client, HttpClientFactory.json), MediaSizeProbe(server.client))
    }

    @Test
    fun `a master's encodes are listed best first, one per label`() = runTest {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=512x288
            288.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360
            360a.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=640x360
            360b.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            1080.m3u8
        """.trimIndent()
        val media = "#EXTM3U\n#EXTINF:10,\ns.ts\n#EXT-X-ENDLIST"
        val reader = readerFor(
            mapOf(base + "master.m3u8" to master) +
                listOf("720", "288", "360a", "360b", "1080").associate { base + "$it.m3u8" to media },
        )

        val qualities = reader.qualitiesOf(base + "master.m3u8", emptyMap()).qualities

        assertEquals(listOf("1080p", "720p", "360p", "288p"), qualities.map { it.label })
        assertEquals("the higher rate of the two 360p encodes", base + "360b.m3u8", qualities[2].url)
    }

    @Test
    fun `a master states how long the video runs, from the variant it reads`() = runTest {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            720.m3u8
        """.trimIndent()
        val media = "#EXTM3U\n#EXTINF:9.5,\na.ts\n#EXTINF:10.25,\nb.ts\n#EXT-X-ENDLIST"
        val reader = readerFor(mapOf(base + "master.m3u8" to master, base + "720.m3u8" to media))

        val read = reader.qualitiesOf(base + "master.m3u8", emptyMap())

        assertEquals(19_750L, read.durationMillis)
    }

    @Test
    fun `a media playlist states its own running time`() = runTest {
        val media = "#EXTM3U\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n#EXTINF:3,\nc.ts\n#EXT-X-ENDLIST"
        val reader = readerFor(mapOf(base + "one.m3u8" to media))

        val read = reader.qualitiesOf(base + "one.m3u8", emptyMap())

        assertEquals(15_000L, read.durationMillis)
    }

    @Test
    fun `a live playlist has no running time`() = runTest {
        // No #EXT-X-ENDLIST: what it lists is the window so far, not the length of anything.
        val media = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:120\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts"
        val reader = readerFor(mapOf(base + "live.m3u8" to media))

        val read = reader.qualitiesOf(base + "live.m3u8", emptyMap())

        assertNull(read.durationMillis)
    }
}
