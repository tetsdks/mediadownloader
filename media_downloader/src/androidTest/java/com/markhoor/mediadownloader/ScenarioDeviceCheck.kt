package com.markhoor.mediadownloader

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.markhoor.mediadownloader.domain.models.DownloadException
import com.markhoor.mediadownloader.domain.models.DownloadModel
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.domain.models.MediaCollectionItem
import com.markhoor.mediadownloader.domain.models.MediaCollectionModel
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What `DownloadRepositoryImpl` does, against the real scheduler, database and files: a download
 * queued with no connection, delete, a collection treated as one unit, the notification, and a
 * link that cannot be fetched at all.
 *
 * These are here rather than in `src/test` because the thing worth proving is the implementation,
 * and the unit tests reach it only through `FakeDownloadRepository`, which records the call and
 * answers success - it is green whether or not the real one works. Every defect found in this
 * class so far (a delete that took the row but left the file and the notification, a
 * `resumeCollection` that started the first row and left the rest `Queued`) was invisible to those
 * tests and would have been caught here.
 *
 * Needs a connection. **Toggles airplane mode**, so run it over USB, not over wifi adb.
 */
@RunWith(AndroidJUnit4::class)
class ScenarioDeviceCheck {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        MediaDownloader.initialize(context)
    }

    /** With no connection the row waits as [DownloadState.Queued], and finishes once there is one. */
    @Test
    fun offlineQueuesAndTheConnectionComingBackFinishesIt() = runBlocking {
        val media = MediaDownloader.parse(SMALL_LINK).getOrThrow()
        val quality = smallest(media)

        airplane(on = true)
        try {
            val id = MediaDownloader.download(media, quality).getOrThrow()
            withTimeout(60_000) {
                MediaDownloader.observeDownload(id).filterNotNull().first { it.state == DownloadState.Queued }
            }
            delay(8_000)
            val waiting = current(id)
            report("offline: $id is ${waiting.state} at ${waiting.downloadedBytes} bytes")
            assertEquals(DownloadState.Queued, waiting.state)
            assertEquals(0L, waiting.downloadedBytes)

            airplane(on = false)
            val done = awaitEnd(id)
            report("after the connection returned: ${done.state} ${done.downloadedBytes}/${done.totalBytes}")
            assertEquals(done.errorMessage, DownloadState.Completed, done.state)
            assertTrue(File(done.filePath).isFile)
        } finally {
            airplane(on = false)
        }
    }

    /** Delete takes the row and, when asked, the file with it. */
    @Test
    fun deleteTakesTheRowAndTheFile() = runBlocking {
        val media = MediaDownloader.parse(SMALL_LINK).getOrThrow()
        val id = MediaDownloader.download(media, smallest(media)).getOrThrow()
        val done = awaitEnd(id)
        assertEquals(done.errorMessage, DownloadState.Completed, done.state)
        val file = File(done.filePath)
        assertTrue("no file to delete at ${file.path}", file.isFile)

        assertTrue(MediaDownloader.delete(id, deleteFile = true).isSuccess)
        withTimeout(30_000) { MediaDownloader.observeDownload(id).first { it == null } }
        report("delete: row gone, file exists=${file.exists()} (${file.path})")
        assertFalse("the file outlived its row", file.exists())
        assertTrue(MediaDownloader.delete(id).exceptionOrNull() is DownloadException.NotFound)
    }

    /** Delete while it is still running: no job is left behind to write to a row that is gone. */
    @Test
    fun deleteWhileRunningLeavesNothingBehind() = runBlocking {
        val media = MediaDownloader.parse(BIG_LINK).getOrThrow()
        val quality = media.qualities.filter { it.type == MediaType.Video }.maxBy { it.sizeBytes ?: 0 }
        val id = MediaDownloader.download(media, quality).getOrThrow()
        withTimeout(TIMEOUT_MS) {
            MediaDownloader.observeDownload(id).filterNotNull().first { it.downloadedBytes > 500_000 }
        }
        val running = current(id)
        report("mid-flight delete: $id was ${running.state} at ${running.downloadedBytes} bytes")
        assertTrue(MediaDownloader.delete(id, deleteFile = true).isSuccess)
        withTimeout(30_000) { MediaDownloader.observeDownload(id).first { it == null } }
        delay(10_000)
        assertEquals(null, MediaDownloader.observeDownload(id).first())
        report("mid-flight delete: still gone 10s later, file=${File(running.filePath).exists()}")
    }

    /** A collection is queued whole, pauses as one and resumes as one. */
    @Test
    fun aCollectionIsQueuedPausedAndResumedAsOneUnit() = runBlocking {
        val collection = MediaCollectionModel(
            title = COLLECTION_TITLE,
            sourceUrl = "https://example.com/playlist",
            items = COLLECTION_LINKS.mapIndexed { i, url -> MediaCollectionItem(url = url, title = "entry ${i + 1}") },
        )
        // The big one first, so there is a row still fetching bytes when the pause lands.
        MediaDownloader.downloadCollection(collection, preferredQuality = "720p")

        withTimeout(TIMEOUT_MS) {
            MediaDownloader.collectionProgress().first {
                it.title == COLLECTION_TITLE && it.added + it.failed >= collection.items.size
            }
        }
        val progress = MediaDownloader.collectionProgress().first()
        report("collection: added=${progress.added} failed=${progress.failed} total=${progress.total} adding=${progress.isAdding}")
        assertEquals(collection.items.size, progress.total)
        assertTrue("nothing was queued", progress.added > 0)

        val rows = MediaDownloader.observeDownloads().first().filter { it.collectionTitle == COLLECTION_TITLE }
        report("collection rows: " + rows.joinToString { "${it.id}:${it.state}" })
        assertEquals(progress.added, rows.size)

        // Pause only says something once a row is actually fetching bytes: pausing a list of
        // Queued rows proves nothing, because none of them was running to begin with.
        val running = withTimeout(TIMEOUT_MS) {
            MediaDownloader.observeDownloads().first { all ->
                all.any { it.collectionTitle == COLLECTION_TITLE && it.state == DownloadState.Downloading && it.downloadedBytes > 200_000 }
            }
        }.first { it.collectionTitle == COLLECTION_TITLE && it.state == DownloadState.Downloading }
        report("collection: ${running.id} is Downloading at ${running.downloadedBytes} bytes, pausing the collection now")

        val paused = MediaDownloader.pauseCollection(COLLECTION_TITLE).getOrThrow()
        report("pauseCollection held $paused row(s)")
        delay(5_000)
        val afterPause = MediaDownloader.observeDownloads().first().filter { it.collectionTitle == COLLECTION_TITLE }
        val held = afterPause.first { it.id == running.id }
        delay(4_000)
        val stillHeld = MediaDownloader.observeDownloads().first().first { it.id == running.id }
        report("collection pause: ${held.id} ${held.state} at ${held.downloadedBytes} then ${stillHeld.downloadedBytes}")
        assertTrue(
            "a paused collection still has a running row: " + afterPause.joinToString { "${it.id}:${it.state}" },
            afterPause.none { it.state == DownloadState.Downloading },
        )
        assertEquals("the paused row kept moving", held.downloadedBytes, stillHeld.downloadedBytes)
        assertEquals(DownloadState.Paused, stillHeld.state)

        val resumed = MediaDownloader.resumeCollection(COLLECTION_TITLE).getOrThrow()
        report("resumeCollection started $resumed row(s)")

        val ids = rows.map { it.id }
        val ended = withTimeout(TIMEOUT_MS) {
            MediaDownloader.observeDownloads().first { all ->
                val mine = all.filter { it.id in ids }
                mine.size == ids.size && mine.all { it.state == DownloadState.Completed || it.state == DownloadState.Failed }
            }
        }.filter { it.id in ids }
        ended.forEach { report("collection end: ${it.id} ${it.state} ${it.downloadedBytes}/${it.totalBytes} ${it.errorMessage ?: ""} ${it.filePath}") }
        assertTrue(
            "no entry of the collection completed",
            ended.any { it.state == DownloadState.Completed },
        )
        ended.filter { it.state == DownloadState.Completed }.forEach {
            assertTrue("completed row has no file: ${it.filePath}", File(it.filePath).isFile)
        }
    }

    /** A link that answers 404 fails with a reason on the row, and retry runs it again. */
    @Test
    fun whatCannotBeFetchedFailsWithAReasonAndRetryRunsAgain() = runBlocking {
        val media = MediaModel(
            title = "no such file",
            thumbnailUrl = null,
            sourceUrl = "https://www.w3schools.com/html/",
            qualities = listOf(
                MediaQualityModel(
                    url = "https://www.w3schools.com/html/no_such_file_xyz.mp4",
                    label = "SD",
                    type = MediaType.Video,
                    sizeBytes = 123_456,
                ),
            ),
        )
        val id = MediaDownloader.download(media, media.qualities.first()).getOrThrow()
        val failed = awaitEnd(id)
        report("unfetchable: ${failed.state} reason=${failed.errorMessage}")
        assertEquals(DownloadState.Failed, failed.state)
        assertNotNull("a failed row with no reason on it", failed.errorMessage)

        assertTrue(MediaDownloader.resume(id).isSuccess)
        val again = awaitEnd(id)
        report("after retry: ${again.state} reason=${again.errorMessage}")
        assertEquals(DownloadState.Failed, again.state)
        assertNotNull(again.errorMessage)
        assertTrue(MediaDownloader.delete(id, deleteFile = true).isSuccess)
    }

    /** A running download is visible in the shade, and deleting it takes the notification too. */
    @Test
    fun aRunningDownloadIsInTheShadeAndDeletingItTakesTheNotification() = runBlocking {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val media = MediaDownloader.parse(BIG_LINK).getOrThrow()
        val quality = media.qualities.filter { it.type == MediaType.Video }.maxBy { it.sizeBytes ?: 0 }
        val id = MediaDownloader.download(media, quality).getOrThrow()
        withTimeout(TIMEOUT_MS) {
            MediaDownloader.observeDownload(id).filterNotNull().first { it.downloadedBytes > 300_000 }
        }
        // DownloadNotifier.progressId/resultId: a row owns the pair id * 2 and id * 2 + 1, so that
        // its progress and its outcome can be cancelled separately. Kept in step by hand - if that
        // changes there, this says the notification never appeared rather than anything true.
        val progressId = (id * 2).toInt()
        val resultId = (id * 2 + 1).toInt()
        val shown = withTimeout(30_000) {
            var found = notifications.activeNotifications.orEmpty()
            while (found.none { it.id == progressId }) {
                delay(500)
                found = notifications.activeNotifications.orEmpty()
            }
            found.first { it.id == progressId }
        }
        report("notification: id=${shown.id} ongoing=${shown.isOngoing} channel=${shown.notification.channelId}")
        assertTrue("the download notification is not ongoing", shown.isOngoing)

        assertTrue(MediaDownloader.delete(id, deleteFile = true).isSuccess)
        withTimeout(30_000) {
            while (notifications.activeNotifications.orEmpty().any { it.id == progressId || it.id == resultId }) delay(500)
        }
        report("notification: gone after delete")
    }

    private fun airplane(on: Boolean) {
        shell("cmd connectivity airplane-mode " + if (on) "enable" else "disable")
        report("airplane-mode " + if (on) "enable" else "disable")
        Thread.sleep(6_000)
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    private fun smallest(media: MediaModel): MediaQualityModel =
        media.qualities.filter { it.type == MediaType.Video }.minByOrNull { it.sizeBytes ?: Long.MAX_VALUE }
            ?: media.qualities.first()

    private suspend fun awaitEnd(id: Long): DownloadModel = withTimeout(TIMEOUT_MS) {
        MediaDownloader.observeDownload(id).filterNotNull()
            .first { it.state == DownloadState.Completed || it.state == DownloadState.Failed }
    }

    private suspend fun current(id: Long): DownloadModel = MediaDownloader.observeDownload(id).filterNotNull().first()

    private fun report(line: String) {
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "MDScenario"
        const val TIMEOUT_MS = 8 * 60_000L
        const val SMALL_LINK = "https://www.pinterest.com/pin/211174979574445/"
        const val BIG_LINK = "https://www.dailymotion.com/video/x8ixblz"
        const val COLLECTION_TITLE = "Scenario Playlist"
        val COLLECTION_LINKS = listOf(
            "https://www.dailymotion.com/video/x8ixblz",
            "https://www.pinterest.com/pin/62628251062820323/",
            "https://www.pinterest.com/pin/211174979574445/",
        )
    }
}
