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
