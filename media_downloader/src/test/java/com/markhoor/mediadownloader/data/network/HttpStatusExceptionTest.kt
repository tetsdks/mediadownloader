package com.markhoor.mediadownloader.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which statuses end a download instead of retrying it four more times over a growing backoff,
 * and which of those are the site turning the request away rather than the media being absent.
 * A private or deleted post answers 400 or 404; a spent or refused signed link answers 401 or 403.
 */
class HttpStatusExceptionTest {

    private fun gone(status: Int) = HttpStatusException(status, "https://cdn.test/v.mp4").isMediaGone()

    private fun refused(status: Int) = HttpStatusException(status, "https://cdn.test/v.mp4").isRefused()

    @Test
    fun `a refusal about the media itself is final`() {
        listOf(400, 401, 403, 404, 410, 451).forEach {
            assertTrue("HTTP $it", gone(it))
        }
    }

    @Test
    fun `a timeout and a rate limit are worth asking again`() {
        assertFalse("HTTP 408", gone(408))
        assertFalse("HTTP 429", gone(429))
    }

    @Test
    fun `the server's own trouble is worth asking again`() {
        listOf(500, 502, 503, 504).forEach {
            assertFalse("HTTP $it", gone(it))
        }
    }

    @Test
    fun `a success or a redirect is not a refusal at all`() {
        listOf(200, 206, 301, 302, 304).forEach {
            assertFalse("HTTP $it", gone(it))
        }
    }

    @Test
    fun `being turned away is told apart from the media being absent`() {
        listOf(401, 403).forEach { assertTrue("HTTP $it", refused(it)) }
        listOf(400, 404, 410, 451).forEach { assertFalse("HTTP $it", refused(it)) }
    }

    @Test
    fun `a refusal still counts as gone, because a ranged part asks that question`() {
        // DirectFileDownloader reads a 403 on a part as "ask for the file whole instead": some
        // sites sign a link for one connection and turn the rest away. Splitting the two apart
        // here would take that fallback away, so the whole-download rule asks both questions.
        listOf(401, 403).forEach { assertTrue("HTTP $it", gone(it)) }
    }

    @Test
    fun `what ends a whole download is gone and not merely refused`() {
        fun endsIt(status: Int) = gone(status) && !refused(status)
        listOf(400, 404, 410, 451).forEach { assertTrue("HTTP $it", endsIt(it)) }
        // The Dailymotion case measured in October 2026: the CDN refused the manifest its own
        // metadata endpoint had just handed out. The video was there; the link was not honoured.
        listOf(401, 403).forEach { assertFalse("HTTP $it", endsIt(it)) }
        listOf(408, 429, 500, 503).forEach { assertFalse("HTTP $it", endsIt(it)) }
    }
}
