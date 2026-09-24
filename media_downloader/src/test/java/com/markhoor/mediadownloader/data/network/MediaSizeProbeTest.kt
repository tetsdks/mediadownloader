package com.markhoor.mediadownloader.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Real time, not a test scheduler: the probe races its requests against a timeout, and MockEngine
 * answers on a thread of its own. Under `runTest` the virtual clock reaches that timeout while the
 * answer is still on its way, so the probe reported nothing - a failure of the test, not the code.
 */
class MediaSizeProbeTest {

    /** Answers every request with [size] bytes of [type]. */
    private fun probeServing(type: String, size: Int = 145_000) = MediaSizeProbe(
        HttpClient(MockEngine {
            respond(
                ByteArray(size),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf(type), HttpHeaders.ContentLength to listOf(size.toString())),
            )
        }),
    )

    @Test
    fun `a video file is sized`() = runBlocking {
        assertEquals(145_000L, probeServing("video/mp4").sizeOf("https://cdn.test/v.mp4", acceptsImage = false))
    }

    @Test
    fun `a placeholder picture in a video's place is not its size`() = runBlocking {
        assertNull(probeServing("image/gif").sizeOf("https://tube.test/get_file/1/a/1.mp4/", acceptsImage = false))
    }

    @Test
    fun `a picture is sized when a picture is what is wanted`() = runBlocking {
        assertEquals(145_000L, probeServing("image/jpeg").sizeOf("https://cdn.test/a.jpg"))
    }

    @Test
    fun `a page sent in the file's place is never its size`() = runBlocking {
        assertNull(probeServing("text/html; charset=utf-8").sizeOf("https://cdn.test/v.mp4"))
    }
}
