package com.markhoor.mediadownloader.data.scraper.instagram

import com.markhoor.mediadownloader.core.Constants.Instagram
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * An Instagram post or reel, or a Threads post.
 *
 * The signed-in scraper decides whenever it answers - it is the only one that sees what the user
 * sees. The fallbacks run alongside it, in two tiers: every one returns the same media, but some
 * return no artwork, and being quickest must not be enough to win. An answer with artwork wins at
 * once; one without waits [Instagram.METADATA_GRACE_MS] for a richer one before it is taken.
 */
internal class InstagramScraper(
    private val signedIn: SiteScraper,
    private val preview: SiteScraper,
    private val embed: SiteScraper,
    private val graphQl: SiteScraper,
    private val getInDevice: SiteScraper,
) : SiteScraper() {

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = coroutineScope {
        val signedInAnswer = async { signedIn.scrape(url).getOrNull() }
        val withArtwork = CompletableDeferred<ScrapedMediaDto>()
        val withoutArtwork = CompletableDeferred<ScrapedMediaDto>()
        val fallbacks = fallbacksFor(url).map { scraper ->
            launch {
                val media = scraper.scrape(url).getOrNull() ?: return@launch
                if (media.thumbnailUrl.isNullOrBlank()) withoutArtwork.complete(media) else withArtwork.complete(media)
            }
        }

        val chosen = signedInAnswer.await() ?: bestFallback(withArtwork, withoutArtwork, fallbacks)
        fallbacks.forEach { it.cancel() }
        chosen
    }

    /** A `/p/` post is only answered by GraphQL; a reel by all four. */
    private fun fallbacksFor(url: String): List<SiteScraper> =
        if (url.contains("/p/")) listOf(graphQl) else listOf(preview, embed, graphQl, getInDevice)

    private suspend fun bestFallback(
        withArtwork: CompletableDeferred<ScrapedMediaDto>,
        withoutArtwork: CompletableDeferred<ScrapedMediaDto>,
        fallbacks: List<Job>,
    ): ScrapedMediaDto? = coroutineScope {
        val afterGrace = async { withoutArtwork.await().also { delay(Instagram.METADATA_GRACE_MS) } }
        val whenAllDone = async {
            fallbacks.joinAll()
            withoutArtwork.takeIf { it.isCompleted }?.await()
        }
        val chosen = select<ScrapedMediaDto?> {
            withArtwork.onAwait { it }
            afterGrace.onAwait { it }
            whenAllDone.onAwait { it }
        }
        afterGrace.cancel()
        whenAllDone.cancel()
        chosen
    }
}
