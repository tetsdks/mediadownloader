package com.markhoor.mediadownloader.data.scraper.tube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player shapes the tube sites are written in, each read without knowing whose site it is. */
class TubePlayerParserTest {

    // region The player object

    @Test
    fun `the player object is read out of the page`() {
        val page = """
            <html><script>
                var flashvars_977717220311377955 = {"video_title":"A video","video_duration":141};
                playerObjList.push(1);
            </script></html>
        """.trimIndent()

        val json = TubePlayerParser.playerObjectOf(page)!!

        assertEquals("""{"video_title":"A video","video_duration":141}""", json)
    }

    @Test
    fun `a brace inside a title does not end the object`() {
        val page = """var flashvars_1 = {"video_title":"Braces } and { more","video_duration":7};"""

        assertEquals(
            """{"video_title":"Braces } and { more","video_duration":7}""",
            TubePlayerParser.playerObjectOf(page),
        )
    }

    @Test
    fun `an escaped quote inside a title does not end the string`() {
        val page = """var flashvars_1 = {"video_title":"She said \"hi\" }","video_duration":3};"""

        assertTrue(TubePlayerParser.playerObjectOf(page)!!.endsWith(""""video_duration":3}"""))
    }

    @Test
    fun `an object that is never closed gives nothing rather than the rest of the page`() {
        assertNull(TubePlayerParser.playerObjectOf("""var flashvars_3 = {"video_title":"cut off"""))
    }

    // endregion

    // region Player calls

    /** What xVideos and XNXX write into a watch page, in the order they write it. */
    private val playerCalls = """
        <html><script>
        html5player.setVideoTitle('Desi Stepsister &amp; Friend');
        html5player.setThumbUrl('https://thumb-cdn77.xnxx-cdn.com/abc/xn_1_t.jpg');
        html5player.setVideoUrlLow('https://mp4-cdn77.xnxx-cdn.com/abc/video_240p.mp4?secure=aa,1');
        html5player.setVideoUrlHigh('https://mp4-cdn77.xnxx-cdn.com/abc/video_360p.mp4?secure=bb,1');
        html5player.setVideoHLS('https://hls-cdn77.xnxx-cdn.com/abc/hls.m3u8');
        </script></html>
    """.trimIndent()

    @Test
    fun `player calls give the files, named by the height in their own url`() {
        val media = TubePlayerParser.fromPlayerCalls(playerCalls)!!

        assertEquals(listOf("360p", "240p"), media.qualities.map { it.label })
        assertEquals("https://mp4-cdn77.xnxx-cdn.com/abc/video_360p.mp4?secure=bb,1", media.qualities.first().url)
        assertEquals("Desi Stepsister & Friend", media.title)
        assertEquals("https://thumb-cdn77.xnxx-cdn.com/abc/xn_1_t.jpg", media.thumbnailUrl)
    }

    @Test
    fun `with no files, the playlist is handed over on its own to be expanded`() {
        val page = """
            <script>html5player.setVideoTitle('Only a stream');
            html5player.setVideoHLS('https://hls.example/abc/hls.m3u8');</script>
        """.trimIndent()

        val media = TubePlayerParser.fromPlayerCalls(page)!!

        assertEquals("https://hls.example/abc/hls.m3u8", media.qualities.single().url)
    }

    @Test
    fun `a page with no player calls is not read this way`() {
        assertNull(TubePlayerParser.fromPlayerCalls("<html><body>Nothing here</body></html>"))
    }

    // endregion

    // region Linked data and Open Graph

    @Test
    fun `linked data gives the video, its name and how long it runs`() {
        val page = """
            <html><script type="application/ld+json">
            {"@type":"VideoObject","name":"Indian Babe","duration":"PT0H12M33S",
             "thumbnailUrl":["https:\/\/static.eporner.com\/11_360.jpg"],
             "contentUrl":"https:\/\/gvideo.eporner.com\/OzG1R7X34gv\/OzG1R7X34gv.mp4"}
            </script></html>
        """.trimIndent()

        val media = TubePlayerParser.fromLinkedData(page)!!

        assertEquals("https://gvideo.eporner.com/OzG1R7X34gv/OzG1R7X34gv.mp4", media.qualities.single().url)
        assertEquals("Indian Babe", media.title)
        assertEquals(753_000L, media.durationMillis)
        assertEquals("https://static.eporner.com/11_360.jpg", media.thumbnailUrl)
    }

