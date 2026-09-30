package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A download whose sound is a second file. Sites that offer more than their lowest resolution tend
 * to keep the picture and the sound apart, and taking only the first url saves a video nobody can
 * hear.
 */
class DownloadEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val videoUrl = "https://cdn.test/video.mp4"
    private val audioUrl = "https://cdn.test/audio.m4a"
    private val videoBytes = ByteArray(40_000) { (it % 251).toByte() }
    private val audioBytes = ByteArray(9_000) { (it % 97).toByte() }

    private class RecordingRemuxer(private val fails: Boolean = false) : StreamRemuxer {
        var video: ByteArray? = null
        var audio: ByteArray? = null

        override fun remux(video: File, audio: File?, output: File) {
            this.video = video.readBytes()
            this.audio = audio?.readBytes()
            if (fails) error("this container cannot take that track")
            output.parentFile?.mkdirs()
            output.writeBytes(this.video!! + (this.audio ?: ByteArray(0)))
        }
    }

    private fun task(sound: String?) = DownloadTask(
        url = videoUrl,
        audioUrl = sound,
        headers = emptyMap(),
        isStream = false,
        singleConnection = false,
        workDir = File(temp.root, "work").apply { mkdirs() },
        output = File(temp.root, "out/.7.download"),
    )

    private fun engine(server: FakeMediaServer, remuxer: StreamRemuxer) = DownloadEngine(
        direct = DirectFileDownloader(HttpFileFetcher(server.client)),
        hls = HlsStreamDownloader(
            HttpFileFetcher(server.client),
            HttpFetcher(server.client, HttpClientFactory.json),
            remuxer,
        ),
        remuxer = remuxer,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `a file whose sound is a second file is fetched twice and joined`() = runTest {
        val server = FakeMediaServer(mapOf(videoUrl to videoBytes, audioUrl to audioBytes))
        val remuxer = RecordingRemuxer()

        engine(server, remuxer).download(task(audioUrl), ProgressMeter())

        assertArrayEquals(videoBytes, remuxer.video)
        assertArrayEquals(audioBytes, remuxer.audio)
        assertArrayEquals(videoBytes + audioBytes, task(audioUrl).output.readBytes())
    }

    @Test
    fun `both tracks count towards what has been downloaded`() = runTest {
        val server = FakeMediaServer(mapOf(videoUrl to videoBytes, audioUrl to audioBytes))
        val meter = ProgressMeter()

        engine(server, RecordingRemuxer()).download(task(audioUrl), meter)

        assertEquals((videoBytes.size + audioBytes.size).toLong(), meter.downloadedBytes)
    }

    @Test
    fun `without a sound url nothing is joined`() = runTest {
        val server = FakeMediaServer(mapOf(videoUrl to videoBytes))
        val remuxer = RecordingRemuxer()

        engine(server, remuxer).download(task(null), ProgressMeter())

        assertNull("the remuxer should not have been asked", remuxer.video)
        assertArrayEquals(videoBytes, task(null).output.readBytes())
    }

    @Test
    fun `a sound that cannot be had leaves the picture, not nothing`() = runTest {
        // The audio url 404s: a silent video is worth more than no video at all.
        val server = FakeMediaServer(mapOf(videoUrl to videoBytes))

        engine(server, RecordingRemuxer()).download(task(audioUrl), ProgressMeter())

        assertArrayEquals(videoBytes, task(audioUrl).output.readBytes())
    }

    @Test
    fun `a join that fails leaves the picture, not nothing`() = runTest {
        // Both tracks arrived, but the container refused one of them - the case that made a
        // YouTube download come out as a silent file when the sound was Opus.
        val server = FakeMediaServer(mapOf(videoUrl to videoBytes, audioUrl to audioBytes))
        val remuxer = RecordingRemuxer(fails = true)

        engine(server, remuxer).download(task(audioUrl), ProgressMeter())

        assertArrayEquals(audioBytes, remuxer.audio)
        assertArrayEquals(videoBytes, task(audioUrl).output.readBytes())
    }

    @Test
    fun `the scratch tracks are not left behind`() = runTest {
        val server = FakeMediaServer(mapOf(videoUrl to videoBytes, audioUrl to audioBytes))
        val downloadTask = task(audioUrl)

        engine(server, RecordingRemuxer()).download(downloadTask, ProgressMeter())

        val leftovers = downloadTask.workDir.listFiles().orEmpty().map { it.name }
        assertTrue("left behind: $leftovers", leftovers.none { it.endsWith(".track") })
    }
}
