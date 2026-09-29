package com.markhoor.mediadownloader.data.scraper.tube

import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.Tube
import com.markhoor.mediadownloader.core.decodeHtmlEntities
import com.markhoor.mediadownloader.core.isHlsPlaylistUrl
import com.markhoor.mediadownloader.core.isVideoFileUrl
import com.markhoor.mediadownloader.core.normalizedHost
import com.markhoor.mediadownloader.core.qualityNameFromResolution
import com.markhoor.mediadownloader.core.titleFromHtml
import com.markhoor.mediadownloader.core.urlPath
import com.markhoor.mediadownloader.data.network.CookieSource
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.NoMediaException
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    private val cookies: CookieSource,
    private val isRestrictedSite: (String) -> Boolean,
) : SiteScraper() {

    private val ID_IN_NAME = Regex("""\d{4,}""")

    /**
     * Whether this is a page worth reading: the right kind of site, and a video's address on it.
     *
     * The sites agree on little, so an address is judged three ways: a path these sites file videos
     * under, the query one network names the video in, or a last segment that names one thing -
     * ending in `.html`, or carrying an id. A feed, a category or a profile is none of those, and
     * claiming one would have the browser treat a listing as a single video's page.
     */
    fun reads(url: String): Boolean {
        val host = url.normalizedHost() ?: return false
        if (!isRestrictedSite(host)) return false
        val path = url.urlPath().orEmpty()
        if (Tube.VIDEO_PATH_MARKERS.any { path.contains(it) }) return true
        if (url.contains(Tube.VIDEO_QUERY_MARKER)) return true
        return namesOneThing(path)
    }

    /** A last segment that names one piece of media: `128032-a_title.html`, `a-title-12345`. */
    private fun namesOneThing(path: String): Boolean {
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return false
        val last = segments.last()
        return last.endsWith(Tube.PAGE_SUFFIX, ignoreCase = true) || ID_IN_NAME.containsMatchIn(last)
    }

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val page = pageOf(url)
        val headers = headersFor(url)
        val media = aylo(page, headers)
            ?: TubePlayerParser.fromPlayerCalls(page)
            ?: TubePlayerParser.fromLinkedData(page)
            ?: TubePlayerParser.fromKvsPlayer(page, url)
            ?: TubePlayerParser.fromNamedFile(page, url)
            ?: TubePlayerParser.fromPreloadedStream(page, url)
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
        // A mirror's object is the page's whole player configuration, which is not always valid
        // JSON; when it cannot be read the page is left to the shapes that come after it.
        val player = TubePlayerParser.playerObjectOf(page)
            ?.let { runCatching { fetcher.json.decodeFromString<AyloPlayerDto>(it) }.getOrNull() }
            ?: return null
        val definitions = withEndpointsFollowed(player.definitions, headers)
        val qualities = qualitiesOf(definitions, Tube.FORMAT_FILE)
            .ifEmpty { bestStreamOf(definitions) }
            .ifEmpty { return null }
        return ScrapedMediaDto(
            qualities = qualities,
            title = player.title?.decodeHtmlEntities()?.trim()?.ifBlank { null },
            thumbnailUrl = player.thumbnailUrl?.ifBlank { null },
            durationMillis = player.durationSeconds?.takeIf { it > 0 }?.times(1_000),
        )
    }

    /**
     * The definitions, with the ones that name no file followed. The network lists some qualities
     * outright and hands the rest over behind a signed endpoint that answers with the same list -
     * one entry on the main sites, every entry on the mirrors. An entry is told from an endpoint by
     * its url: a file or a playlist names itself, an endpoint is a query.
     *
     * The files those endpoints give are preferred to the streams listed beside them: one request
     * per download, a size that can be measured before it starts, and no remux afterwards. They are
     * signed for the address that asked, so they are read now rather than kept for later.
     */
    private suspend fun withEndpointsFollowed(
        definitions: List<AyloDefinitionDto>,
        headers: Map<String, String>,
    ): List<AyloDefinitionDto> {
        val (named, endpoints) = definitions
            .filter { !it.videoUrl.isNullOrBlank() }
            .partition { it.videoUrl.orEmpty().let { url -> url.isHlsPlaylistUrl() || url.isVideoFileUrl() } }
        val answered = endpoints.flatMap { endpoint ->
            fetcher.getJson<List<AyloDefinitionDto>>(endpoint.videoUrl.orEmpty(), headers)
                .getOrNull().orEmpty()
        }
        return answered + named
    }

    /**
     * The best stream, and only the best. A master playlist is expanded into every height it lists
     * further down the pipeline, and that only happens to a quality that arrives on its own:
     * handing over all four masters would offer the same video four times, unexpanded.
     */
    private fun bestStreamOf(definitions: List<AyloDefinitionDto>): List<ScrapedQualityDto> =
        qualitiesOf(definitions, Tube.FORMAT_STREAM).take(1)

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

    /**
     * A browser's user agent, the site's own front page as where the request came from, and the
     * cookies the browser holds for it. KVS hands its files only to a visitor whose player has
     * loaded, and a site the reader has been browsed to has a session of its own besides - the
     * page reads without either, the file often does not.
     */
    private fun headersFor(url: String): Map<String, String> = buildMap {
        put(Network.HEADER_USER_AGENT, Tube.USER_AGENT)
        val held = cookies.cookiesFor(url)?.takeIf { it.isNotBlank() }
        put(Network.HEADER_COOKIE, listOfNotNull(Tube.PLAYER_COOKIE, held).joinToString("; "))
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
 * One quality. The size is given as a width and a height on the main sites and as a bare `quality`
 * on the mirrors and behind the endpoints - a string there, and an empty array on the entry that
 * points at the endpoint itself, which is why it is read as whatever the site sent.
 */
@Serializable
internal data class AyloDefinitionDto(
    val format: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoUrl: String? = null,
    val quality: JsonElement? = null,
) {
    /** What the quality is named after: the short side, as it is for a stream's resolution. */
    val shortSide: Int
        get() = listOfNotNull(width, height).minOrNull()
            ?: (quality as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: 0

    val resolution: String get() = "${shortSide}x$shortSide"
}
