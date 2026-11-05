package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.core.Constants.Download
import java.util.concurrent.ConcurrentHashMap

/**
 * How fast each running download is going, in bytes a second, for the time left on a notification
 * and on `DownloadModel`.
 *
 * **In memory on purpose, not in the database.** A column would change the schema, and this
 * database starts its table again rather than migrating it - a speed is not worth a user's list
 * of downloads. It is also worth nothing after a restart: a speed describes a transfer happening
 * now, and the process that was measuring it is gone.
 *
 * Written by whichever thread reports a download's progress and read by whoever is showing it, so
 * the state per download is one immutable value swapped in a [ConcurrentHashMap] - a reader sees
 * one sample or the next, never half of each.
 */
internal object DownloadRates {

    private val samples = ConcurrentHashMap<Long, Sample>()

    /**
     * What the last two reports say download [id] is doing, smoothed.
     *
     * Smoothed because the raw figure is not worth showing: a second's worth of bytes on a phone
     * swings with every change of signal, and a time left computed from it jumps between seconds
     * and minutes while nothing has actually changed. [Download.RATE_SMOOTHING] is how much of
     * each new reading is believed.
     *
     * @param nowMillis a monotonic clock - elapsed real time, never wall clock, which moves when
     *   the device's own time is corrected and would make a reading negative or enormous.
     */
    fun note(id: Long, downloadedBytes: Long, nowMillis: Long) {
        samples.compute(id) { _, previous -> previous.next(downloadedBytes, nowMillis) }
        if (samples.size > Download.MAX_TRACKED_RATES) forgetStale(nowMillis)
    }

    /**
     * Download [id]'s speed in bytes a second, or `null` where there is nothing honest to say: it
     * has not been seen twice yet, it has not reported for [Download.RATE_STALE_AFTER_MS] - paused,
     * waiting for a connection, or in a process that is gone - or it is not moving at all.
     */
    fun of(id: Long, nowMillis: Long): Long? = samples[id]
        ?.takeIf { nowMillis - it.atMillis <= Download.RATE_STALE_AFTER_MS }
        ?.bytesPerSecond
        ?.takeIf { it > 0 }

    /** Done with, by whatever ending. Staleness covers the rest; this keeps the map small. */
    fun forget(id: Long) {
        samples.remove(id)
    }

    private fun forgetStale(nowMillis: Long) {
        samples.entries.removeIf { nowMillis - it.value.atMillis > Download.RATE_STALE_AFTER_MS }
    }

    /**
     * One reading: where the download had got to, when, and the smoothed speed as of then.
     * `bytesPerSecond` is 0 until a second reading gives something to compare against.
     */
    private data class Sample(val bytes: Long, val atMillis: Long, val bytesPerSecond: Long)

    private fun Sample?.next(bytes: Long, nowMillis: Long): Sample {
        val previous = this ?: return Sample(bytes, nowMillis, bytesPerSecond = 0)
        val elapsed = nowMillis - previous.atMillis
        val gained = bytes - previous.bytes
        // A retry starts the file again and a resumed run restates where it had got to, so a count
        // that went backwards is a new beginning rather than a negative speed.
        if (gained < 0) return Sample(bytes, nowMillis, bytesPerSecond = 0)
        if (elapsed <= 0) return previous.copy(bytes = bytes)
        val justNow = gained * 1_000.0 / elapsed
        val smoothed = if (previous.bytesPerSecond <= 0) {
            justNow
        } else {
            Download.RATE_SMOOTHING * justNow + (1 - Download.RATE_SMOOTHING) * previous.bytesPerSecond
        }
        return Sample(bytes, nowMillis, smoothed.toLong())
    }
}
