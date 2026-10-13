package com.markhoor.mediadownloader.data.repo

import com.markhoor.mediadownloader.data.browser.testResolver
import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.hls.HlsQualityReader
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.network.MediaSizeProbe
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScraperRacer
import com.markhoor.mediadownloader.data.scraper.SignInRequiredException
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A site that only answers a signed-in request reaches the host as a failure it can act on: the
 * link is not empty, the user is not signed in, and the browser is where they would fix that.
 */
class SignInNeededTest {

    private val reel = "https://www.instagram.com/reel/DQN9eI6CF55/"

    private class Refusing(private val error: Throwable) : SiteScraper() {
        override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = throw error
    }

    private fun repositoryWhoseInstagram(scraper: SiteScraper): MediaParserRepositoryImpl {
        val client = FakeMediaServer(emptyMap()).client
        val fetcher = HttpFetcher(client, HttpClientFactory.json)
        val sizeProbe = MediaSizeProbe(client)
        return MediaParserRepositoryImpl(
            resolver = testResolver(instagram = scraper),
            racer = ScraperRacer(),
            hlsQualityReader = HlsQualityReader(fetcher, sizeProbe),
            sizeProbe = sizeProbe,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `a site that needs a session says so, in the failure the host receives`() = runTest {
        val repository = repositoryWhoseInstagram(Refusing(SignInRequiredException("Instagram")))

        val error = repository.parse(reel).exceptionOrNull()

        val notFound = error as? MediaParseException.MediaNotFound
        assertTrue("expected a parse failure, got $error", notFound != null)
        assertTrue("the host is told a session is what is missing", notFound?.needsSignIn == true)
        assertEquals("Sign in to this site in the browser, then try again", notFound?.message)
    }

    @Test
    fun `an ordinary empty link is still an ordinary empty link`() = runTest {
        val repository = repositoryWhoseInstagram(Refusing(IllegalStateException("the page was gibberish")))

        val notFound = repository.parse(reel).exceptionOrNull() as? MediaParseException.MediaNotFound

        assertFalse("nobody is told to sign in over a broken page", notFound?.needsSignIn == true)
        assertEquals("No media found behind this link", notFound?.message)
    }
}
