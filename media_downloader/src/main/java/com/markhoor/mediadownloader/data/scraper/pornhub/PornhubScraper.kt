package com.markhoor.mediadownloader.data.scraper.pornhub

import com.markhoor.mediadownloader.core.Constants.Pornhub
import com.markhoor.mediadownloader.core.decodeHtmlEntities
import com.markhoor.mediadownloader.core.qualityNameFromResolution
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.NoMediaException
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A video on the PornHub network, read from the player object its watch page carries
 * ([PornhubParser]). It is only ever reached when the host app allows adult sites; site access
 * refuses the link before any scraper is asked otherwise.
 *
 * The page names its qualities two ways. Each listed stream is a master playlist for one height,
 * and one entry points at a signed endpoint that answers with the same video as plain files. The
 * files are preferred: one request per download, a size that can be measured before it starts, and
 * no remux afterwards. Those urls are signed for the address that asked for them - this device -
 * so they are read when the link is parsed rather than kept for later.
 */
internal class PornhubScraper(
    private val fetcher: HttpFetcher,
) : SiteScraper() {

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val player = PornhubParser.flashvarsJsonOf(pageOf(url))
            ?.let { fetcher.json.decodeFromString<PornhubPlayerDto>(it) }
            ?: return null
        return ScrapedMediaDto(
            qualities = filesOf(player) ?: bestStreamOf(player) ?: return null,
            title = player.title?.decodeHtmlEntities()?.trim()?.ifBlank { null },
            thumbnailUrl = player.thumbnailUrl?.ifBlank { null },
            durationMillis = player.durationSeconds?.takeIf { it > 0 }?.times(1_000),
            headers = Pornhub.HEADERS,
        )
    }

    /**
     * The watch page, asked for more than once. Networks that filter this site mostly do it by
     * resetting the connection rather than by answering, and a reset arrives at once and lands on
     * roughly half of the attempts - so the same request, repeated, usually gets through. Only
     * failures are retried; a page that answers is never fetched twice.
     */
    private suspend fun pageOf(url: String): String {
        var failure: Throwable? = null
        repeat(Pornhub.PAGE_ATTEMPTS) {
            val attempt = fetcher.getText(url, Pornhub.HEADERS)
            attempt.getOrNull()?.let { return it }
            failure = attempt.exceptionOrNull()
        }
        throw failure ?: NoMediaException("No page at $url")
    }

    /** The plain files, behind the signed endpoint the player object names. */
    private suspend fun filesOf(player: PornhubPlayerDto): List<ScrapedQualityDto>? {
        val endpoint = player.definitions
            .firstOrNull { it.format == Pornhub.FORMAT_FILE && !it.videoUrl.isNullOrBlank() }
            ?.videoUrl ?: return null
        val files = fetcher.getJson<List<PornhubDefinitionDto>>(endpoint, Pornhub.HEADERS)
            .getOrNull().orEmpty()
        return qualitiesOf(files, Pornhub.FORMAT_FILE).ifEmpty { null }
    }

    /**
     * The best stream, and only the best. A master playlist is expanded into every height it
     * lists further down the pipeline, and that only happens to a quality that arrives on its own:
     * handing over all four masters would offer the same video four times, unexpanded.
     */
    private fun bestStreamOf(player: PornhubPlayerDto): List<ScrapedQualityDto>? =
        qualitiesOf(player.definitions, Pornhub.FORMAT_STREAM).take(1).ifEmpty { null }

    private fun qualitiesOf(
        definitions: List<PornhubDefinitionDto>,
        format: String,
    ): List<ScrapedQualityDto> = definitions
        .filter { it.format == format && !it.videoUrl.isNullOrBlank() && it.shortSide > 0 }
        .sortedByDescending { it.shortSide }
        .distinctBy { it.shortSide }
        .map {
            ScrapedQualityDto(
                url = it.videoUrl.orEmpty(),
                type = MediaType.Video,
                label = it.resolution.qualityNameFromResolution(),
            )
        }
}

@Serializable
internal data class PornhubPlayerDto(
    @SerialName("video_title") val title: String? = null,
    @SerialName("video_duration") val durationSeconds: Long? = null,
    @SerialName("image_url") val thumbnailUrl: String? = null,
    @SerialName("mediaDefinitions") val definitions: List<PornhubDefinitionDto> = emptyList(),
)

/**
 * One quality. The site's own `quality` field is deliberately not read: it is a string on a stream
 * and an empty array on the entry that points at the files, while the size says the same thing in
 * both - and says it the way the rest of the module names qualities.
 */
@Serializable
internal data class PornhubDefinitionDto(
    val format: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoUrl: String? = null,
) {
    val resolution: String get() = "${width ?: height ?: 0}x${height ?: 0}"

    /** What the quality is named after: the short side, as it is for a stream's resolution. */
    val shortSide: Int get() = listOfNotNull(width, height).minOrNull() ?: 0
}
