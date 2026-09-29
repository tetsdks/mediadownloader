package com.markhoor.mediadownloader.data.scraper.pornhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PornhubParserTest {

    @Test
    fun `the player object is read out of the page`() {
        val page = """
            <html><body><script>
                var flashvars_977717220311377955 = {"video_title":"A video","video_duration":141,
                "mediaDefinitions":[{"format":"hls","height":720,"videoUrl":"https://cdn.test/m.m3u8"}]};
                playerObjList.push(1);
            </script></body></html>
        """.trimIndent()

        val json = PornhubParser.flashvarsJsonOf(page)!!

        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains(""""video_title":"A video""""))
        // Nothing after the assignment comes along.
        assertTrue(!json.contains("playerObjList"))
    }

    @Test
    fun `a brace inside a title does not end the object`() {
        val page = """var flashvars_1 = {"video_title":"Braces } and { more","video_duration":7};"""

        val player = PornhubParser.flashvarsJsonOf(page)!!

        assertEquals("""{"video_title":"Braces } and { more","video_duration":7}""", player)
    }

    @Test
    fun `an escaped quote inside a title does not end the string`() {
        val page = """var flashvars_1 = {"video_title":"She said \"hi\" }","video_duration":3};"""

        val player = PornhubParser.flashvarsJsonOf(page)!!

        assertTrue(player.endsWith(""""video_duration":3}"""))
    }

    @Test
    fun `nested objects are counted, not the first closing brace`() {
        val page = """var flashvars_22 = {"a":{"b":{"c":1}},"video_duration":9};"""

        val player = PornhubParser.flashvarsJsonOf(page)!!

        assertEquals("""{"a":{"b":{"c":1}},"video_duration":9}""", player)
    }

    @Test
    fun `a page without the player object gives nothing`() {
        assertNull(PornhubParser.flashvarsJsonOf("<html><body>Video unavailable</body></html>"))
    }

    @Test
    fun `an object that is never closed gives nothing rather than the rest of the page`() {
        assertNull(PornhubParser.flashvarsJsonOf("""var flashvars_3 = {"video_title":"cut off"""))
    }
}
