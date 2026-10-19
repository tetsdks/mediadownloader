package com.markhoor.mediadownloader.data.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One lock per download, so two runs of the same download never overlap. They can: a pause
 * followed quickly by a resume replaces the job while the old run is still winding down, and both
 * would write the same files. The new run waits here until the old one has let go.
 *
 * A lock is dropped once nobody holds or is waiting for it. It used to be kept: an entry per
 * download the app ever ran, for the life of the process - small each, unbounded together, in a
 * singleton that lives as long as the app does.
 *
 * Counting who wants it is what makes dropping it safe. Removing it the moment it was free is not:
 * between one run letting go and the next taking it, the entry would be gone from the map, and a
 * third run asking then would be handed a **new** lock and run beside the second - which is the
 * one thing this class exists to prevent.
 */
internal class DownloadRunLocks {

    private class Entry {
        val mutex = Mutex()
        var wanted = 0
    }

    private val locks = HashMap<Long, Entry>()

    /** How many ids are held, so a test can watch the map empty itself. */
    val held: Int get() = synchronized(locks) { locks.size }

    suspend fun <T> withLock(id: Long, block: suspend () -> T): T {
        // Counted before anyone can see it as free, so the entry cannot be taken away underneath
        // a run that is on its way to it.
        val entry = synchronized(locks) { locks.getOrPut(id) { Entry() }.also { it.wanted++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(locks) {
                if (--entry.wanted == 0) locks.remove(id)
            }
        }
    }
}
