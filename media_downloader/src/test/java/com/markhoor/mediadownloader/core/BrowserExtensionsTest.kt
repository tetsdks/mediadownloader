package com.markhoor.mediadownloader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtensionsTest {

    @Test
    fun `adverts and trackers are told from media by host and whole path segment`() {
        assertTrue("https://pubads.g.doubleclick.net/x.mp4".isAdvertMediaUrl())
        assertTrue("https://hugh.cdn.rumble.cloud/rac/creatives/31/c9/a.mp4".isAdvertMediaUrl())
        assertTrue("https://media-backend.bitchute.com/pixel.gif".isAdvertMediaUrl())
        assertTrue("https://svacdn77.tsyndicate.com/videos/6/1/abc/main.mp4".isAdvertMediaUrl())
        assertTrue("https://htl-cdn.adtng.com/1196799_video_with_sound.mp4".isAdvertMediaUrl())
        assertTrue("https://video.sacdnssedge.com/ol_79de3265.mp4".isAdvertMediaUrl())
        assertTrue("https://redirector.gvt1.com/videoplayback/id/7673e70a/aitags/15,18/source/dclk_video_ads/requiressl/yes".isAdvertMediaUrl())
        assertTrue("https://z6v2p9a8.bkcdn.net/library/953622/71fe022b6da0a40986b8b54614165155f1e14cf6.mp4".isAdvertMediaUrl())
        assertTrue("https://n2j9y0x0.bxcdn.net/2c8a6eb0756d725edb5849a5366175dc1881bb70.mp4".isAdvertMediaUrl())
        assertFalse("https://z6v2p9a8.bkcdn.net/videos/953622/clip.mp4".isAdvertMediaUrl())
        assertFalse("https://gcdn.drtuber.desi/mp4_lq/2c8a6eb0756d725edb5849a5366175dc1881bb70.mp4".isAdvertMediaUrl())
        assertFalse("https://cdn.test/downloads/video.mp4".isAdvertMediaUrl())
        assertFalse("https://notdoubleclick.net.example.com/v.mp4".isAdvertMediaUrl())
    }

    @Test
    fun `tiktok grids are not players`() {
        assertTrue("https://www.tiktok.com/discover".isTikTokGridPage())
        assertTrue("https://www.tiktok.com/@someone".isTikTokGridPage())
        assertFalse("https://www.tiktok.com/foryou".isTikTokGridPage())
        assertFalse("https://www.tiktok.com/@someone/video/7684717011754093844".isTikTokGridPage())
        assertFalse("https://www.tiktok.com/".isTikTokGridPage())
    }

    @Test
    fun `sniffed sites know their single media pages`() {
        assertTrue("https://rumble.com/v7frlhy-its-title.html".isSniffedSingleMediaPage())
        assertFalse("https://rumble.com/videos".isSniffedSingleMediaPage())
        assertTrue("https://www.ted.com/talks/someone_a_talk".isSniffedSingleMediaPage())
        assertTrue("https://9gag.com/gag/aXYZ".isSniffedSingleMediaPage())
        assertFalse("https://9gag.com/trending".isSniffedSingleMediaPage())
    }

    @Test
    fun `a rumble video's own page is one video, a listing is not`() {
        listOf(
            "https://rumble.com/v7frlhy-killer-fps-good-music-and-bad-game-play.html",
            "https://rumble.com/v7frpcy-must...-monitor...-situation.html",
            "https://rumble.com/v6xyz.html",
            "https://rumble.com/embed/v7frlhy/",
        ).forEach { assertTrue(it, it.isSniffedSingleMediaPage()) }
        listOf(
            "https://rumble.com/videos",
            "https://rumble.com/videos?sort=views&date=today",
            "https://rumble.com/",
            "https://rumble.com/c/SomeChannel",
            "https://rumble.com/user/someone",
        ).forEach { assertFalse(it, it.isSniffedSingleMediaPage()) }
    }

    @Test
    fun `static assets never reach the sniffer`() {
        assertTrue("https://cdn.test/app.min.js?v=3".isStaticAssetUrl())
        assertTrue("https://fonts.test/a.woff2".isStaticAssetUrl())
        assertFalse("https://cdn.test/v.mp4".isStaticAssetUrl())
        assertFalse("https://cdn.test/master.m3u8".isStaticAssetUrl())
    }

    @Test
    fun `segments and known cdn files are recognised`() {
        assertTrue("https://cdn.test/seg-12.ts?t=1".isHlsSegmentUrl())
        assertTrue("https://cdn.test/chunk.m4s".isHlsSegmentUrl())
        assertTrue("https://v16-webapp-prime.tiktok.com/video/tos/a/".isDirectMediaUrl())
        assertTrue("https://seed.bitchute.com/a/b.mp4".isDirectMediaUrl())
        assertFalse("https://media-backend.bitchute.com/a.mp4".isDirectMediaUrl())
        assertFalse("https://cdn.test/page.html".isDirectMediaUrl())
    }

    @Test
    fun `a permalink slug becomes a title, without the ids around it`() {
        assertEquals("sorry i annoyed you", "https://rumble.com/v7exqzu-sorry-i-annoyed-you.html".titleFromSlug())
        assertEquals("sunlight seen through leaves", "https://www.pexels.com/video/sunlight-seen-through-leaves-10395606/".titleFromSlug())
        assertEquals("a long walk home", "https://xhamster.com/videos/a-long-walk-home-xhvgYCw".titleFromSlug())
        assertEquals("the best of 2024 remix", "https://a.com/v/the-best-of-2024-remix".titleFromSlug())
        assertEquals("", "https://a.com/two-words".titleFromSlug())
        assertEquals("", (null as String?).titleFromSlug())
    }

    @Test
    fun `a written title that spells the slug out wins`() {
        assertTrue("vaibhav has to wait dont want to rush".isSpelledOutBy("Vaibhav has to wait; don't want to rush Hardik"))
        assertFalse("premium only".isSpelledOutBy("Premium Only Content"))
    }

    @Test
    fun `an embed page is not a media file, a stream manifest is`() {
        assertFalse("https://odysee.com/$/embed/@a/b".namesMediaFile())
        assertTrue("https://cdn.test/v.mp4?t=1".namesMediaFile())
        assertTrue("https://manifest.test/hls/v4/clear/abc".namesMediaFile())
        assertTrue("https://www.theguardian.com/world/video/a".isSamePageAs("https://www.theguardian.com/world/video/a/?x=1"))
    }

    @Test
    fun `image endings are read from the tail of any length`() {
        assertTrue(("https://a.com/" + "x".repeat(50_000) + ".jpg?w=1").looksLikeImageUrl())
        assertFalse("https://a.com/v.mp4".looksLikeImageUrl())
    }
}
