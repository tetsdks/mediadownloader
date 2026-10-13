package com.markhoor.mediadownloader.core

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stream the host collects in its own scope, where a throw is not a failed read but a dead
 * process. A full disk makes even a `SELECT` fail, so this is not a theoretical one.
 */
class FlowExtensionsTest {

    @Test
    fun `a stream that fails ends, and keeps what it had already read`() = runTest {
        val read = flow {
            emit("one")
            emit("two")
            throw IllegalStateException("database or disk is full (code 13 SQLITE_FULL)")
        }

        val seen = read.endingOnFailure("the test's stream").toList()

        assertEquals(listOf("one", "two"), seen)
    }

    @Test
    fun `a stream that does not fail is untouched`() = runTest {
        val read = flow { emit(1); emit(2); emit(3) }

        assertEquals(listOf(1, 2, 3), read.endingOnFailure("the test's stream").toList())
    }

    /** A screen going away still ends the collection: a guard against failures is not a shield. */
    @Test
    fun `a cancelled collector is still cancelled`() = runTest {
        val collecting = launch {
            flow { emit(1); awaitCancellation() }.endingOnFailure("the test's stream").collect { }
        }
        runCurrent()

        collecting.cancelAndJoin()

        assertTrue(collecting.isCancelled)
    }
}
