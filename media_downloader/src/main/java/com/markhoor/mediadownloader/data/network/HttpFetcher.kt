package com.markhoor.mediadownloader.data.network

import com.markhoor.mediadownloader.core.Constants.Network
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.charset
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

/**
 * Fetches pages and api answers as text, never more than [maxResponseBytes] of one (less on a
 * low-end device). Every failure - a network error, an oversized body, even
 * an OutOfMemoryError from one - comes back as [Result.failure]; cancellation is always rethrown.
 *
 * The status is not checked unless asked: several sites answer a readable page with a non-2xx
 * status, and each scraper decides from the content whether it found anything.
 */
internal class HttpFetcher(
    private val client: HttpClient,
    val json: Json,
    private val maxResponseBytes: Long = Network.MAX_RESPONSE_BYTES,
) {

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        requireSuccess: Boolean = false,
        maxBytes: Long = maxResponseBytes,
    ): Result<String> = fetch(url, HttpMethod.Get, headers, requireSuccess, maxBytes)

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): Result<String> = fetch(url, HttpMethod.Post, headers) {
        setBody(FormDataContent(parameters { form.forEach { (name, value) -> append(name, value) } }))
    }

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<String> = fetch(url, HttpMethod.Post, headers) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    suspend inline fun <reified T> getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<T> = getText(url, headers).mapCatching { json.decodeFromString<T>(it) }

    private suspend fun fetch(
        url: String,
        method: HttpMethod,
        headers: Map<String, String>,
        requireSuccess: Boolean = false,
        maxBytes: Long = maxResponseBytes,
        body: HttpRequestBuilder.() -> Unit = {},
    ): Result<String> = try {
        client.prepareRequest(url) {
            this.method = method
            headers.forEach { (name, value) -> header(name, value) }
            body()
        }.execute { response ->
            val declaredLength = response.contentLength()
            when {
                requireSuccess && !response.status.isSuccess() ->
                    Result.failure(HttpStatusException(response.status.value, url))
                declaredLength != null && declaredLength > maxBytes -> Result.failure(tooLarge(declaredLength))
                else -> {
                    // Streamed and cut off, not read whole: a body with no stated length is still bounded.
                    val bytes = response.bodyAsChannel().readBuffer(maxBytes + 1).readByteArray()
                    if (bytes.size > maxBytes) {
                        Result.failure(tooLarge(bytes.size.toLong()))
                    } else {
                        Result.success(String(bytes, response.charset() ?: Charsets.UTF_8))
                    }
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        // Throwable, not Exception: an oversized body raises OutOfMemoryError.
        Result.failure(error)
    }

    private fun tooLarge(bytes: Long) = IllegalStateException("Response of at least $bytes bytes is too large to read")
}

/** A response whose status said the request failed. */
internal class HttpStatusException(val status: Int, url: String) : Exception("HTTP $status for $url")

/**
 * Whether the status says the media is not there for us to take - a private or deleted post, a
 * signed link already spent - so asking again would be told the same thing. A timeout and a "too
 * many requests" are the two client errors worth another try. `HlsStreamDownloader` already reads a
 * segment's 4xx this way; this is the same rule for a whole download.
 */
internal fun HttpStatusException.isMediaGone(): Boolean =
    status in 400..499 && status != 408 && status != 429
