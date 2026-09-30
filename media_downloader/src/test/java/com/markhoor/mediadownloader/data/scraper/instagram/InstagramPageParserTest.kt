package com.markhoor.mediadownloader.data.scraper.instagram

import com.markhoor.mediadownloader.domain.models.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstagramPageParserTest {

    /** The web-info JSON a signed-in reel page embeds, urls escaped as the page escapes them. */
    private val signedInReel = """
        <!DOCTYPE html><html><script>
        {"xdt_api__v1__media__shortcode__web_info": {"x":1, "items": [{"caption": {"text": "A café at night 🌙"},
        "video_versions": [{"type":101, "url": "https:\/\/scontent.cdninstagram.com\/v\/reel.mp4?efg=abc%3D&oh=1"}],
        "image_versions2": {"candidates": [{"url": "https:\/\/scontent.cdninstagram.com\/v\/cover.jpg"}]}}]}
        </script></html>
    """.trimIndent()

    @Test
    fun `a signed-in reel gives its video, cover and caption`() {
        val media = InstagramPageParser.parseSignedInPage(signedInReel)!!
        val quality = media.qualities.single()
        assertEquals("https://scontent.cdninstagram.com/v/reel.mp4?efg=abc%3D&oh=1", quality.url)
        assertEquals(MediaType.Video, quality.type)
        assertEquals("https://scontent.cdninstagram.com/v/cover.jpg", media.thumbnailUrl)
        assertEquals("A café at night 🌙", media.title)
    }

    @Test
    fun `without web-info the first video on the page is used`() {
        val page = """
            <html><meta property="og:image" content="https://cdn.example/og.jpg">
            "video_versions":[{"url":"https:\/\/cdn.example\/v.mp4"}]</html>
        """.trimIndent()
        val media = InstagramPageParser.parseSignedInPage(page)!!
        assertEquals("https://cdn.example/v.mp4", media.qualities.single().url)
        assertEquals("https://cdn.example/og.jpg", media.thumbnailUrl)
    }

    @Test
    fun `a picture post gives its picture`() {
        val page = """<html>"image_versions2":{"candidates":[{"url":"https:\/\/cdn.example\/p.jpg"}]}</html>"""
        val media = InstagramPageParser.parseSignedInPage(page)!!
        assertEquals(MediaType.Image, media.qualities.single().type)
        assertEquals("https://cdn.example/p.jpg", media.thumbnailUrl)
    }

    /** "<account> on Instagram:" names the account, not the post. */
    @Test
    fun `labels naming the account are not taken for a caption`() {
        val page = """
            <html><meta property="og:title" content="someone on Instagram: something">
            <meta name="twitter:description" content="The actual words of this post">
            "video_versions":[{"url":"https:\/\/cdn.example\/v.mp4"}]</html>
        """.trimIndent()
        assertEquals("The actual words of this post", InstagramPageParser.parseSignedInPage(page)!!.title)
    }

    @Test
    fun `a page naming no media gives nothing`() {
        assertNull(InstagramPageParser.parseSignedInPage("<html><body>Log in</body></html>"))
    }

    @Test
    fun `the og caption is lifted out of instagram's boilerplate`() {
        val page = """<meta property="og:description" content="12K likes - someone on May 1: &quot;Sunset &#x1F305; over the bay&quot;">"""
        assertEquals("Sunset 🌅 over the bay", InstagramPageParser.ogCaption(page))
    }

    @Test
    fun `post urls are read and rebuilt`() {
        assertEquals("abc_12", InstagramPageParser.shortcodeOf("https://www.instagram.com/reels/abc_12/?igsh=x"))
        assertEquals("xyz", InstagramPageParser.postIdOf("https://www.instagram.com/p/xyz/"))
        assertEquals(
            "https://www.instagram.com/reel/xyz/embed/",
            InstagramPageParser.canonicalReelUrl("https://www.instagram.com/p/xyz/?utm=1", embed = true),
        )
    }
}
