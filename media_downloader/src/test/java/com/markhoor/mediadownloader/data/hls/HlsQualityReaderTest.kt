package com.markhoor.mediadownloader.data.hls

import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.network.MediaSizeProbe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HlsQualityReaderTest {

    private val base = "https://cdn.test/v/"

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
        val server = FakeMediaServer(
            mapOf(base + "master.m3u8" to master.toByteArray()) +
                listOf("720", "288", "360a", "360b", "1080").associate { base + "$it.m3u8" to media.toByteArray() },
        )
        val reader = HlsQualityReader(HttpFetcher(server.client, HttpClientFactory.json), MediaSizeProbe(server.client))

        val qualities = reader.qualitiesOf(base + "master.m3u8", emptyMap())

        assertEquals(listOf("1080p", "720p", "360p", "288p"), qualities.map { it.label })
        assertEquals("the higher rate of the two 360p encodes", base + "360b.m3u8", qualities[2].url)
    }
}
