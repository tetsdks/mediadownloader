package com.markhoor.mediadownloader.data.network

import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.isHttpUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * How big a file is, without downloading it.
 *
 * Servers disagree about which question they answer, so three are asked at once and the first
 * real size wins: a HEAD, a one-byte range request, and a GET whose body is never read. Every
 * answer is checked for success first - a CDN's refusal has a small body whose length is not the
 * file's size - and for its type: a page or a placeholder picture sent in the file's place has a
 * length too, and it is not the file's.
 */
internal class MediaSizeProbe(private val client: HttpClient) {

    /**
     * The size in bytes, or `null` when no strategy could tell. [acceptsImage] is false for a video's
     * file: KVS tube sites answer a spent `get_file` link with a GIF, and its 145 kB was offered as
     * the video's size.
     */
    suspend fun sizeOf(url: String, headers: Map<String, String> = emptyMap(), acceptsImage: Boolean = true): Long? {
        if (!url.isHttpUrl()) return null
        return withTimeoutOrNull(Network.SIZE_PROBE_TIMEOUT_MS.milliseconds) { firstKnownSize(url, headers, acceptsImage) }
    }

    private suspend fun firstKnownSize(url: String, headers: Map<String, String>, acceptsImage: Boolean): Long? = coroutineScope {
        val answers = { response: HttpResponse -> response.isTheFile(acceptsImage) }
        val strategies: List<suspend () -> Long?> = listOf(
            { headSize(url, headers, answers) },
            { rangeSize(url, headers, answers) },
            { getSize(url, headers, answers) },
        )
        val sizes = Channel<Long?>(strategies.size)
        strategies.forEach { strategy -> launch { sizes.send(sizeOrNull(strategy)) } }
        repeat(strategies.size) {
            val size = sizes.receive()
            if (size != null && size > 0) {
                coroutineContext.cancelChildren()
                return@coroutineScope size
            }
        }
        null
    }

    private suspend fun headSize(url: String, headers: Map<String, String>, isTheFile: (HttpResponse) -> Boolean): Long? {
        val response = client.head(url) { applyProbeHeaders(this, headers) }
        return if (response.status.isSuccess() && isTheFile(response)) response.contentLength() else null
    }

    private suspend fun rangeSize(url: String, headers: Map<String, String>, isTheFile: (HttpResponse) -> Boolean): Long? =
        client.prepareGet(url) {
            applyProbeHeaders(this, headers)
            header(HttpHeaders.Range, "bytes=0-0")
        }.execute { response ->
            when {
                !isTheFile(response) -> null
                response.status == HttpStatusCode.PartialContent -> totalFromContentRange(response)
                response.status == HttpStatusCode.OK -> response.contentLength()
                else -> null
            }
        }

    /** The headers of a plain GET; the body is left unread and dropped with the response. */
    private suspend fun getSize(url: String, headers: Map<String, String>, isTheFile: (HttpResponse) -> Boolean): Long? =
        client.prepareGet(url) { applyProbeHeaders(this, headers) }.execute { response ->
            if (response.status.isSuccess() && isTheFile(response)) response.contentLength() else null
        }

    /** A page is never the file, and a picture is only when a picture is what is wanted. */
    private fun HttpResponse.isTheFile(acceptsImage: Boolean): Boolean {
        val type = contentType() ?: return true
        return !type.match(ContentType.Text.Html) && (acceptsImage || type.contentType != ContentType.Image.Any.contentType)
    }

    private fun applyProbeHeaders(request: HttpRequestBuilder, headers: Map<String, String>) {
        request.timeout { requestTimeoutMillis = Network.SIZE_PROBE_TIMEOUT_MS }
        // Compressed transfer hides the real length.
        request.header(HttpHeaders.AcceptEncoding, "identity")
        headers.forEach { (name, value) -> request.header(name, value) }
    }

    private fun totalFromContentRange(response: HttpResponse): Long? =
        response.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()

    private suspend fun sizeOrNull(strategy: suspend () -> Long?): Long? = try {
        strategy()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
