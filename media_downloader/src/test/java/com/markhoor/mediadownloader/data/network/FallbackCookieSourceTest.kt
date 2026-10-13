package com.markhoor.mediadownloader.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session the Instagram readers ask with. The user's own always wins; the one the host app
 * supplied stands in when the browser has no session, and only for Instagram's own urls - it is
 * one site's session and means nothing to another.
 */
class FallbackCookieSourceTest {

    private val reel = "https://www.instagram.com/reel/DQN9eI6CF55/"
    private val threadsPost = "https://www.threads.com/@someone/post/Dd6YnykGgJb"
    private val supplied = "sessionid=host-session; csrftoken=host-token"

    private fun browserHolding(cookie: String?) = CookieSource { cookie }

    @Test
    fun `the user's own session is used, not the supplied one`() {
        val source = FallbackCookieSource(browserHolding("sessionid=mine; mid=abc"), supplied)

        assertEquals("sessionid=mine; mid=abc", source.cookiesFor(reel))
    }

    /** What the WebView holds after browsing Instagram signed out: cookies, but no session. */
    @Test
    fun `cookies without a session are replaced by the supplied one`() {
        val source = FallbackCookieSource(browserHolding("csrftoken=abc; mid=xyz; ig_did=1"), supplied)

        assertEquals(supplied, source.cookiesFor(reel))
    }

    @Test
    fun `no cookies at all means the supplied one`() {
        val source = FallbackCookieSource(browserHolding(null), supplied)

        assertEquals(supplied, source.cookiesFor(reel))
    }

    /** An Instagram session is not a Threads session, however one account opens both. */
    @Test
    fun `another site is never given the supplied cookie`() {
        val source = FallbackCookieSource(browserHolding("csrftoken=abc"), supplied)

        assertEquals("csrftoken=abc", source.cookiesFor(threadsPost))
        assertNull(FallbackCookieSource(browserHolding(null), supplied).cookiesFor(threadsPost))
    }

    @Test
    fun `a host that supplied nothing changes nothing`() {
        listOf(null, "", "   ").forEach { nothing ->
            val source = FallbackCookieSource(browserHolding("csrftoken=abc"), nothing)

            assertEquals(nothing.toString(), "csrftoken=abc", source.cookiesFor(reel))
            assertNull(FallbackCookieSource(browserHolding(null), nothing).cookiesFor(reel))
        }
    }

    @Test
    fun `flushing still reaches the browser's own store`() {
        var flushed = false
        val browser = object : CookieSource {
            override fun cookiesFor(url: String): String? = null
            override fun flush() {
                flushed = true
            }
        }

        FallbackCookieSource(browser, supplied).flush()

        assertTrue(flushed)
    }
}
