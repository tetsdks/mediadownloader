package com.markhoor.mediadownloader.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which statuses end a download instead of retrying it four more times over a growing backoff.
 * A private or deleted post answers 400 or 404; a spent signed link answers 403.
 */
class HttpStatusExceptionTest {

    private fun gone(status: Int) = HttpStatusException(status, "https://cdn.test/v.mp4").isMediaGone()

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
}
