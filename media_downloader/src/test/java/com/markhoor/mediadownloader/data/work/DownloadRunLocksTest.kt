package com.markhoor.mediadownloader.data.work

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two runs of one download must not overlap, and the locks that see to it must not pile up: an
 * entry was kept for every download the app ever ran, for the life of the process.
 */
class DownloadRunLocksTest {

    @Test
    fun `two runs of one download do not overlap`() = runTest {
        val locks = DownloadRunLocks()
        var running = 0
        var seenTogether = 0

        listOf(1, 2, 3).map {
            async {
                locks.withLock(7L) {
                    running++
                    if (running > 1) seenTogether++
                    delay(10)
                    running--
                }
            }
        }.awaitAll()

        assertEquals("two runs of one download ran at once", 0, seenTogether)
    }

    @Test
    fun `a lock is dropped once nobody wants it`() = runTest {
        val locks = DownloadRunLocks()

        (1L..50L).map { id -> async { locks.withLock(id) { delay(1) } } }.awaitAll()

        assertEquals("the map still holds locks nobody is using", 0, locks.held)
    }

    /**
     * The hazard in dropping it the moment it is free: between one run letting go and the next
     * taking it, a third run asking would be handed a new lock and run beside the second. Here the
     * runs are started while the first still holds it, so each is waiting when the one before
     * lets go - and the lock must survive the whole chain.
     */
    @Test
    fun `a lock survives a run that is already waiting for it`() = runTest {
        val locks = DownloadRunLocks()
        val order = mutableListOf<Int>()

        val runs = (1..4).map { n ->
            async {
                locks.withLock(9L) {
                    order += n
                    delay(5)
                }
            }
        }
        runs.awaitAll()

        assertEquals("every run took its turn", 4, order.size)
        assertEquals(0, locks.held)
    }
}
