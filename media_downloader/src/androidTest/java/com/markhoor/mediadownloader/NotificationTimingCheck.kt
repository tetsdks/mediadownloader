package com.markhoor.mediadownloader

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **When** a download's notification appears, and how long it takes to say something useful.
 *
 * A big file is what shows this: a small one is downloaded before the notification has had a
 * chance to be wrong, so it has to be one that takes long enough for the first seconds to be
 * visible. The timeline is logged under `MediaDownloader`, so a run can be read rather than
 * guessed at.
 *
 * The link is signed and expires. Pass a fresh one with
 * `-Pandroid.testInstrumentationRunnerArguments.bigFile="https://..."`; without a working one the
 * test says the download never started rather than quietly passing.
 */
@RunWith(AndroidJUnit4::class)
class NotificationTimingCheck {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    private val bigFile: String =
        InstrumentationRegistry.getArguments().getString("bigFile")?.takeIf { it.isNotBlank() } ?: BIG_FILE

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        MediaDownloader.initialize(context)
    }

    @Test
    fun theNotificationIsUpAtOnceAndFillsInAsTheDownloadGoes() = runBlocking {
        val request = DownloadRequest(
            mediaUrl = bigFile,
            type = MediaType.Video,
            title = "A long video",
            sourceUrl = "https://www.facebook.com/",
            qualityLabel = "720p",
        )

        val enqueuedAt = SystemClock.elapsedRealtime()
        val id = MediaDownloader.download(request).getOrThrow()
        val progressId = (id * 2).toInt()

        var firstShownAt: Long? = null
        var firstWithSizeAt: Long? = null
        var firstWithTimeLeftAt: Long? = null
        var last = ""
        try {
            withTimeout(WATCH_MS) {
                while (firstWithTimeLeftAt == null) {
                    val shown = manager.activeNotifications.firstOrNull { it.id == progressId }?.notification
                    val since = SystemClock.elapsedRealtime() - enqueuedAt
                    if (shown != null) {
                        if (firstShownAt == null) note(since, "the notification is up")
                        firstShownAt = firstShownAt ?: since
                        val text = shown.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                        val header = shown.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
                        val line = "$text | $header"
                        if (line != last) {
                            note(since, line)
                            last = line
                        }
                        if (firstWithSizeAt == null && text.contains(" of ")) firstWithSizeAt = since
                        if (firstWithTimeLeftAt == null && header.contains("left")) firstWithTimeLeftAt = since
                    }
                    delay(POLL_MS)
                }
            }
        } finally {
            MediaDownloader.delete(id, deleteFile = true)
        }

        note(firstShownAt ?: -1, "FIRST SHOWN")
        note(firstWithSizeAt ?: -1, "FIRST WITH A SIZE")
        note(firstWithTimeLeftAt ?: -1, "FIRST WITH A TIME LEFT")

        assertNotNull("the download never put a notification up at all", firstShownAt)
        assertTrue("the notification took ${firstShownAt}ms to appear", firstShownAt!! < SHOWN_WITHIN_MS)
        assertNotNull("it never said how big the file is", firstWithSizeAt)
        assertNotNull("it never said how long is left", firstWithTimeLeftAt)
    }

    /** One download's whole life in the log, each line with how long after the press it was. */
    private fun note(sinceMillis: Long, what: String) =
        Log.d(TAG, "notification +${sinceMillis}ms: $what")

    private companion object {
        const val TAG = "MediaDownloader"
        const val POLL_MS = 100L
        const val WATCH_MS = 90_000L

        /** A notification that takes longer than this to appear is late enough to be a fault. */
        const val SHOWN_WITHIN_MS = 5_000L

        /** A signed Facebook CDN file of some size; it expires, so pass `bigFile` when it does. */
        const val BIG_FILE =
            "https://video.xx.fbcdn.net/o1/v/t2/f2/m366/AQN4NQCsC1kLr55OrSpmCGKp2qZ6v38VzGeBDt5-9h6UQCvHdDGV9G8Cvelq-erWy3K" +
            "6effcrXy4YIhFEgbktJbBA34t1QcEXKHX4bRFOB5QiA.mp4?_nc_cat=109&_nc_oc=Adqtwk37iG6WLfH66YTiyOGsYoCyVWAHD79Yy5-pDh4" +
            "-QARQ6yYiJgZcbBu7RlS0o7o&_nc_sid=5e9851&_nc_ht=video.fmux5-1.fna.fbcdn.net&_nc_ohc=2z7AKH3TTfIQ7kNvwF-5RJN&efg" +
            "=eyJ2ZW5jb2RlX3RhZyI6Inhwdl9wcm9ncmVzc2l2ZS5GQUNFQk9PSy4uQzMuMTI4MC5kYXNoX2gyNjQtYmFzaWMtZ2VuMl83MjBwIiwieHB2X" +
            "2Fzc2V0X2lkIjo4NTc4NTA3MDkzNjg4MjMsImFzc2V0X2FnZV9kYXlzIjoxMDg1LCJ2aV91c2VjYXNlX2lkIjoxMDEyMiwiZHVyYXRpb25fcyI" +
            "6MjcwNCwidXJsZ2VuX3NvdXJjZSI6Ind3dyJ9&ccb=17-1&vs=8c05769063a3cf0b&_nc_vs=HBksFQIYRWZiX2VwaGVtZXJhbC9ERjRCNTU1" +
            "RjE3MjY4ODlGMzQ5QjVCRDA0RUU1QkI4M19tdF8xX3ZpZGVvX2Rhc2hpbml0Lm1wNBUAAsgBEgAVAhgzZmJfcGVybWFuZW50LzI3NDg5NTI3NT" +
            "UyNzEzOF82NTgyMjQyNzM1MzE5NTMyMjMubXA0FQICyAESACgAGAAbAogHdXNlX29pbAExEnByb2dyZXNzaXZlX3JlY2lwZQExFQAAJu7_6uy9" +
            "jYYDFQIoAkMzLBdApSHrhR64UhgZZGFzaF9oMjY0LWJhc2ljLWdlbjJfNzIwcBEAdQJllJ4BAA&_nc_gid=4Nk_fAxUX-pvMZb51OisEA&_nc_" +
            "ss=7b20f&_nc_map=urlgen_bucketless&_nc_zt=28&oh=00_AQSD_vTzvjGwjOBnfbITLic6i2Qnk6Y0gD0A-bfckSTxgA&oe=6AF1F77B&" +
            "bitrate=1138691&tag=dash_h264-basic-gen2_720p"
    }
}
