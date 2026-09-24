package com.markhoor.mediadownloader

import android.Manifest
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.markhoor.mediadownloader.domain.models.DownloadException
import com.markhoor.mediadownloader.domain.models.DownloadModel
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Downloads real links on a device, through the real worker, database and notification, and
 * checks the files that come out: the bytes are what they claim, a video has a running time, and
 * a stream that kept its sound apart has sound again. Needs a connection.
 *
 * Links can be replaced with `-Pandroid.testInstrumentationRunnerArguments.links="https://... https://..."`.
 * Files are left in Download/ so they can be played; the output lists where.
 */
@RunWith(AndroidJUnit4::class)
class DownloadDeviceCheck {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        MediaDownloader.initialize(context)
    }

    @Test
    fun realLinksDownloadIntoPlayableFiles() = runBlocking {
        val links = InstrumentationRegistry.getArguments().getString("links")?.split(' ')?.filter { it.isNotBlank() }
            ?: DEFAULT_LINKS
        val problems = links.mapNotNull { link ->
            runCatching { downloadAndInspect(link) }.exceptionOrNull()?.let { "$link: ${it.message}" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun pauseHoldsAndResumeFinishes() = runBlocking {
        val media = MediaDownloader.parse(PAUSE_LINK).getOrThrow()
        val quality = media.qualities.filter { it.type == MediaType.Video }.maxBy { it.sizeBytes ?: 0 }
        val id = MediaDownloader.download(media, quality).getOrThrow()

        withTimeout(TIMEOUT_MS) { MediaDownloader.observeDownload(id).filterNotNull().first { it.downloadedBytes > 2_000_000 } }
        assertTrue(MediaDownloader.pause(id).isSuccess)
        delay(3_000)
        val held = current(id)
        delay(3_000)
        val stillHeld = current(id)
        report("paused at ${held.downloadedBytes} then ${stillHeld.downloadedBytes}, ${stillHeld.state}")
        assertEquals(DownloadState.Paused, stillHeld.state)
        assertEquals(held.downloadedBytes, stillHeld.downloadedBytes)
        assertTrue(MediaDownloader.pause(id).exceptionOrNull() is DownloadException.InvalidState)

        assertTrue(MediaDownloader.resume(id).isSuccess)
        val done = awaitEnd(id)
        assertEquals(done.errorMessage, DownloadState.Completed, done.state)
        inspect(done, expectSound = true)
        assertTrue(MediaDownloader.resume(id).exceptionOrNull() is DownloadException.InvalidState)
        assertTrue(MediaDownloader.pause(Long.MAX_VALUE).exceptionOrNull() is DownloadException.NotFound)
    }

    /**
     * Two halves of a process-death check, run by hand (see the guide): `start` begins a long
     * download and returns, so the process dies with it running; `verify` finds it finished.
     */
    @Test
    fun processDeath() = runBlocking {
        when (InstrumentationRegistry.getArguments().getString("processDeath")) {
            "start" -> {
                val media = MediaDownloader.parse(PAUSE_LINK).getOrThrow()
                val quality = media.qualities.filter { it.type == MediaType.Video }.maxBy { it.sizeBytes ?: 0 }
                val id = MediaDownloader.download(media, quality).getOrThrow()
                withTimeout(TIMEOUT_MS) { MediaDownloader.observeDownload(id).filterNotNull().first { it.downloadedBytes > 1_000_000 } }
                report("process death: started $id at ${current(id).downloadedBytes} bytes, leaving it running")
            }
            "verify" -> {
                val latest = MediaDownloader.observeDownloads().first().first()
                report("process death: ${latest.id} is ${latest.state} at ${latest.downloadedBytes} bytes")
                val done = awaitEnd(latest.id)
                assertEquals(done.errorMessage, DownloadState.Completed, done.state)
                inspect(done, expectSound = true)
            }
            else -> Unit
        }
    }

    private suspend fun downloadAndInspect(link: String) {
        val media = MediaDownloader.parse(link).getOrThrow()
        val quality = pick(media)
        report("$link -> ${quality.label} ${quality.type} size=${quality.sizeBytes} separateSound=${quality.audioUrl != null}")
        val id = MediaDownloader.download(media, quality).getOrThrow()
        val done = awaitEnd(id)
        check(done.state == DownloadState.Completed) { "ended ${done.state}: ${done.errorMessage}" }
        inspect(done, expectSound = quality.audioUrl != null)
    }

    /** A quality with separate sound when there is one - that is the path worth proving - else the smallest. */
    private fun pick(media: MediaModel): MediaQualityModel =
        media.qualities.firstOrNull { it.audioUrl != null }
            ?: media.qualities.filter { it.type == MediaType.Video }.minByOrNull { it.sizeBytes ?: Long.MAX_VALUE }
            ?: media.qualities.first()

    private suspend fun awaitEnd(id: Long): DownloadModel = withTimeout(TIMEOUT_MS) {
        MediaDownloader.observeDownload(id).filterNotNull()
            .first { it.state == DownloadState.Completed || it.state == DownloadState.Failed }
    }

    private suspend fun current(id: Long): DownloadModel = MediaDownloader.observeDownload(id).filterNotNull().first()

    private fun inspect(download: DownloadModel, expectSound: Boolean) {
        val file = File(download.filePath)
        check(file.isFile && file.length() > 0) { "no file at ${file.path}" }
        val head = file.inputStream().use { input -> ByteArray(12).also { input.read(it) } }
        val details = StringBuilder("${file.path} ${file.length()} bytes head=${head.joinToString("") { "%02x".format(it) }}")
        if (download.type == MediaType.Video) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                details.append(" duration=${duration}ms video=$hasVideo audio=$hasAudio ${width}x$height")
                check(duration > 0) { "no running time: $details" }
                check(hasVideo == "yes") { "no picture: $details" }
                if (expectSound) check(hasAudio == "yes") { "sound missing: $details" }
            } finally {
                retriever.release()
            }
        }
        report("OK ${download.state} $details")
    }

    private fun report(line: String) {
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "MDCheck"
        const val TIMEOUT_MS = 8 * 60_000L
        const val PAUSE_LINK = "https://www.dailymotion.com/video/x8ixblz"
        val DEFAULT_LINKS = listOf(
            "https://www.pinterest.com/pin/62628251062820323/",
            "https://www.tiktok.com/@2brother4100/video/7682479112115588360",
            "https://www.dailymotion.com/video/x8ixblz",
            "https://www.pinterest.com/pin/211174979574445/",
        )
    }
}
