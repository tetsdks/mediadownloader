package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.data.network.HttpStatusException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DirectFileDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val url = "https://cdn.test/video.mp4"

    private fun task(singleConnection: Boolean = false) = DownloadTask(
        url = url,
        audioUrl = null,
        headers = mapOf("Referer" to "https://site.test/"),
        isStream = false,
        singleConnection = singleConnection,
        workDir = File(temp.root, "work").apply { mkdirs() },
        output = File(temp.root, "out/.1.download"),
    )

    @Test
    fun `a large file that serves ranges comes down in parts, joined in order`() = runTest {
        val bytes = sampleBytes(9 * 1024 * 1024 + 123)
        val server = FakeMediaServer(mapOf(url to bytes))
        val meter = ProgressMeter()

        DirectFileDownloader(HttpFileFetcher(server.client)).download(task(), meter)

        assertArrayEquals(bytes, task().output.readBytes())
        assertEquals(bytes.size.toLong(), meter.downloadedBytes)
        assertEquals(bytes.size.toLong(), meter.totalBytes)
        // One probe and one request per part, every one of them ranged.
        assertEquals(1 + 4, server.requests.size)
        assertTrue(server.requests.all { it.contains("bytes=") })
    }

    @Test
    fun `a part answered with the whole file falls back to one piece, never nine copies`() = runTest {
        val bytes = sampleBytes(5 * 1024 * 1024)
        // The probe's range is honoured, every part's is not.
        val server = FakeMediaServer(mapOf(url to bytes), ignoresRangesAfter = 1)
        val meter = ProgressMeter()

        DirectFileDownloader(HttpFileFetcher(server.client)).download(task(), meter)

        assertArrayEquals(bytes, task().output.readBytes())
        assertEquals(bytes.size.toLong(), meter.downloadedBytes)
        assertTrue(File(temp.root, "work").listFiles().orEmpty().none { it.name.startsWith("part-") })
    }

    @Test
    fun `a site that breaks on parallel parts is fetched in one piece`() = runTest {
        val bytes = sampleBytes(5 * 1024 * 1024)
        val server = FakeMediaServer(mapOf(url to bytes))

        DirectFileDownloader(HttpFileFetcher(server.client)).download(task(singleConnection = true), ProgressMeter())

        assertArrayEquals(bytes, task().output.readBytes())
        assertEquals(listOf("$url bytes=0-0", "$url -"), server.requests)
    }

    @Test
    fun `an interrupted download resumes from the bytes on disk`() = runTest {
        val bytes = sampleBytes(300_000)
        task().output.apply { parentFile!!.mkdirs() }.writeBytes(bytes.copyOf(120_000))
        val server = FakeMediaServer(mapOf(url to bytes))
        val meter = ProgressMeter()

        DirectFileDownloader(HttpFileFetcher(server.client)).download(task(), meter)

        assertArrayEquals(bytes, task().output.readBytes())
        assertEquals("$url bytes=120000-", server.requests.last())
        assertEquals(bytes.size.toLong(), meter.downloadedBytes)
    }

    @Test
    fun `a resume the server ignores starts over instead of doubling the start`() = runTest {
        val bytes = sampleBytes(300_000)
        task().output.apply { parentFile!!.mkdirs() }.writeBytes(bytes.copyOf(120_000))
        val server = FakeMediaServer(mapOf(url to bytes), ignoresRangesAfter = 1)

        DirectFileDownloader(HttpFileFetcher(server.client)).download(task(), ProgressMeter())

        assertArrayEquals(bytes, task().output.readBytes())
    }

    @Test
    fun `the page's headers go with every request`() = runTest {
        val seen = mutableListOf<String?>()
        val bytes = sampleBytes(1000)
        val client = HttpClient(MockEngine { request ->
            seen += request.headers["Referer"]
            respond(bytes)
        })

        DirectFileDownloader(HttpFileFetcher(client)).download(task(), ProgressMeter())

        assertTrue(seen.isNotEmpty() && seen.all { it == "https://site.test/" })
    }

    @Test(expected = HttpStatusException::class)
    fun `a missing file fails`() = runTest {
        val server = FakeMediaServer(emptyMap())
        DirectFileDownloader(HttpFileFetcher(server.client)).download(task(), ProgressMeter())
    }

    @Test
    fun `a part longer than its range is fetched again, not failed forever`() = runTest {
        val bytes = sampleBytes(9 * 1024 * 1024 + 123)
        File(task().workDir, "parts.length").writeText(bytes.size.toString())
        File(task().workDir, "part-0").writeBytes(sampleBytes(3 * 1024 * 1024))
        val server = FakeMediaServer(mapOf(url to bytes))

        DirectFileDownloader(HttpFileFetcher(server.client)).download(task(), ProgressMeter())

        assertArrayEquals(bytes, task().output.readBytes())
    }

    @Test
    fun `parts cut for a file of another length are dropped`() = runTest {
        val bytes = sampleBytes(9 * 1024 * 1024 + 123)
        File(task().workDir, "parts.length").writeText("12345")
        File(task().workDir, "part-1").writeBytes(ByteArray(1_000) { 7 })
        val server = FakeMediaServer(mapOf(url to bytes))

        DirectFileDownloader(HttpFileFetcher(server.client)).download(task(), ProgressMeter())

        assertArrayEquals(bytes, task().output.readBytes())
    }

    @Test
    fun `a server sending past the range asked for writes no more than the range`() = runTest {
        val bytes = sampleBytes(9 * 1024 * 1024 + 123)
        // Answers every range with 206 from the right start, but runs on to the end of the file.
        val client = HttpClient(MockEngine { request ->
            val start = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            val slice = bytes.copyOfRange(start, bytes.size)
            respond(
                slice,
                HttpStatusCode.PartialContent,
                headersOf(
                    HttpHeaders.ContentRange to listOf("bytes $start-${bytes.size - 1}/${bytes.size}"),
                    HttpHeaders.ContentLength to listOf(slice.size.toString()),
                ),
            )
        })

        DirectFileDownloader(HttpFileFetcher(client)).download(task(), ProgressMeter())

        assertArrayEquals(bytes, task().output.readBytes())
    }
}
