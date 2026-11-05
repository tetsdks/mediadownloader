package com.markhoor.mediadownloader

import android.app.Notification
import android.text.format.Formatter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.markhoor.mediadownloader.data.local.DownloadEntity
import com.markhoor.mediadownloader.data.work.DownloadNotifier
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.domain.models.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a download's notification actually says, built on a device.
 *
 * Here rather than in `src/test` because the words are Android's: `Formatter` writes a size the
 * way this device and locale write one, and the rest comes out of resources. Under JVM tests both
 * answer a default, so these lines would be checked against nothing - the shape would pass while
 * the words were empty.
 *
 * Sizes are asked of `Formatter` here too rather than written out, so the test says "the size,
 * then the word of, then the size" and stays true on a device that writes `48 MB` as something
 * else. The times are the module's own strings and are written out.
 *
 * Needs no connection: nothing is downloaded, the notifications are only built.
 */
@RunWith(AndroidJUnit4::class)
class DownloadNotificationCheck {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val notifier = DownloadNotifier(
        context = context,
        smallIcon = { android.R.drawable.stat_sys_download },
        openActivity = { null },
    )

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    private fun row(state: DownloadState = DownloadState.Downloading) = DownloadEntity(
        id = 1,
        title = "A clip worth keeping",
        fileName = "a-clip.mp4",
        directoryPath = context.cacheDir.path,
        sourceUrl = "https://example.com/p/1",
        mediaUrl = "https://cdn.example.com/720.mp4",
        audioUrl = null,
        headers = emptyMap(),
        thumbnailUrl = null,
        type = MediaType.Video,
        qualityLabel = "720p",
        isStream = false,
        singleConnection = false,
        state = state,
        createdAtMillis = 0,
    )

    private val Notification.text: String? get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private val Notification.header: String? get() = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
    private val Notification.percent: Int get() = extras.getInt(Notification.EXTRA_PROGRESS)
    private val Notification.isIndeterminate: Boolean
        get() = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)

    @Test
    fun theLineGrowsIntoItsShapeRatherThanShowingZeroes() {
        val starting = notifier.progress(row(), downloadedBytes = 0, totalBytes = null)
        assertEquals("Preparing download…", starting.text)
        assertNull("no time to speak of yet", starting.header)
        assertTrue(starting.isIndeterminate)

        // Bytes arriving and the size known, but only one reading so far: no speed, so no time.
        val sized = notifier.progress(row(), downloadedBytes = 12_000_000, totalBytes = 48_000_000)
        assertEquals("${size(12_000_000)} of ${size(48_000_000)}", sized.text)
        assertNull(sized.header)
        assertEquals(25, sized.percent)
        assertFalse(sized.isIndeterminate)
    }

    @Test
    fun aRunningDownloadSaysSizeSpeedAndTimeLeft() {
        val running = notifier.progress(
            row(),
            downloadedBytes = 12_000_000,
            totalBytes = 48_000_000,
            bytesPerSecond = 2_000_000,
        )

        assertEquals("${size(12_000_000)} of ${size(48_000_000)} · ${size(2_000_000)}/s", running.text)
        // 36 MB still to come at 2 MB a second.
        assertEquals("18 sec left", running.header)
        assertEquals(25, running.percent)
    }

    /** A stream has no size until its last segment, so it says what has arrived and how fast. */
    @Test
    fun aStreamWithNoSizeYetSaysWhatHasArrivedAndHowFast() {
        val stream = notifier.progress(
            row(),
            downloadedBytes = 3_500_000,
            totalBytes = null,
            bytesPerSecond = 900_000,
        )

        assertEquals("${size(3_500_000)} · ${size(900_000)}/s", stream.text)
        assertNull("no size means no time left", stream.header)
        assertTrue(stream.isIndeterminate)
    }

    @Test
    fun aLongWaitIsSaidInMinutesAndThenHours() {
        val minutes = notifier.progress(row(), 0, totalBytes = 600_000_000, bytesPerSecond = 1_000_000)
        assertEquals("10 min left", minutes.header)

        val hours = notifier.progress(row(), 0, totalBytes = 9_000_000_000L, bytesPerSecond = 1_000_000)
        assertEquals("2 hr 30 min left", hours.header)
    }

    /** Rounded up, so the last minute reads as one rather than as none. */
    @Test
    fun theLastMinuteIsNotRoundedAwayToZero() {
        val nearlyAMinute = notifier.progress(row(), 0, totalBytes = 61_000_000, bytesPerSecond = 1_000_000)
        assertEquals("2 min left", nearlyAMinute.header)
    }

    /**
     * The join, the stitch and the copy into the public folder are not counted in bytes. A full
     * bar and "0 sec left" is how a download still working looks exactly like one that has hung,
     * so it says what it is doing and the bar goes back to moving on its own.
     */
    @Test
    fun finishingSaysSoInsteadOfStandingAtAFullBar() {
        val finishing = notifier.finishing(row(state = DownloadState.Finishing))

        assertEquals("Finishing…", finishing.text)
        assertTrue(finishing.isIndeterminate)
        assertNull(finishing.header)
    }

    @Test
    fun theLastSecondsAreNotCountedDown() {
        // One second's worth left: said as nothing, because finishing follows immediately.
        val nearlyDone = notifier.progress(row(), 47_000_000, totalBytes = 48_000_000, bytesPerSecond = 2_000_000)

        assertEquals(97, nearlyDone.percent)
        assertNull(nearlyDone.header)
    }
}
