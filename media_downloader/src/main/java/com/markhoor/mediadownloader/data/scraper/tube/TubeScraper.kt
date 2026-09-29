package com.markhoor.mediadownloader.data.scraper.tube

import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.Tube
import com.markhoor.mediadownloader.core.decodeHtmlEntities
import com.markhoor.mediadownloader.core.normalizedHost
import com.markhoor.mediadownloader.core.qualityNameFromResolution
import com.markhoor.mediadownloader.core.titleFromHtml
import com.markhoor.mediadownloader.core.urlPath
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.NoMediaException
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One reader for the tube sites, rather than one reader each. They are too many to write a scraper
 * apiece for and too alike to need it: [TubePlayerParser] recognises the few player shapes between
 * them, and this fetches the page and follows whatever that shape still owes.
 *
 * It is only ever asked about a site the configuration allows, and only about a page whose address
 * looks like a video's. A site it cannot read - one that hands its player encrypted urls - returns
 * nothing and is left to the browser, which can watch what the player fetches.
 *
 * @param isRestrictedSite whether a host belongs to the category these sites are listed under, so
 *   mirrors and hosts that name themselves are covered without listing them here.
 */
internal class TubeScraper(
    private val fetcher: HttpFetcher,
    private val isRestrictedSite: (String) -> Boolean,
) : SiteScraper() {

    /** Whether this is a page worth reading: the right kind of site, and a video's address on it. */
    fun reads(url: String): Boolean {
        val host = url.normalizedHost() ?: return false
        if (!isRestrictedSite(host)) return false
        val path = url.urlPath().orEmpty()
        return Tube.VIDEO_PATH_MARKERS.any { path.contains(it) } || url.contains(Tube.VIDEO_QUERY_MARKER)
    }

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val page = pageOf(url)
        val headers = headersFor(url)
        val media = aylo(page, headers)
            ?: TubePlayerParser.fromPlayerCalls(page)
            ?: TubePlayerParser.fromLinkedData(page)
            ?: TubePlayerParser.fromOpenGraph(page)
            ?: return null
        // Every shape names the video except Open Graph on a page that left it out; the page's
        // own title is the last thing left to call it.
        return media.copy(
            title = media.title ?: page.titleFromHtml().ifBlank { null },
            headers = headers,
        )
    }

    /**
     * Aylo's network (PornHub, RedTube, YouPorn, Tube8) hands its player an object listing every
     * quality as a master playlist, and one entry pointing at a signed endpoint that answers with
     * the same video as plain files. The files are preferred: one request per download, a size that
     * can be measured before it starts, and no remux afterwards. Those urls are signed for the
     * address that asked for them, so they are read now rather than kept for later.
     */
    private suspend fun aylo(page: String, headers: Map<String, String>): ScrapedMediaDto? {
        val player = TubePlayerParser.playerObjectOf(page)
            ?.let { fetcher.json.decodeFromString<AyloPlayerDto>(it) }
            ?: return null
        val qualities = filesOf(player, headers) ?: bestStreamOf(player) ?: return null
        return ScrapedMediaDto(
            qualities = qualities,
            title = player.title?.decodeHtmlEntities()?.trim()?.ifBlank { null },
            thumbnailUrl = player.thumbnailUrl?.ifBlank { null },
            durationMillis = player.durationSeconds?.takeIf { it > 0 }?.times(1_000),
        )
    }

    private suspend fun filesOf(
        player: AyloPlayerDto,
        headers: Map<String, String>,
    ): List<ScrapedQualityDto>? {
        val endpoint = player.definitions
            .firstOrNull { it.format == Tube.FORMAT_FILE && !it.videoUrl.isNullOrBlank() }
            ?.videoUrl ?: return null
        val files = fetcher.getJson<List<AyloDefinitionDto>>(endpoint, headers).getOrNull().orEmpty()
        return qualitiesOf(files, Tube.FORMAT_FILE).ifEmpty { null }
    }

    /**
     * The best stream, and only the best. A master playlist is expanded into every height it lists
     * further down the pipeline, and that only happens to a quality that arrives on its own:
     * handing over all four masters would offer the same video four times, unexpanded.
     */
    private fun bestStreamOf(player: AyloPlayerDto): List<ScrapedQualityDto>? =
        qualitiesOf(player.definitions, Tube.FORMAT_STREAM).take(1).ifEmpty { null }

    private fun qualitiesOf(
        definitions: List<AyloDefinitionDto>,
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

    /**
     * The page, asked for more than once. Networks that filter these sites mostly do it by
     * resetting the connection rather than by answering, and a reset arrives at once and lands on
     * some attempts and not others - so the same request, repeated, usually gets through. Only
     * failures are retried; a page that answers is never fetched twice.
     */
    private suspend fun pageOf(url: String): String {
        var failure: Throwable? = null
        repeat(Tube.PAGE_ATTEMPTS) {
            val attempt = fetcher.getText(url, headersFor(url))
            attempt.getOrNull()?.let { return it }
            failure = attempt.exceptionOrNull()
        }
        throw failure ?: NoMediaException("No page at $url")
    }

    /** A browser's user agent, and the site's own front page as where the request came from. */
    private fun headersFor(url: String): Map<String, String> = buildMap {
        put(Network.HEADER_USER_AGENT, Tube.USER_AGENT)
        url.normalizedHost()?.let { put(Tube.HEADER_REFERER, "https://$it/") }
    }
}

@Serializable
internal data class AyloPlayerDto(
    @SerialName("video_title") val title: String? = null,
    @SerialName("video_duration") val durationSeconds: Long? = null,
    @SerialName("image_url") val thumbnailUrl: String? = null,
    @SerialName("mediaDefinitions") val definitions: List<AyloDefinitionDto> = emptyList(),
)

/**
 * One quality. The site's own `quality` field is deliberately not read: it is a string on a stream
 * and an empty array on the entry that points at the files, while the size says the same thing in
 * both - and says it the way the rest of the module names qualities.
 */
@Serializable
internal data class AyloDefinitionDto(
    val format: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoUrl: String? = null,
) {
    val resolution: String get() = "${width ?: height ?: 0}x${height ?: 0}"

    /** What the quality is named after: the short side, as it is for a stream's resolution. */
    val shortSide: Int get() = listOfNotNull(width, height).minOrNull() ?: 0
}
