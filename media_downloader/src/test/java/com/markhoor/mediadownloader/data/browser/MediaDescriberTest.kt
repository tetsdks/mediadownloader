package com.markhoor.mediadownloader.data.browser

import com.markhoor.mediadownloader.data.hls.HlsQualityReader
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.network.MediaSizeProbe
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.domain.repo.MediaParserRepository
import com.markhoor.mediadownloader.domain.usecase.CheckSiteAccessUseCase
import com.markhoor.mediadownloader.domain.usecase.ParseLinkUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real time, not a test scheduler: the size probe races its requests against a timeout, and
 * MockEngine answers on a thread of its own - the same reason `MediaSizeProbeTest` says.
 */
class MediaDescriberTest {

    /** Comfortably over SMALLEST_REAL_VIDEO, so a real size is not refused as a placeholder. */
    private val size = 200_000

    /** Answers every request with [size] bytes of video, so every probe succeeds. */
    private fun describer(sizeProbesAtOnce: Int): MediaDescriber {
        val client = HttpClient(MockEngine {
            respond(
                ByteArray(size),
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("video/mp4"),
                    HttpHeaders.ContentLength to listOf(size.toString()),
                ),
            )
        })
        val probe = MediaSizeProbe(client)
        val parseLink = ParseLinkUseCase(
            CheckSiteAccessUseCase(strictSupportedSitesOnly = true, extraBlockedHosts = emptySet()),
            object : MediaParserRepository {
                override suspend fun parse(url: String) = Result.failure<MediaModel>(IllegalStateException("no parser"))
            },
        )
        return MediaDescriber(parseLink, HlsQualityReader(HttpFetcher(client, HttpClientFactory.json), probe), probe, sizeProbesAtOnce)
    }

    private fun withSound(count: Int) = MediaModel(
        title = "a video and its sound",
        thumbnailUrl = null,
        sourceUrl = "https://cdn.test/page",
        qualities = List(count) { index ->
            MediaQualityModel(
                url = "https://cdn.test/v$index.mp4",
                label = "${index}p",
                type = MediaType.Video,
                sizeBytes = null,
                audioUrl = "https://cdn.test/a$index.m4a",
            )
        },
    )

    /**
     * Sizing a quality took a permit and then asked for a second one, from inside the first, to
     * size its sound - and `kotlinx`'s `Semaphore` is not re-entrant. With as many such qualities
     * as there are permits, every permit is held by a coroutine waiting for one that nobody can
     * release, `awaitAll` never returns, and the host's sheet spins for ever on a coroutine that
     * is never going to finish. A video above 360p keeps its sound apart, so this is the ordinary
     * shape of a YouTube answer rather than an odd one.
     *
     * The timeout is what makes the deadlock a failed test rather than a hung suite.
     */
    @Test(timeout = 30_000)
    fun `a video whose sound is a second file is sized, however few permits there are`() = runBlocking {
        val described = describer(sizeProbesAtOnce = 2).describe(withSound(4), postUrl = null, keepFoundUrl = false)

        assertEquals(4, described.qualities.size)
        // The picture and its sound, both counted.
        assertEquals(List(4) { 2L * size }, described.qualities.map { it.sizeBytes })
    }

    /** The same, with one permit, which is the smallest a low-end device would use. */
    @Test(timeout = 30_000)
    fun `one permit is enough to size a video and its sound`() = runBlocking {
        val described = describer(sizeProbesAtOnce = 1).describe(withSound(2), postUrl = null, keepFoundUrl = false)

        assertEquals(List(2) { 2L * size }, described.qualities.map { it.sizeBytes })
    }
}
