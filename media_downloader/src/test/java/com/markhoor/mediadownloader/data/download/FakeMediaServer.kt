package com.markhoor.mediadownloader.data.download

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Collections

/**
 * Serves [files] by url, honouring `Range` requests unless told not to - which is how a CDN that
 * advertises ranges and then ignores them is played.
 *
 * @param ignoresRangesAfter answer ranged requests with the whole file once this many were honoured.
 */
internal class FakeMediaServer(
    private val files: Map<String, ByteArray>,
    private val ignoresRangesAfter: Int = Int.MAX_VALUE,
) {
    /** Every request as `"<url> <Range or ->"`, in the order they arrived. */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var rangesHonoured = 0

    val client = HttpClient(MockEngine { request ->
        val url = request.url.toString()
        val range = request.headers[HttpHeaders.Range]
        requests += "$url ${range ?: "-"}"
        val body = files[url] ?: return@MockEngine respond("", HttpStatusCode.NotFound)

        val honour = range != null && synchronized(this@FakeMediaServer) { rangesHonoured++ < ignoresRangesAfter }
        if (range != null && honour) {
            val (start, endOrNull) = range.removePrefix("bytes=").split('-').let { it[0].toLong() to it[1].toLongOrNull() }
            val end = minOf(endOrNull ?: (body.size - 1L), body.size - 1L)
            val slice = body.copyOfRange(start.toInt(), end.toInt() + 1)
            respond(
                slice,
                HttpStatusCode.PartialContent,
                headersOf(
                    HttpHeaders.ContentRange to listOf("bytes $start-$end/${body.size}"),
                    HttpHeaders.ContentLength to listOf(slice.size.toString()),
                ),
            )
        } else {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        }
    })
}

/** Bytes that differ at every offset a test is likely to split at, so a misplaced part shows. */
internal fun sampleBytes(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 251).toByte() }
