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

    /**
     * KVS - the script most of these sites run - writes its files into the page outright, at a
     * path ending in `.mp4/`. The qualities are separate files, best last in the page.
     */
    @Test
    fun `a file the page names outright is read`() {
        val page = """
            <html><head><title>5 big tit milfs - PornTrex</title></head><body><script>
            rnd: '1790669502',
            video_url: 'https://www.porntrex.com/get_file/5/c91f46/3347000/3347669/3347669.mp4/',
            postfix: '.mp4',
            video_alt_url: 'https://www.porntrex.com/get_file/5/58ee1a/3347000/3347669/3347669_720p.mp4/',
            preview_url: 'https://www.porntrex.com/contents/videos/tmb/3347669/preview.mp4',
            </script></body></html>
        """.trimIndent()

        val media = TubePlayerParser.fromNamedFile(page, "https://www.porntrex.com/video/3347669/5-big-tit-milfs")!!

        // Every file the page names, best first, and never the hovered thumbnail's clip.
        assertEquals(listOf("720p", "HD"), media.qualities.map { it.label })
        assertEquals(
            "https://www.porntrex.com/get_file/5/58ee1a/3347000/3347669/3347669_720p.mp4",
            media.qualities.first().url,
        )
        assertTrue(media.qualities.none { it.url.contains("/tmb/") })
        assertTrue(media.title!!.startsWith("5 big tit milfs"))
    }

    @Test
    fun `a file on somebody else's host is not the page's video`() {
        val page = """
            <script>var ad = 'https://z6v2p9a8.bkcdn.net/library/773428/399ec545.mp4';</script>
        """.trimIndent()

        assertNull(TubePlayerParser.fromNamedFile(page, "https://www.porntrex.com/video/1/a"))
    }

    /**
     * A site that hands its player encrypted urls still asks the browser to start fetching the
     * stream before the player is built. The advert before the video belongs to another network
     * and is never preloaded, so this is the video.
     */
    @Test
    fun `a stream the page preloads is read`() {
        val page = """
            <html><head><title>I share a bed - xHamster</title>
            <link rel="preload" as="image" href="https://ic.test/028/031/985/1280x720.jpg">
            <link rel="preload" as="fetch" href="https://video-nss-b.test/Ns-p2g==,1790690400/media=hls4/multi=256x144:144p:,1920x1080:1080p:/028/031/985/_TPL_.h264.mp4.m3u8">
            <meta property="og:image" content="https://ic.test/cover.jpg">
            </head><body></body></html>
        """.trimIndent()

        val media = TubePlayerParser.fromPreloadedStream(page, "https://xhamster46.desi/videos/a-video-xh1")!!

        assertTrue(media.qualities.single().url.endsWith("_TPL_.h264.mp4.m3u8"))
        assertEquals("https://ic.test/cover.jpg", media.thumbnailUrl)
        assertTrue(media.title!!.startsWith("I share a bed"))
    }

    @Test
    fun `a page that preloads only its pictures has no stream to give`() {
        val page = """
            <link rel="preload" as="image" href="https://ic.test/cover.jpg">
            <link rel="preload" as="font" href="https://static.test/a.woff2">
        """.trimIndent()

        assertNull(TubePlayerParser.fromPreloadedStream(page, "https://xhamster.com/videos/a-video-xh1"))
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

}
