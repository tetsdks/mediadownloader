package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.core.Constants.Scratch
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * A video whose sound is a second file, across more than one run of the same download. Real time
 * and real threads: a pause lands between two network reads, which a test scheduler cannot place.
 */
class SoundTrackResumeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val videoUrl = "https://cdn.test/video.mp4"
    private val audioUrl = "https://cdn.test/audio.m4a"

    /** Both over 2 x MIN_PART_BYTES, so each is fetched in parallel parts - 2 and 3 of them. */
    private val videoBytes = sampleBytes(5 * 1024 * 1024)
    private val audioBytes = sampleBytes(6 * 1024 * 1024)

    /** The audio's first part, which partRanges cuts at exactly MIN_PART_BYTES for 6 MB. */
    private val firstSoundPart = 2 * 1024 * 1024L
    private val firstSoundRange = "bytes=0-${firstSoundPart - 1}"

    private object PassThroughRemuxer : StreamRemuxer {
        override fun remux(video: File, audio: File?, output: File) {
            output.parentFile?.mkdirs()
            output.writeBytes(video.readBytes() + (audio?.readBytes() ?: ByteArray(0)))
        }
    }

    private fun task() = DownloadTask(
        url = videoUrl,
        audioUrl = audioUrl,
        headers = emptyMap(),
        isStream = false,
        singleConnection = false,
        workDir = File(temp.root, "work").apply { mkdirs() },
        output = File(temp.root, "out/.7.download"),
    )

    /**
     * The sound's first part, wherever in the work dir it is being built: the part that sits beside
     * a record of the sound's own length, and only once it is whole.
     */
    private fun soundPart(task: DownloadTask): File? = task.workDir.walkTopDown()
        .filter { it.name == Scratch.PARTS_LENGTH_NAME && it.readText() == audioBytes.size.toString() }
        .mapNotNull { cutFor -> File(cutFor.parentFile, "${Scratch.PART_PREFIX}0").takeIf { it.length() == firstSoundPart } }
        .firstOrNull()

    private fun engine(client: HttpClient) = DownloadEngine(
        direct = DirectFileDownloader(HttpFileFetcher(client)),
        hls = HlsStreamDownloader(HttpFileFetcher(client), HttpFetcher(client, HttpClientFactory.json), PassThroughRemuxer),
        remuxer = PassThroughRemuxer,
        ioDispatcher = Dispatchers.IO,
    )

    /**
     * Serves both files, honouring ranges. [soundRanges] decides what happens to a ranged request
     * for the sound: anything it does not name is answered.
     */
    private fun server(
        soundRanges: Map<String, suspend () -> Nothing> = emptyMap(),
    ): Pair<HttpClient, MutableList<String>> {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val files = mapOf(videoUrl to videoBytes, audioUrl to audioBytes)
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            val range = request.headers[HttpHeaders.Range]
            requests += "$url ${range ?: "-"}"
            if (url == audioUrl && range != null) soundRanges[range]?.invoke()
            val body = files[url] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
            if (range == null) {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
            } else {
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
            }
        })
        return client to requests
    }

    /**
     * A pause between the picture finishing and the sound doing, then resume - which is what a
     * reader pausing a YouTube download looks like, since anything over 360p keeps its sound apart.
     *
     * The two tracks are fetched into one work dir under one set of part names, so the sound's
     * finished parts and the length they were cut for are deleted by the picture's own "already
     * whole" test on the very next run, and the sound starts again from nothing. On a connection
     * that cannot hold for a whole track, the sound could then never finish inside the attempts
     * the download is given.
     */
    @Test(timeout = 180_000)
    fun `the sound keeps the parts it already has when the picture is already whole`() = runBlocking {
        val task = task()
        // Run one: the picture arrives, the sound's first part arrives, the rest never answers -
        // and the reader pauses, which cancels the job where it stands.
        val (slow, _) = server(
            soundRanges = mapOf(
                "bytes=$firstSoundPart-${2 * firstSoundPart - 1}" to { awaitCancellation() },
                "bytes=${2 * firstSoundPart}-${audioBytes.size - 1}" to { awaitCancellation() },
            ),
        )
        val paused = launch { engine(slow).download(task, ProgressMeter()) }
        // Found rather than named, so this reads the same whether the two tracks share a dir or
        // have one each: "part-0" alone says nothing about which track it belongs to - the
        // picture's passes through 2 MB on its way to 2.5 MB. The length the parts were cut for is
        // what tells them apart, and the picture deletes its own as it joins.
        withTimeout(120_000) {
            while (soundPart(task) == null) delay(20)
        }
        paused.cancel()
        paused.join()

        // What the pause left behind: the picture whole, and the sound's first part with it.
        assertEquals(videoBytes.size.toLong(), File(task.workDir, Scratch.VIDEO_TRACK).length())
        assertEquals(firstSoundPart, soundPart(task)?.length())

        // Run two: everything answers.
        val (whole, requests) = server()
        engine(whole).download(task, ProgressMeter())

        assertEquals(videoBytes.size + audioBytes.size, task.output.readBytes().size)
        assertTrue(
            "the sound's first part was fetched again: $requests",
            requests.none { it == "$audioUrl $firstSoundRange" },
        )
    }

    /**
     * A sound lost to a dropped connection is not a sound that is gone. The stream downloader
     * already tells them apart - its unlessGone passes anything but a 4xx on, so the run fails and
     * is tried again - while a direct download wrapped the whole fetch in runCatching and published
     * a silent video, called it complete, and never asked for the sound again.
     */
    @Test(timeout = 180_000)
    fun `a sound lost to a dropped connection fails the run instead of finishing silent`() = runBlocking {
        val dropped: suspend () -> Nothing = { throw IOException("connection reset by peer") }
        val (client, _) = server(
            soundRanges = mapOf(
                firstSoundRange to dropped,
                "bytes=$firstSoundPart-${2 * firstSoundPart - 1}" to dropped,
                "bytes=${2 * firstSoundPart}-${audioBytes.size - 1}" to dropped,
            ),
        )
        val task = task()

        val thrown = runCatching { engine(client).download(task, ProgressMeter()) }.exceptionOrNull()

        assertTrue(
            "a dropped sound connection was called complete, with ${task.output.length()} bytes published",
            thrown is IOException,
        )
    }
}
