package com.markhoor.mediadownloader.data.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * One lock per download, so two runs of the same download never overlap. They can: a pause
 * followed quickly by a resume replaces the job while the old run is still winding down, and both
 * would write the same files. The new run waits here until the old one has let go.
 */
internal class DownloadRunLocks {

    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withLock(id: Long, block: suspend () -> T): T =
        locks.computeIfAbsent(id) { Mutex() }.withLock { block() }
}