    /**
     * A page describes its cover in the same block as its video, as an ImageObject with a
     * contentUrl of its own. Read whole, the block offered the cover: a picture, a few hundred
     * kilobytes, labelled as the video and as long as the video runs.
     */
    @Test
    fun `the cover described beside the video is not taken for the video`() {
        val page = """
            <script type="application/ld+json">
            {"@context":"http://schema.org","@graph":[
              {"@type":"ImageObject","contentUrl":"https://pix.test/cover.jpg","name":"A cover"},
              {"@type":"VideoObject","name":"A video","duration":"PT21M37S",
               "thumbnailUrl":"https://pix.test/cover.jpg",
               "contentUrl":"https://ev.test/videos/360P_360K_1.mp4"}]}
            </script>
        """.trimIndent()

        val media = TubePlayerParser.fromLinkedData(page)!!

        assertEquals("https://ev.test/videos/360P_360K_1.mp4", media.qualities.single().url)
        assertEquals("A video", media.title)
        assertEquals(1_297_000L, media.durationMillis)
    }

    /** The network's mirrors write the same player object without the assignment in front of it. */
    @Test
    fun `the player object is found by its own key when nothing introduces it`() {
        val page = """
            <script>window.page_params = {"vkey":"223569731","video":{"video_title":"On a mirror",
            "mediaDefinitions":[{"format":"mp4","videoUrl":"https://www.mirror.test/media/mp4/?s=abc"}]}};
            </script>
        """.trimIndent()

        val player = TubePlayerParser.playerObjectOf(page)!!

        assertTrue(player.startsWith("{") && player.endsWith("}"))
        assertTrue(player.contains(""""video_title":"On a mirror""""))
        // The object around the key, not the page configuration it is nested in.
        assertTrue(!player.contains("vkey"))
    }

    @Test
    fun `linked data that describes something other than a video is passed over`() {
        val page = """
            <script type="application/ld+json">{"@type":"WebSite","name":"A tube"}</script>
        """.trimIndent()

        assertNull(TubePlayerParser.fromLinkedData(page))
    }

    @Test
    fun `open graph is the last thing tried`() {
        val page = """
            <meta property="og:video:secure_url" content="https://cdn.example/v.mp4">
            <meta property="og:title" content="A page">
            <meta property="og:image" content="https://cdn.example/cover.jpg">
        """.trimIndent()

        val media = TubePlayerParser.fromOpenGraph(page)!!

        assertEquals("https://cdn.example/v.mp4", media.qualities.single().url)
        assertEquals("A page", media.title)
        assertEquals("https://cdn.example/cover.jpg", media.thumbnailUrl)
    }

    /**
     * A tube page plays a pre-roll from an advert network and often has nothing else on it until
     * the video is asked for. Taking that file would hand the user a thirty-second advert.
     */
    @Test
    fun `an advert is never the answer, whichever shape names it`() {
        val advert = "https://htl-cdn.adtng.com/a7/creatives/1177880_video_with_sound.mp4"

        assertNull(TubePlayerParser.fromOpenGraph("""<meta property="og:video" content="$advert">"""))
        assertNull(
            TubePlayerParser.fromLinkedData(
                """<script type="application/ld+json">{"@type":"VideoObject","contentUrl":"$advert"}</script>""",
            ),
        )
        assertNull(TubePlayerParser.fromPlayerCalls("<script>html5player.setVideoUrlHigh('$advert');</script>"))
    }

    // endregion

    @Test
    fun `a running time is read the way linked data writes it`() {
        assertEquals(753_000L, TubePlayerParser.millisOf("PT0H12M33S"))
        assertEquals(3_600_000L, TubePlayerParser.millisOf("PT1H"))
        assertEquals(90_000L, TubePlayerParser.millisOf("PT1M30S"))
        assertNull(TubePlayerParser.millisOf("PT0S"))
        assertNull(TubePlayerParser.millisOf("twelve minutes"))
    }
}
