package com.markhoor.mediadownloader.core

import com.markhoor.mediadownloader.domain.models.MediaType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtensionsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `normalizedHost strips scheme, www, case and a trailing dot`() {
        assertEquals("reddit.com", "https://www.Reddit.com./r/aww".normalizedHost())
        assertEquals("m.facebook.com", "m.facebook.com/watch".normalizedHost())
        assertEquals("bbc.co.uk", "https://www.bbc.co.uk/news?x=1#y".normalizedHost())
    }

    @Test
    fun `normalizedHost decodes punycode`() {
        assertEquals("bücher.de", "https://xn--bcher-kva.de/".normalizedHost())
    }

    @Test
    fun `the host a string opens with wins over a url quoted inside it`() {
        assertEquals("sex.com", "sex.com/?ref=https://x.com".normalizedHost())
        assertEquals("x.com", "https://x.com/?ref=https://sex.com".normalizedHost())
    }

    @Test
    fun `text that does not open with a host is read by its first url`() {
        assertEquals("x.com", "look at this https://x.com/someone/status/1".normalizedHost())
    }

    @Test
    fun `nothing host shaped has no host`() {
        listOf("", "   ", "about:blank", "data:text/html,x", "just words").forEach {
            assertNull(it, it.normalizedHost())
        }
    }

    @Test
    fun `the remembered answer is keyed on the url`() {
        repeat(3) {
            assertEquals("xvideos.com", "https://xvideos.com/a".normalizedHost())
            assertEquals("dailymotion.com", "https://www.dailymotion.com/video/x1".normalizedHost())
        }
    }

    @Test
    fun `isUnderAnyOf matches subdomains and never lookalikes`() {
        val domains = setOf("x.com", "reddit.com")
        assertTrue("x.com".isUnderAnyOf(domains))
        assertTrue("old.reddit.com".isUnderAnyOf(domains))
        assertFalse("sex.com".isUnderAnyOf(domains))
        assertFalse("notreddit.com".isUnderAnyOf(domains))
        assertFalse("reddit.com.evil.net".isUnderAnyOf(domains))
    }

    @Test
    fun `isSiteOf reads the host`() {
        assertTrue("https://www.tiktok.com/@a/video/1".isSiteOf("tiktok.com"))
        assertFalse("https://example.com/?share=tiktok.com".isSiteOf("tiktok.com"))
        assertFalse((null as String?).isSiteOf("tiktok.com"))
    }

    @Test
    fun `embeddedUrls lists the urls inside a text, not the text itself`() {
        assertEquals(
            listOf("https://inxxx.com/v/abc"),
            "https://example.com/go?ref=https://inxxx.com/v/abc".embeddedUrls().toList(),
        )
        assertEquals(
            listOf("https://x.com/a"),
            "look https://x.com/a now".embeddedUrls().toList(),
        )
    }

    @Test
    fun `embeddedUrls is bounded`() {
        val many = "https://a.com/" + (1..50).joinToString("") { "?u=https://b$it.com/" }
        assertEquals(Constants.Url.MAX_EMBEDDED_URLS, many.embeddedUrls().count())
    }

    @Test
    fun `urlPath reads the path`() {
        assertEquals("/videos", "https://rumble.com/videos".urlPath())
        assertEquals("/@a/video/1", "tiktok.com/@a/video/1".urlPath())
    }

    // region Text decoding
    // Raw strings below hold the text as a page writes it, backslashes and all.

    @Test
    fun `html entities decode, numeric and named, and direction marks are dropped`() {
        assertEquals("Tom & Jerry \"live\" – 🌅", "Tom &amp; Jerry &quot;live&quot; &ndash; &#x1F305;".decodeHtmlEntities())
        assertEquals("abc", "‏abc‎".decodeHtmlEntities())
        assertEquals("&unknown;", "&unknown;".decodeHtmlEntities())
    }

    @Test
    fun `json escapes decode, twice when a page nests json`() {
        assertEquals("https://a.com/x?e=%3D&b=1", """https:\/\/a.com\/x?e=%3D&b=1""".decodeJsonEscapes())
        assertEquals("\"q\"", """\\\"q\\\"""".decodeJsonEscapes(passes = 2))
    }

    @Test
    fun `an embedded url loses its escapes and its html ampersands`() {
        assertEquals("https://a.com/v.mp4?a=1&b=2", """https:\/\/a.com\/v.mp4?a=1&amp;b=2""".unescapeEmbeddedUrl())
    }

    @Test
    fun `a title keeps what an escape meant and loses blank runs`() {
        assertEquals("اردو", """اردو""".cleanTitle())
        assertEquals("a\nb", "a\n\n\nb".cleanTitle())
    }

    // endregion

    // region Html

    @Test
    fun `a page's title is its og title, without a reel's stats`() {
        assertEquals("Post name", """<meta property="og:title" content="Post name"><title>Facebook</title>""".titleFromHtml())
        assertEquals("Author on Reels", """<meta property="og:title" content="12K views | Author on Reels">""".titleFromHtml())
        assertEquals("Fallback", "<title>Fallback</title>".titleFromHtml())
        assertEquals("", "<html></html>".titleFromHtml())
    }

    @Test
    fun `a meta property is read and decoded`() {
        assertEquals("a&b", """<meta property="og:image" content="a&amp;b">""".metaProperty("og:image"))
        assertNull("<html></html>".metaProperty("og:image"))
    }

    // endregion

    // region Urls and media

    @Test
    fun `relative links resolve against the page they were found in`() {
        val base = "https://cdn.example.com/a/b/master.m3u8?t=1"
        assertEquals("https://cdn.example.com/a/b/low.m3u8", "low.m3u8".resolveAgainst(base))
        assertEquals("https://cdn.example.com/root.m3u8", "/root.m3u8".resolveAgainst(base))
        assertEquals("https://other.com/x", "https://other.com/x".resolveAgainst(base))
    }

    @Test
    fun `a link inherits the playlist's query only when it has none`() {
        assertEquals("https://c.com/s.ts?t=1", "https://c.com/s.ts".inheritQueryFrom("https://c.com/p.m3u8?t=1"))
        assertEquals("https://c.com/s.ts?own=2", "https://c.com/s.ts?own=2".inheritQueryFrom("https://c.com/p.m3u8?t=1"))
    }

    @Test
    fun `the link in shared text is found`() {
        assertEquals("https://x.com/a/status/1", "look at this https://x.com/a/status/1 wow".extractLink())
        assertEquals("pinterest.com/pin/1/", "  pinterest.com/pin/1/  ".extractLink())
    }

    @Test
    fun `only a scheme and no whitespace is a url`() {
        assertTrue("https://a.com/v.mp4".isHttpUrl())
        assertFalse("<html> https://a.com".isHttpUrl())
        assertFalse((null as String?).isHttpUrl())
    }

    @Test
    fun `hls playlists are recognised`() {
        assertTrue("https://a.com/master.m3u8?x=1".isHlsPlaylistUrl())
        assertTrue("https://a.com/hls/v1/manifest".isHlsPlaylistUrl())
        assertFalse("https://a.com/video.mp4".isHlsPlaylistUrl())
    }

    @Test
    fun `sizes that cannot be true are dropped`() {
        assertNull(0L.sensibleSize(isVideo = true))
        assertNull(652L.sensibleSize(isVideo = true))
        assertEquals(652L, 652L.sensibleSize(isVideo = false))
        assertNull((40L * 1024 * 1024 * 1024).sensibleSize(isVideo = true))
        assertEquals(5_000_000L, 5_000_000L.sensibleSize(isVideo = true))
        assertNull((null as Long?).sensibleSize(isVideo = true))
    }

    @Test
    fun `a quality is named for its short side`() {
        assertEquals("720p", "720x1280".qualityNameFromResolution())
        assertEquals("720p", "1280x720".qualityNameFromResolution())
        assertEquals("HD", "HD".qualityNameFromResolution())
    }

    // endregion

    // region Files and downloads

    @Test
    fun `a title becomes a file name a file system accepts`() {
        assertEquals("a b c d", """a/b\c:d""".toFileNameBase())
        assertEquals("Reel by Author", "  ..Reel   by\nAuthor ".toFileNameBase())
        assertEquals("", "???".toFileNameBase())
        assertEquals("karish🧌🤣 foryou 100 real", "karish🧌🤣#foryou 100%real".toFileNameBase())
        assertEquals(50, "x".repeat(500).toFileNameBase().length)
    }

    @Test
    fun `a url's own extension is kept only when it is a media one`() {
        assertEquals("webm", "https://a.com/v.webm?t=1".mediaExtension(MediaType.Video))
        assertEquals("mp4", "https://a.com/v/abc123.hash".mediaExtension(MediaType.Video))
        assertEquals("jpg", "https://64.media.tumblr.com/a/s640x960/b.pnj".mediaExtension(MediaType.Image))
        assertEquals("jpg", "https://a.com/image".mediaExtension(MediaType.Image))
    }

    @Test
    fun `a file's first bytes name its type`() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        assertEquals("mp4", bytes(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D).sniffedExtension())
        assertEquals("jpg", bytes(0xFF, 0xD8, 0xFF, 0xE0).sniffedExtension())
        assertEquals("png", bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A).sniffedExtension())
        assertEquals("webp", "RIFF1234WEBPVP8 ".toByteArray().sniffedExtension())
        assertNull(bytes(0x47, 0x40, 0x00).sniffedExtension())
        assertNull(ByteArray(0).sniffedExtension())
    }

    @Test
    fun `a download is filed under its site, by host`() {
        assertEquals("Twitter X", "https://x.com/a/status/1".siteFolderName())
        assertEquals("Website", "https://sex.com/a".siteFolderName())
        assertEquals("Website", "https://netflix.com/a".siteFolderName())
        assertEquals("Daily Motion", "https://www.dailymotion.com/video/x1".siteFolderName())
    }

    @Test
    fun `a taken name gets the next free number`() {
        val dir = temp.newFolder()
        assertEquals("v.mp4", dir.freeFile("v", "mp4").name)
        File(dir, "v.mp4").createNewFile()
        File(dir, "v (1).mp4").createNewFile()
        assertEquals("v (2).mp4", dir.freeFile("v", "mp4").name)
    }

    @Test
    fun `files join in the order given`() {
        val dir = temp.newFolder()
        val parts = listOf("ab", "cd", "e").mapIndexed { index, text -> File(dir, "$index").apply { writeText(text) } }
        val joined = File(dir, "out/joined")
        parts.joinInto(joined)
        assertArrayEquals("abcde".toByteArray(), joined.readBytes())
    }

    @Test
    fun `ranges and playlist entries read right`() {
        assertEquals("bytes=0-99", (0L..99L).toRangeHeader())
        assertEquals("bytes=500-", (500L..Long.MAX_VALUE).toRangeHeader())
        assertTrue("https://a.com/subs/en.vtt?x=1".isSubtitleUrl())
        assertTrue("https://a.com/low.m3u8?t=1".isPlaylistEntryUrl())
        assertTrue("https://fastly.brightcovecdn.com/media/v1/hls/v4/clear/abc".isPlaylistEntryUrl())
        assertFalse("https://v.pinimg.com/videos/iht/hls/a_240w.cmfv".isPlaylistEntryUrl())
        assertFalse("https://a.com/seg1.ts".isPlaylistEntryUrl())
    }

    // endregion

    @Test
    fun `a thumbnail is an image, linked or small and inline, never a stream`() {
        assertTrue("https://i.pinimg.com/videos/thumbnails/originals/7a/d7/98/abc.0000000.jpg".isUsableThumbnail())
        assertTrue("https://s2.dmcdn.net/v/UkYi71clTvHU-p1wP/x360".isUsableThumbnail())
        assertTrue("data:image/jpeg;base64,/9j/4AAQSkZJRg==".isUsableThumbnail())
        assertFalse("https://v1.pinimg.com/videos/iht/hls/7a/d7/98/abc_240w.m3u8".isUsableThumbnail())
        assertFalse("https://cdn.test/video.mp4".isUsableThumbnail())
        assertFalse(("data:image/png;base64," + "A".repeat(20_000)).isUsableThumbnail())
        assertFalse("blob:https://x.com/1".isUsableThumbnail())
        assertFalse((null as String?).isUsableThumbnail())
    }

    @Test
    fun `page text becomes a title without its decoration, and an attribution is no title`() {
        assertEquals("The Shawshank Redemption - Official Trailer", "▶️ The Shawshank Redemption - Official Trailer".asMediaTitle())
        assertEquals("Breaking news", " • Breaking news ".asMediaTitle())
        assertEquals("A walk by the sea", "Description: A walk by the sea".asMediaTitle())
        assertEquals("Title Fight highlights", "Title Fight highlights".asMediaTitle())
        assertEquals("", "Source www.reddit.com".asMediaTitle())
        assertEquals("", "via giphy.com".asMediaTitle())
        assertEquals("", "https://example.com/watch?v=1".asMediaTitle())
        // Real titles that only look like addresses, or start with a sign that belongs to them.
        assertEquals("Mr.Bean at the beach", "Mr.Bean at the beach".asMediaTitle())
        assertEquals("#1 trick for cats", "#1 trick for cats".asMediaTitle())
        assertEquals("(Official Video) Song", "(Official Video) Song".asMediaTitle())
        assertEquals("مرحبا بالعالم", "مرحبا بالعالم".asMediaTitle())
        assertEquals("Cat Working GIF", "Cat Working GIF - Find &amp; Share on GIPHY".asMediaTitle())
        assertEquals("How to share with just friends.", "How to share with just friends. | Facebook".asMediaTitle())
        assertEquals("Big Buck Bunny", "Big Buck Bunny : Free Download, Borrow, and Streaming : Internet Archive".asMediaTitle())
        assertEquals("Why I left Facebook for good", "Why I left Facebook for good".asMediaTitle())
        assertEquals("Facebook", "Facebook".asMediaTitle())
        assertEquals(
            "Microsoft Defender for Cloud Copilot Incident Response",
            "Microsoft Defender for Cloud Copilot Incident Response | Microsoft Mechanics posted on the topic | LinkedIn".asMediaTitle(),
        )
        assertEquals(
            "Ancient space rocks really rock! #space #nasa",
            "Ancient space rocks really rock! #space #nasa\\\"},\\\"comment_count\\\":17,\\\"like_count\\\":8402}".asMediaTitle(),
        )
        assertEquals("She said \"hi\", then left", "She said \"hi\", then left".asMediaTitle())
        assertEquals("Cloud Computing Services | Microsoft Azure", "Cloud Computing Services | Microsoft Azure | Microsoft Azure".asMediaTitle())
        assertEquals("", "sound is on. click to mute sound.".asMediaTitle())
        assertEquals("", "Play".asMediaTitle())
        assertEquals("Playing with fire: a documentary", "Playing with fire: a documentary".asMediaTitle())
    }

    @Test
    fun `a video file is known by its path, not its host`() {
        assertTrue("https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4".isVideoFileUrl())
        assertTrue("https://videos.pexels.com/video-files/1918465/1918465-hd_1920_1080_24fps.mp4?token=x".isVideoFileUrl())
        assertTrue("https://upload.wikimedia.org/wikipedia/commons/a/a1/Clip.WEBM".isVideoFileUrl())
        assertFalse("https://example.com/player.js".isVideoFileUrl())
        assertFalse("https://example.com/watch?file=a.mp4".isVideoFileUrl())
        assertFalse("https://example.com/stream.m3u8".isVideoFileUrl())
        assertTrue("https://thisvid.com/get_file/5/1071bce9/14758000/14758512.mp4/?rnd=1".isVideoFileUrl())
    }

    @Test
    fun `a page sent in a file's place is told by its first byte`() {
        assertTrue("<!DOCTYPE html><html>".toByteArray().looksLikeMarkup())
        assertTrue("﻿  \n<html>".toByteArray().looksLikeMarkup())
        assertFalse(byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70).looksLikeMarkup())
        assertFalse(byteArrayOf(0x47, 0x40, 0x11, 0x10).looksLikeMarkup())
        assertFalse(ByteArray(0).looksLikeMarkup())
    }

    @Test
    fun `a stream's pieces are not video files, though they end in mp4`() {
        assertFalse("https://video.cdn.test/030/512/480p.av1.mp4/init-v1-a1.mp4".isVideoFileUrl())
        assertFalse("https://video.cdn.test/v/init.mp4".isVideoFileUrl())
        assertFalse("https://media-hls.cdn.test/b-hls-16/279/279_240p_h264_init_BX06iq.mp4".isVideoFileUrl())
        assertFalse("https://video.cdn.test/v/720p.mp4/seg-3-v1-a1.mp4".isVideoFileUrl())
        assertTrue("https://video.cdn.test/v/initial-d-trailer.mp4".isVideoFileUrl())
        assertTrue("https://video.cdn.test/v/1080p.mp4?init=1".isVideoFileUrl())
    }

    @Test
    fun `a title that is a file name does not keep its extension`() {
        assertEquals("sample 5s 720p", "sample 5s 720p.mp4".toFileNameBase())
        assertEquals("clip", "clip.WEBM".toFileNameBase())
        assertEquals("Version 2.5 release", "Version 2.5 release".toFileNameBase())
    }
}
