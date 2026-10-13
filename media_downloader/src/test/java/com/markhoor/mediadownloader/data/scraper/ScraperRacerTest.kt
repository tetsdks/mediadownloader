package com.markhoor.mediadownloader.data.scraper

import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScraperRacerTest {

    private val racer = ScraperRacer()

    private fun media(url: String) = ScrapedMediaDto(listOf(ScrapedQualityDto(url, MediaType.Video)))

    private class FakeScraper(private val answer: suspend () -> ScrapedMediaDto?) : SiteScraper() {
        override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = answer()
    }

    @Test
    fun `the first scraper to find media wins and the others are cancelled`() = runTest {
        val slowCancelled = CompletableDeferred<Unit>()
        val slow = FakeScraper {
            try {
                awaitCancellation()
            } finally {
                slowCancelled.complete(Unit)
            }
        }
        val fast = FakeScraper { delay(10); media("https://fast.example/v.mp4") }

        val result = racer.firstSuccess(listOf(slow, fast), "https://example.com/v")

        assertEquals("https://fast.example/v.mp4", result.getOrThrow().qualities.single().url)
        assertTrue(slowCancelled.isCompleted)
    }

    @Test
    fun `when every scraper fails the last failure is the answer`() = runTest {
        val first = FakeScraper { delay(10); throw IOException("first") }
        val last = FakeScraper { delay(20); throw IOException("last") }

        val result = racer.firstSuccess(listOf(first, last), "https://example.com/v")

        assertEquals("last", result.exceptionOrNull()?.message)
    }

    /** A scraper that throws is a failed scraper, never a crash. */
    @Test
    fun `a throwing scraper loses to one that answers`() = runTest {
        val throwing = FakeScraper { throw IllegalStateException("boom") }
        val answering = FakeScraper { delay(5); media("https://ok.example/v.mp4") }

        assertTrue(racer.firstSuccess(listOf(throwing, answering), "https://example.com/v").isSuccess)
    }

    /** Media with no url is nothing found, whatever the scraper returned. */
    @Test
    fun `an answer without a url is not media`() = runTest {
        val empty = FakeScraper { ScrapedMediaDto(listOf(ScrapedQualityDto("", MediaType.Video))) }
        assertTrue(racer.firstSuccess(listOf(empty), "https://example.com/v").isFailure)
    }

    // region A backup reader

    /** The whole point: quicker is not better. Ours answers last and still wins. */
    @Test
    fun `a backup never wins against a reader of ours, however quick it is`() = runTest {
        val backup = FakeScraper { media("https://backup.example/v.mp4") }
        val ours = FakeScraper { delay(500); media("https://ours.example/v.mp4") }

        val result = racer.firstSuccess(listOf(ours), "https://example.com/v", backups = listOf(backup))

        assertEquals("https://ours.example/v.mp4", result.getOrThrow().qualities.single().url)
    }

    @Test
    fun `a backup answers where every reader of ours came back empty`() = runTest {
        val backup = FakeScraper { delay(50); media("https://backup.example/v.mp4") }
        val ours = listOf(
            FakeScraper { delay(10); throw IOException("ours, offline") },
            FakeScraper { delay(20); null },
        )

        val result = racer.firstSuccess(ours, "https://example.com/v", backups = listOf(backup))

        assertEquals("https://backup.example/v.mp4", result.getOrThrow().qualities.single().url)
    }

    /**
     * A reader of ours that has stalled - a site holding the connection to the request timeout -
     * does not hold the answer with it. It is given a grace and then the backup is taken.
     */
    @Test
    fun `a reader of ours that stalls loses the grace, not the whole minute`() = runTest {
        val stalled = FakeScraper { delay(60_000); media("https://ours.example/v.mp4") }
        val backup = FakeScraper { delay(100); media("https://backup.example/v.mp4") }

        val started = testScheduler.currentTime
        val result = racer.firstSuccess(listOf(stalled), "https://example.com/v", backups = listOf(backup))
        val waited = testScheduler.currentTime - started

        assertEquals("https://backup.example/v.mp4", result.getOrThrow().qualities.single().url)
        assertTrue("waited ${waited}ms, which is the stalled reader's whole timeout", waited < 5_000)
    }

    /** Nothing of ours to run at all - an X link with no key - and the backup is the only reader. */
    @Test
    fun `a backup on its own is still asked`() = runTest {
        val backup = FakeScraper { media("https://backup.example/v.mp4") }

        val result = racer.firstSuccess(emptyList(), "https://example.com/v", backups = listOf(backup))

        assertTrue(result.isSuccess)
    }

    /**
     * Our own reader's failure is the one reported: it is what carries why the post could not be
     * read - a sign-in would have done it - where the backup's failure says only that a service
     * somewhere said no.
     */
    @Test
    fun `when both fail it is our own failure that is reported`() = runTest {
        val ours = FakeScraper { delay(10); throw IOException("sign in to read this") }
        val backup = FakeScraper { delay(50); throw IOException("the resolver is busy") }

        val result = racer.firstSuccess(listOf(ours), "https://example.com/v", backups = listOf(backup))

        assertEquals("sign in to read this", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a backup that is not needed is cancelled with the rest`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val backup = FakeScraper {
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val ours = FakeScraper { delay(10); media("https://ours.example/v.mp4") }

        racer.firstSuccess(listOf(ours), "https://example.com/v", backups = listOf(backup))

        assertTrue(cancelled.isCompleted)
    }

    // endregion
}
