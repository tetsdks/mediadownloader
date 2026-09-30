package com.markhoor.mediadownloader.data.browser

import com.markhoor.mediadownloader.domain.policy.RestrictedCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SniffPolicyTest {

    private val policy = testPolicy()

    @Test
    fun `each site gets its own script, every other allowed site the generic one`() {
        val resource = "https://cdn.example/app.jpg"
        mapOf(
            "https://m.facebook.com/watch" to PageScript.Facebook,
            "https://www.instagram.com/explore/" to PageScript.Instagram,
            "https://x.com/home" to PageScript.Twitter,
            "https://www.threads.com/@a" to PageScript.Threads,
            "https://www.tiktok.com/foryou" to PageScript.TikTok,
            "https://www.tiktok.com/discover" to PageScript.Generic,
            "https://rumble.com/videos" to PageScript.Generic,
            "https://www.reddit.com/r/aww/" to PageScript.Generic,
        ).forEach { (page, script) -> assertEquals(page, script, policy.scriptFor(page, resource)) }
    }

    @Test
    fun `no script, no sniffing and no button on a blocked or unsupported page`() {
        listOf(
            "https://www.pornhub.com/view_video.php?viewkey=1",
            "https://sex.com/a/status/1",
            "https://xhamster19.com/videos/a",
            "https://www.youtube.com/watch?v=1",
            "https://stackoverflow.com/questions/1",
            "about:blank",
        ).forEach { page ->
            assertNull(page, policy.scriptFor(page, "https://cdn.test/a.js"))
            assertFalse(page, policy.mayTake("https://cdn.test/v.mp4", page))
            assertFalse(page, policy.allowsNativeButton(page))
        }
    }

    @Test
    fun `x dot com is twitter and sex dot com is not`() {
        assertEquals(PageScript.Twitter, policy.scriptFor("https://x.com/a/status/1", "https://abs.twimg.com/x.js"))
        assertNull(policy.scriptFor("https://sex.com/a/status/1", "https://abs.twimg.com/x.js"))
    }

    @Test
    fun `site scripts wait for something worth scanning`() {
        assertNull(policy.scriptFor("https://m.facebook.com/", "https://static.xx.fbcdn.net/rsrc.css"))
        assertEquals(PageScript.Generic, policy.scriptFor("https://www.reddit.com/", "https://www.redditstatic.com/a.css"))
    }

    @Test
    fun `the host's button stays off the script sites`() {
        assertFalse(policy.allowsNativeButton("https://www.instagram.com/p/abc/"))
        assertTrue(policy.allowsNativeButton("https://www.dailymotion.com/video/x8ixblz"))
        assertTrue(policy.usesGenericScript("https://rumble.com/videos"))
        assertFalse(policy.usesGenericScript("https://www.tiktok.com/@a/video/1"))
    }

    @Test
    fun `requests are media, a parser link, or nothing`() {
        val page = "https://www.reddit.com/r/videos/"
        assertEquals(SniffedRequest.Media("https://v.redd.it/x/HLSPlaylist.m3u8"), policy.classify("https://v.redd.it/x/HLSPlaylist.m3u8", page))
        assertEquals(SniffedRequest.Media("https://hugh.cdn.rumble.cloud/video/a.mp4"), policy.classify("https://hugh.cdn.rumble.cloud/video/a.mp4", page))
        assertEquals(SniffedRequest.Ignore, policy.classify("https://hugh.cdn.rumble.cloud/rac/creatives/31/a.mp4", page))
        assertEquals(SniffedRequest.Ignore, policy.classify("https://rr1.googlevideo.com/videoplayback?x=1", page))
        assertEquals(SniffedRequest.Ignore, policy.classify("https://vod3.cf.dmcdn.net/sec(abc)/x.m3u8", page))
        assertEquals(SniffedRequest.Ignore, policy.classify("https://cdn.test/audio.m3u8", page))
        // Imdb's few-second autoplay preview; the trailer is on the video's own page.
        assertEquals(
            SniffedRequest.Ignore,
            policy.classify("https://imdb-video.media-imdb.com/vi123/hls-preview-abc.m3u8", page),
        )
        assertEquals(
            SniffedRequest.ParserLink("https://www.dailymotion.com/player/metadata/video/x8ixblz?app=a"),
            policy.classify("https://www.dailymotion.com/player/metadata/video/x8ixblz?app=a", "https://www.dailymotion.com/"),
        )
        assertEquals(
            SniffedRequest.Ignore,
            policy.classify("https://www.dailymotion.com/player/metadata/video/x8ixblz", "https://www.dailymotion.com/video/x8ixblz"),
        )
    }

    @Test
    fun `on a page the parser reads, only tiktok's own video files are taken`() {
        val tiktok = "https://www.tiktok.com/@a/video/7684717011754093844"
        assertTrue(policy.mayTake("https://v16-webapp-prime.tiktok.com/video/tos/a", tiktok))
        assertFalse(policy.mayTake("https://cdn.test/other.mp4", tiktok))
        assertTrue(policy.mayTake("https://cdn.test/other.mp4", "https://rumble.com/videos"))
    }

    @Test
    fun `only a video's own page on imdb holds one media`() {
        val policy = testPolicy()
        assertTrue(policy.pageShowsOneMedia("https://www.imdb.com/video/vi3877612057/"))
        // Its listings play trailers inside them; a film's page shows the trailer's poster.
        assertFalse(policy.pageShowsOneMedia("https://www.imdb.com/whats-on-tv/fall-tv-guide/"))
        assertFalse(policy.pageShowsOneMedia("https://www.imdb.com/title/tt0111161/"))
    }

    @Test
    fun `a parser site says where its posts live`() {
        val policy = testPolicy()
        // Pinterest's category tiles are pictures inside links to /ideas/...; only /pin/ is a post.
        assertEquals("/pin/", policy.postPathFor("https://www.pinterest.com/ideas/"))
        // A short link is one post already, so it needs no hint about where posts live.
        assertEquals("", policy.postPathFor("https://pin.it/abc"))
        assertEquals("/video/", policy.postPathFor("https://www.imdb.com/"))
        assertEquals("", policy.postPathFor("https://www.dailymotion.com/pk"))
        assertEquals("", policy.postPathFor("https://rumble.com/videos"))
    }

    @Test
    fun `one media's own page is told from a feed`() {
        assertTrue(policy.pageShowsOneMedia("https://rumble.com/v7exqzu-sorry-i-annoyed-you.html"))
        assertFalse(policy.pageShowsOneMedia("https://rumble.com/videos"))
        assertTrue(policy.pageShowsOneMedia("https://vimeo.com/22439234"))
        assertTrue(policy.pageShowsOneMedia("https://www.dailymotion.com/video/x8ixblz"))
        assertFalse(policy.pageShowsOneMedia("https://www.tiktok.com/discover"))
    }

    @Test
    fun `a video file the page's player asks for is media on any allowed site, an advert's is not`() {
        val policy = testPolicy()
        val file = "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4"
        assertEquals(SniffedRequest.Media(file), policy.classify(file, "https://archive.org/details/BigBuckBunny_124"))
        assertEquals(
            SniffedRequest.Ignore,
            policy.classify("https://imasdk.googleapis.com/js/core/bridge/preroll.mp4", "https://archive.org/details/x"),
        )
    }

    @Test
    fun `youtube's player files are ignored by default and taken once youtube is allowed`() {
        val file = "https://rr3---sn-a.googlevideo.com/videoplayback/file.mp4"
        val page = "https://www.dailymotion.com/video/x1"
        assertEquals(SniffedRequest.Ignore, testPolicy(strict = false).classify(file, page))
        assertEquals(
            SniffedRequest.Media(file),
            testPolicy(strict = false, allowed = setOf(RestrictedCategory.YouTube)).classify(file, page),
        )
    }
}
