package com.markhoor.mediadownloader.data.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {

    private val pinterestMasterUrl = "https://v1.pinimg.com/videos/iht/hls/7a/d7/98/clip.m3u8"

    /** Pinterest keeps its sound in one rendition and lists picture-only variants beside it. */
    private val pinterestMaster = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio1",NAME="English, stereo",AUTOSELECT=YES,URI="clip_audio.m3u8",CHANNELS="2"
        #EXT-X-STREAM-INF:BANDWIDTH=408200,AVERAGE-BANDWIDTH=340847,CODECS="avc1.64080D,mp4a.40.29",RESOLUTION=234x416,FRAME-RATE=30,AUDIO="audio1"
        clip_240w.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1373152,AVERAGE-BANDWIDTH=1211432,CODECS="avc1.64081F,mp4a.40.29",RESOLUTION=720x1280,FRAME-RATE=30,AUDIO="audio1"
        clip_720w.m3u8
    """.trimIndent()

    @Test
    fun `a master lists its variants, absolute, with their sound`() {
        val variants = HlsPlaylistParser.parseMaster(pinterestMaster, pinterestMasterUrl)
        assertEquals(2, variants.size)
        with(variants[1]) {
            assertEquals("https://v1.pinimg.com/videos/iht/hls/7a/d7/98/clip_720w.m3u8", url)
            assertEquals(1_373_152L, bandwidth)
            assertEquals(1_211_432L, averageBandwidth)
            assertEquals("720x1280", resolution)
            assertEquals(30f, frameRate)
            assertEquals("https://v1.pinimg.com/videos/iht/hls/7a/d7/98/clip_audio.m3u8", audioUrl)
        }
    }

    /** CODECS holds a comma inside its quotes; splitting the line on commas cut it in half. */
    @Test
    fun `a quoted attribute keeps its commas`() {
        val variant = HlsPlaylistParser.parseMaster(pinterestMaster, pinterestMasterUrl).first()
        assertEquals("avc1.64080D,mp4a.40.29", variant.codecs)
    }

    @Test
    fun `a master that muxes its sound gives no audio playlist`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720
            /hls/high.m3u8
        """.trimIndent()
        val variant = HlsPlaylistParser.parseMaster(master, "https://cdn.example.com/a/b/master.m3u8").single()
        assertNull(variant.audioUrl)
        assertEquals("https://cdn.example.com/hls/high.m3u8", variant.url)
    }

    @Test
    fun `master and media playlists are told apart`() {
        assertTrue(HlsPlaylistParser.isMaster(pinterestMaster))
        assertFalse(HlsPlaylistParser.isMaster("#EXTM3U\n#EXTINF:10,\na.ts\n#EXT-X-ENDLIST"))
    }

    /** Pinterest's CMAF: one file cut by offset, with the init piece named by #EXT-X-MAP. */
    @Test
    fun `a byte range playlist keeps every piece and its init segment`() {
        val playlist = """
            #EXTM3U
            #EXT-X-MAP:URI="clip_240w.cmfv",BYTERANGE="974@0"
            #EXTINF:2,
            #EXT-X-BYTERANGE:69034@1098
            clip_240w.cmfv
            #EXTINF:2,
            #EXT-X-BYTERANGE:50000
            clip_240w.cmfv
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsed = HlsPlaylistParser.parseMedia(playlist, "https://v1.pinimg.com/hls/clip_240w.m3u8")

        assertEquals(0L..973L, parsed.initSegment?.byteRange)
        assertEquals(2, parsed.segments.size)
        assertEquals(1098L..70131L, parsed.segments[0].byteRange)
        // No offset: the piece continues where the previous one ended.
        assertEquals(70132L..120131L, parsed.segments[1].byteRange)
        assertEquals("https://v1.pinimg.com/hls/clip_240w.cmfv", parsed.segments[0].url)
        assertEquals(974L + 69_034L + 50_000L, parsed.statedBytes)
        assertEquals(4.0, parsed.durationSeconds, 0.0)
        assertTrue(parsed.isComplete)
    }

    /** The real playlist rumble served for a 39 minute 4K video: 238 pieces named by offset. */
    @Test
    fun `rumble's pieces state the whole rendition's size`() {
        val playlist = javaClass.classLoader!!.getResource("rumble_2160p_chunklist.m3u8")!!.readText()
        val parsed = HlsPlaylistParser.parseMedia(playlist, "https://hugh.cdn.rumble.cloud/video/OYnZA.jaa.tar?r_file=chunklist.m3u8")

        assertEquals(238, parsed.segments.size)
        assertEquals(2_810_750_400L, parsed.statedBytes)
        assertNull("r_range is served by the url itself, not by a Range header", parsed.segments[0].byteRange)
        assertTrue(parsed.segments[0].url.startsWith("https://hugh.cdn.rumble.cloud/video/OYnZA.jaa.tar?r_file=media-0.ts"))
    }

    @Test
    fun `an ordinary playlist states no size`() {
        val playlist = "#EXTM3U\n#EXTINF:10,\nseg0.ts\n#EXTINF:10,\nseg1.ts\n#EXT-X-ENDLIST"
        val parsed = HlsPlaylistParser.parseMedia(playlist, "https://cdn.example.com/v/index.m3u8")
        assertNull(parsed.statedBytes)
        assertEquals(20.0, parsed.durationSeconds, 0.0)
    }

    /** Brightcove signs the playlist url; a segment asked for without the signature is refused. */
    @Test
    fun `segments carry the playlist's query when they have none`() {
        val playlist = "#EXTM3U\n#EXTINF:6,\nseg0.ts\n#EXTINF:6,\nseg1.ts?own=1\n#EXT-X-ENDLIST"
        val parsed = HlsPlaylistParser.parseMedia(playlist, "https://cdn.example.com/v/index.m3u8?fastly_token=abc")
        assertEquals("https://cdn.example.com/v/seg0.ts?fastly_token=abc", parsed.segments[0].url)
        assertEquals("https://cdn.example.com/v/seg1.ts?own=1", parsed.segments[1].url)
    }

    @Test
    fun `a live playlist is not complete`() {
        val parsed = HlsPlaylistParser.parseMedia("#EXTM3U\n#EXTINF:6,\nseg0.ts", "https://cdn.example.com/live.m3u8")
        assertFalse(parsed.isComplete)
    }
}
