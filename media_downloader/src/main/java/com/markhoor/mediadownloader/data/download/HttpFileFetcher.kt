package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.core.toRangeHeader
import com.markhoor.mediadownloader.data.network.HttpStatusException
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileOutputStream

/**
 * What a server says about a file before it is fetched.
 *
 * @param length the full size, when the server states it.
 * @param servesRanges a ranged request was actually answered with 206. An `Accept-Ranges`
 *   header alone proves nothing: pexels' CDN advertises ranges and answers every one with the
 *   whole file, which once saved a 2.4 MB photo as nine copies of itself.
 */
internal data class RemoteFile(val length: Long?, val servesRanges: Boolean)

/** A ranged request answered with the whole file. Nothing was written. */
internal class RangeIgnoredException : Exception("The server ignored the requested range")

/** Streams files from the network onto disk. */
internal class HttpFileFetcher(private val client: HttpClient) {

    /** Asks for the first byte only, which answers both questions at once. */
    suspend fun probe(url: String, headers: Map<String, String>): RemoteFile =
        client.prepareGet(url) {
            applyHeaders(this, headers, range = 0L..0L)
        }.execute { response ->
            when {
                response.status == HttpStatusCode.PartialContent ->
                    RemoteFile(length = totalFromContentRange(response), servesRanges = true)
                response.status.isSuccess() ->
                    RemoteFile(length = response.contentLength()?.takeIf { it > 0 }, servesRanges = false)
                else -> throw HttpStatusException(response.status.value, url)
            }
        }

    /**
     * Streams [url] into [target], reporting each block written to [onBytes].
     *
     * With a [range] the bytes are appended to [target], and anything but a 206 answer for that
     * range's first byte throws [RangeIgnoredException] before a byte is written - appending a
     * whole file, or another part of it, would corrupt it. No more than the range asks for is
     * written, whatever the server sends. Without a range, [target] is started over.
     */
    suspend fun fetchInto(
        target: File,
        url: String,
        headers: Map<String, String>,
        range: LongRange? = null,
        onBytes: (Long) -> Unit,
    ) {
        client.prepareGet(url) {
            applyHeaders(this, headers, range)
        }.execute { response ->
            val status = response.status
            if (!status.isSuccess()) throw HttpStatusException(status.value, url)
            if (range != null) {
                if (status != HttpStatusCode.PartialContent) throw RangeIgnoredException()
                val servedFrom = startOfContentRange(response)
                if (servedFrom != null && servedFrom != range.first) throw RangeIgnoredException()
            }

            target.parentFile?.mkdirs()
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(Download.BUFFER_BYTES)
            var remaining = range?.takeIf { it.last != Long.MAX_VALUE }?.let { it.last - it.first + 1 } ?: Long.MAX_VALUE
            FileOutputStream(target, range != null).use { output ->
                while (remaining > 0) {
                    val read = channel.readAvailable(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    remaining -= read
                    onBytes(read.toLong())
                }
            }
        }
    }

    private fun applyHeaders(request: HttpRequestBuilder, headers: Map<String, String>, range: LongRange?) {
        headers.forEach { (name, value) -> request.header(name, value) }
        // A compressed body has no length to check against, and cannot be resumed by offset.
        request.header(HttpHeaders.AcceptEncoding, "identity")
        range?.let { request.header(HttpHeaders.Range, it.toRangeHeader()) }
    }

    /** `Content-Range: bytes 100-199/12345` → 100. */
    private fun startOfContentRange(response: HttpResponse): Long? =
        response.headers[HttpHeaders.ContentRange]?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()

    /** `Content-Range: bytes 0-0/12345` → 12345. */
    private fun totalFromContentRange(response: HttpResponse): Long? =
        response.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }
}
