package com.markhoor.mediadownloader.data.repo

import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.asMediaTitle
import com.markhoor.mediadownloader.core.cleanTitle
import com.markhoor.mediadownloader.core.isHlsPlaylistUrl
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.sensibleSize
import com.markhoor.mediadownloader.data.hls.HlsQualityReader
import com.markhoor.mediadownloader.data.network.MediaSizeProbe
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScraperRacer
import com.markhoor.mediadownloader.data.scraper.ScraperResolver
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaParseException
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.domain.repo.MediaParserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Scrapes a link and turns what the scrapers read into media ready to offer: a lone HLS stream is
 * expanded into its qualities, every other quality is measured, sizes that cannot be true are
 * dropped, and the title is cleaned.
 */
internal class MediaParserRepositoryImpl(
    private val resolver: ScraperResolver,
    private val racer: ScraperRacer,
    private val hlsQualityReader: HlsQualityReader,
    private val sizeProbe: MediaSizeProbe,
    private val ioDispatcher: CoroutineDispatcher,
) : MediaParserRepository {

    override suspend fun parse(url: String): Result<MediaModel> = withContext(ioDispatcher) {
        val scrapers = resolver.scrapersFor(url)
        if (scrapers.isEmpty()) return@withContext Result.failure(MediaParseException.LinkNotRecognised(url))
        racer.firstSuccess(scrapers, url).fold(
            onSuccess = { scraped -> Result.success(mediaModelOf(scraped, url)) },
            onFailure = { error -> Result.failure(MediaParseException.MediaNotFound(url, error)) },
        )
    }

    private suspend fun mediaModelOf(scraped: ScrapedMediaDto, sourceUrl: String): MediaModel {
        val qualities = expandedQualities(scraped)
        val sized = coroutineScope { qualities.map { async { withSensibleSize(it) } }.awaitAll() }
        return MediaModel(
            title = scraped.title?.cleanTitle()?.asMediaTitle().orEmpty(),
            thumbnailUrl = scraped.thumbnailUrl?.takeIf { it.isHttpUrl() },
            qualities = sized,
            sourceUrl = sourceUrl,
            durationMillis = scraped.durationMillis,
        )
    }

    /** The scraped qualities, or - when the only one is an HLS playlist - the qualities it lists. */
    private suspend fun expandedQualities(scraped: ScrapedMediaDto): List<MediaQualityModel> {
        val qualities = scraped.qualities.filter { it.url.isNotBlank() }.map { quality ->
            MediaQualityModel(
                url = quality.url,
                label = quality.label?.ifBlank { null } ?: QualityLabels.HD,
                type = quality.type,
                sizeBytes = quality.sizeBytes,
                audioUrl = quality.audioUrl,
                headers = scraped.headers,
            )
        }
        val stream = qualities.singleOrNull()?.takeIf { it.url.isHlsPlaylistUrl() } ?: return qualities
        return hlsQualityReader.qualitiesOf(stream.url, stream.headers).ifEmpty { qualities }
    }

    /** A file is asked its size; a stream's size was already worked out from its playlist. */
    private suspend fun withSensibleSize(quality: MediaQualityModel): MediaQualityModel {
        val measured = if (quality.url.isHlsPlaylistUrl()) null else sizeProbe.sizeOf(quality.url, quality.headers)
        return quality.copy(sizeBytes = (measured ?: quality.sizeBytes).sensibleSize(isVideo = quality.type == MediaType.Video))
    }
}
