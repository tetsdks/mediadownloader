package com.markhoor.mediadownloader.data.scraper

import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.CancellationException

/**
 * Reads media out of one site's link.
 *
 * Implementations write [scrapeOrNull] and may simply throw: the network stack can throw out of
 * any request, and [scrape] turns every failure into [Result.failure] in one place instead of in
 * every scraper. Cancellation is always rethrown.
 */
internal abstract class SiteScraper {

    /** The media behind [url], or `null` when there is none. May throw. */
    protected abstract suspend fun scrapeOrNull(url: String): ScrapedMediaDto?

    suspend fun scrape(url: String): Result<ScrapedMediaDto> = try {
        scrapeOrNull(url)
            ?.takeIf { media -> media.qualities.any { it.url.isNotBlank() } }
            ?.let { Result.success(it) }
            ?: Result.failure(NoMediaException("${javaClass.simpleName} found no media at $url"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

/** A scraper ran and found nothing. */
internal class NoMediaException(message: String) : Exception(message)

/**
 * The post is not there - deleted, or never was. Told apart from the ordinary nothing-found because
 * it rules a sign-in out: only a reader that sees a site as a signed-in account can say it, and
 * when such a reader cannot find a post, a session was not what was missing.
 */
internal class MediaGoneException(message: String) : Exception(message)

/**
 * Every reader of a site came back empty, and the site only answers a signed-in request. Told
 * apart from finding nothing because what the user can do about it is different: signing in to the
 * site in the browser is what would fix it, and the host can say so.
 */
internal class SignInRequiredException(val site: String) :
    Exception("$site gives a signed-out request no media")

/** Media as one scraper read it, before labels are settled, sizes measured and streams expanded. */
internal data class ScrapedMediaDto(
    val qualities: List<ScrapedQualityDto>,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val durationMillis: Long? = null,
    /** Headers every quality's download needs. */
    val headers: Map<String, String> = emptyMap(),
)

internal data class ScrapedQualityDto(
    val url: String,
    val type: MediaType,
    val label: String? = null,
    val sizeBytes: Long? = null,
    val audioUrl: String? = null,
)
