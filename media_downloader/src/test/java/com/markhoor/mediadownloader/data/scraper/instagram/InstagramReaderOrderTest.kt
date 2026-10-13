package com.markhoor.mediadownloader.data.scraper.instagram

import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.CookieSource
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.MediaGoneException
import com.markhoor.mediadownloader.data.scraper.NoMediaException
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SignInRequiredException
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the five readers of an Instagram post is believed, and how long the others are made to
 * wait for it. The times here are the virtual clock's, so they measure the ranking itself rather
 * than the machine the test runs on.
 */
class InstagramReaderOrderTest {

    private val reel = "https://www.instagram.com/reel/DQN9eI6CF55"
    private val session = "sessionid=the-user-own-session"

    private class Answers(private val name: String, private val after: Long, private val found: Boolean = true) :
        SiteScraper() {
        override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
            delay(after)
            if (!found) return null
            return ScrapedMediaDto(
                qualities = listOf(ScrapedQualityDto("https://cdn.test/$name.mp4", MediaType.Video)),
                title = name,
                thumbnailUrl = "https://cdn.test/$name.jpg",
            )
        }
    }

    private fun nothing(name: String, after: Long = 10) = Answers(name, after, found = false)

    private fun scraperOf(
        signedIn: SiteScraper,
        ours: SiteScraper,
        backup: SiteScraper,
        cookies: CookieSource = CookieSource { session },
    ) = InstagramScraper(
        fetcher = HttpFetcher(FakeMediaServer(emptyMap()).client, HttpClientFactory.json),
        signedIn = signedIn,
        preview = ours,
        embed = nothing("embed"),
        graphQl = nothing("graphQl"),
        getInDevice = backup,
        cookies = cookies,
    )

    /** The reader that sees what the user sees is the one believed, whenever it answers. */
    @Test
    fun `the signed-in reader wins when it answers first`() = runTest {
        val scraper = scraperOf(
            signedIn = Answers("signedIn", after = 100),
            ours = Answers("preview", after = 400),
            backup = Answers("resolver", after = 50),
        )

        val media = scraper.scrape(reel).getOrThrow()

        assertEquals("signedIn", media.title)
        assertEquals(100, testScheduler.currentTime)
    }

    /** It is slower than the others and still preferred, as long as it is only a little slower. */
    @Test
    fun `the signed-in reader is waited for while another answer is held`() = runTest {
        val scraper = scraperOf(
            signedIn = Answers("signedIn", after = 900),
            ours = Answers("preview", after = 100),
            backup = Answers("resolver", after = 50),
        )

        val media = scraper.scrape(reel).getOrThrow()

        assertEquals("signedIn", media.title)
    }

    /**
     * The fix this test exists for: a signed-in page that is slow used to hold the whole parse,
     * however long it took. Measured live on an Instagram `/p/` post: **13.9s**, where every other
     * reader had answered inside two seconds.
     */
    @Test
    fun `a slow signed-in reader no longer holds the answer`() = runTest {
        val scraper = scraperOf(
            signedIn = Answers("signedIn", after = 60_000),
            ours = Answers("preview", after = 100),
            backup = Answers("resolver", after = 50),
        )

        val media = scraper.scrape(reel).getOrThrow()

        assertEquals("preview", media.title)
        // Its own answer, plus the grace the signed-in reader is given and does not use.
        assertEquals(1_600, testScheduler.currentTime)
    }

    /** With no reader of ours answering either, the resolver's answer is taken after its grace. */
    @Test
    fun `a slow signed-in reader does not hold the resolver's answer either`() = runTest {
        val scraper = scraperOf(
            signedIn = Answers("signedIn", after = 60_000),
            ours = nothing("preview", after = 80),
            backup = Answers("resolver", after = 300),
        )

        val media = scraper.scrape(reel).getOrThrow()

        assertEquals("resolver", media.title)
        assertEquals(1_800, testScheduler.currentTime)
    }

    /**
     * A private post: nobody but the user can read it, so the readers that cannot must not end the
     * parse between them. A resolver that fails used to do exactly that - its branch finished with
     * nothing and took the select with it.
     */
    @Test
    fun `a post only the user can see is still waited for`() = runTest {
        val scraper = scraperOf(
            signedIn = Answers("signedIn", after = 5_000),
            ours = nothing("preview", after = 100),
            backup = nothing("resolver", after = 120),
        )

        val media = scraper.scrape(reel).getOrThrow()

        assertEquals("signedIn", media.title)
        assertEquals(5_000, testScheduler.currentTime)
    }

    /**
     * The resolver reads a post as a signed-in account, so when **it** cannot find one, a session
     * was not what was missing and the user must not be sent to sign in. Measured on a real reel
     * link whose post did not exist: every reader of ours was locked out and said nothing, and the
     * resolver was the only one that knew why.
     */
    @Test
    fun `a post the resolver says is gone is not blamed on signing in`() = runTest {
        val gone = object : SiteScraper() {
            override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
                delay(120)
                throw MediaGoneException("The post or video could not be found.")
            }
        }
        val scraper = scraperOf(
            signedIn = nothing("signedIn", after = 50),
            ours = nothing("preview", after = 60),
            backup = gone,
            cookies = CookieSource { null },
        )

        val failure = scraper.scrape(reel).exceptionOrNull()

        assertTrue("expected a plain nothing-found, got $failure", failure is NoMediaException)
    }

    @Test
    fun `when every reader comes back empty the failure is the sign-in one`() = runTest {
        val scraper = scraperOf(
            signedIn = nothing("signedIn", after = 50),
            ours = nothing("preview", after = 60),
            backup = nothing("resolver", after = 70),
            cookies = CookieSource { null },
        )

        val failure = scraper.scrape(reel).exceptionOrNull()

        assertTrue("expected a sign-in to be asked for, got $failure", failure is SignInRequiredException)
    }
}
