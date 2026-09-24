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
}
