package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.core.Constants.Download
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speed behind a download's time left. The clock is passed in, so these are exact rather than
 * timed: a test that slept would measure this machine instead of the arithmetic.
 */
class DownloadRatesTest {

    private val id = 7L

    @After
    fun forget() = DownloadRates.forget(id)

    @Test
    fun `one reading says nothing, because a speed needs two`() {
        DownloadRates.note(id, downloadedBytes = 1_000, nowMillis = 1_000)

        assertNull(DownloadRates.of(id, nowMillis = 1_000))
    }

    @Test
    fun `two readings a second apart are the bytes between them`() {
        DownloadRates.note(id, downloadedBytes = 0, nowMillis = 1_000)
        DownloadRates.note(id, downloadedBytes = 500_000, nowMillis = 2_000)

        assertEquals(500_000L, DownloadRates.of(id, nowMillis = 2_000))
    }

    /**
     * A phone's connection is not steady, and the raw figure is not worth showing: here the second
     * is four times the first, and what is reported moves part of the way rather than all of it,
     * so a time left computed from it does not leap about while nothing has really changed.
     */
    @Test
    fun `a sudden change is believed only in part`() {
        DownloadRates.note(id, downloadedBytes = 0, nowMillis = 1_000)
        DownloadRates.note(id, downloadedBytes = 1_000_000, nowMillis = 2_000)
        DownloadRates.note(id, downloadedBytes = 5_000_000, nowMillis = 3_000)

        val smoothed = DownloadRates.of(id, nowMillis = 3_000)!!
        assertEquals(1_000_000 + Download.RATE_SMOOTHING * 3_000_000, smoothed.toDouble(), 1.0)
        assertTrue("between the old reading and the new", smoothed in 1_000_001..4_999_999)
    }

    /** Paused, waiting for a connection, or in a process that is gone: all of them fall silent. */
    @Test
    fun `a download that has stopped reporting has no speed`() {
        DownloadRates.note(id, downloadedBytes = 0, nowMillis = 1_000)
        DownloadRates.note(id, downloadedBytes = 500_000, nowMillis = 2_000)

        assertEquals(500_000L, DownloadRates.of(id, nowMillis = 2_000 + Download.RATE_STALE_AFTER_MS))
        assertNull(DownloadRates.of(id, nowMillis = 2_001 + Download.RATE_STALE_AFTER_MS))
    }

    /** A retry starts the file again, which is a beginning and not a negative speed. */
    @Test
    fun `a count that went backwards starts again rather than reading below zero`() {
        DownloadRates.note(id, downloadedBytes = 900_000, nowMillis = 1_000)
        DownloadRates.note(id, downloadedBytes = 0, nowMillis = 2_000)

        assertNull(DownloadRates.of(id, nowMillis = 2_000))

        DownloadRates.note(id, downloadedBytes = 200_000, nowMillis = 3_000)
        assertEquals(200_000L, DownloadRates.of(id, nowMillis = 3_000))
    }

    @Test
    fun `a download standing still has no speed to report`() {
        DownloadRates.note(id, downloadedBytes = 500_000, nowMillis = 1_000)
        DownloadRates.note(id, downloadedBytes = 500_000, nowMillis = 2_000)

        assertNull(DownloadRates.of(id, nowMillis = 2_000))
    }

    @Test
    fun `two readings at the same moment do not divide by nothing`() {
        DownloadRates.note(id, downloadedBytes = 0, nowMillis = 1_000)
        DownloadRates.note(id, downloadedBytes = 500_000, nowMillis = 1_000)

        assertNull(DownloadRates.of(id, nowMillis = 1_000))
    }

    @Test
    fun `one download's speed is not another's`() {
        DownloadRates.note(id, downloadedBytes = 0, nowMillis = 1_000)
        DownloadRates.note(id, downloadedBytes = 500_000, nowMillis = 2_000)
        DownloadRates.note(id = 8, downloadedBytes = 0, nowMillis = 1_000)
        DownloadRates.note(id = 8, downloadedBytes = 100_000, nowMillis = 2_000)

        assertEquals(500_000L, DownloadRates.of(id, nowMillis = 2_000))
        assertEquals(100_000L, DownloadRates.of(id = 8, nowMillis = 2_000))
        DownloadRates.forget(8)
    }

    @Test
    fun `a download forgotten has no speed`() {
        DownloadRates.note(id, downloadedBytes = 0, nowMillis = 1_000)
        DownloadRates.note(id, downloadedBytes = 500_000, nowMillis = 2_000)
        DownloadRates.forget(id)

        assertNull(DownloadRates.of(id, nowMillis = 2_000))
    }
}
