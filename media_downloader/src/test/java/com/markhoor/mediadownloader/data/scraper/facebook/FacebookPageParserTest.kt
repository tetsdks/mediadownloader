package com.markhoor.mediadownloader.data.scraper.facebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FacebookPageParserTest {

    @Test
    fun `a video page gives its HD and SD files, title and cover`() {
        val page = """
            <meta property="og:title" content="1.2K views | Someone on Reels">
            {"browser_native_hd_url":"https:\/\/video.xx.fbcdn.net\/o1\/v\/hd.mp4?efg=eyJ%3D&oe=6791",
             "browser_native_sd_url":"https:\/\/video.xx.fbcdn.net\/o1\/v\/sd.mp4?_nc=1",
             "image":{"uri":"https:\/\/scontent.xx.fbcdn.net\/cover.jpg"}}
        """.trimIndent()
        val media = FacebookPageParser.parse(page)!!

        assertEquals(listOf("HD", "SD"), media.qualities.map { it.label })
        assertEquals("https://video.xx.fbcdn.net/o1/v/hd.mp4?efg=eyJ%3D&oe=6791", media.qualities[0].url)
        assertEquals("https://scontent.xx.fbcdn.net/cover.jpg", media.thumbnailUrl)
        // The reel's stats are not part of its name.
        assertEquals("Someone on Reels", media.title)
    }

    /** A login wall carries none of the markers; nothing of the page may pass for a url. */
    @Test
    fun `a page without the markers gives nothing`() {
        assertNull(FacebookPageParser.parse("<html><title>Log into Facebook</title></html>"))
    }
}
