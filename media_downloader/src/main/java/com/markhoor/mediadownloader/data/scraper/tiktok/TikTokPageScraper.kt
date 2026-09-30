package com.markhoor.mediadownloader.data.scraper.tiktok

import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.Constants.TikTok
import com.markhoor.mediadownloader.data.network.CookieSource
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The video as the signed-in reader sees it, read from TikTok's own page rather than from the api.
 *
 * The api is a stranger to the reader's account: a video shared with friends only, or left up by a
 * private account the reader follows, is invisible to it and the press came back with nothing. The
 * page carries the same video's files in a block of json, and asked for with the browser's own
 * cookies it carries them for whatever that account may see.
 *
 * Those files are handed over with the session that was given them. Measured against the site: the
 * play url answers `403` to a request with no cookies however it is dressed up, and `206` with the
 * cookies, a browser's name and the site as where it came from - so all three travel with the
 * media rather than being left behind at the page.
 */
internal class TikTokPageScraper(
    private val fetcher: HttpFetcher,
    private val cookies: CookieSource,
) : SiteScraper() {

    private val apiData = Regex(
        """<script id="api-data" type="application/json">(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val headers = headersFor(url)
        val page = fetcher.getText(url, headers).getOrThrow()
        val json = apiData.find(page)?.groupValues?.get(1) ?: return null
        val video = runCatching { fetcher.json.decodeFromString<TikTokPageDto>(json) }
            .getOrNull()?.detail?.itemInfo?.item ?: return null
        val file = video.video ?: return null
        val qualities = listOfNotNull(
            // The stream the player itself uses, which carries no watermark.
            file.playAddr?.ifBlank { null }
                ?.let { ScrapedQualityDto(it, MediaType.Video, QualityLabels.HD, file.playSize) },
            // What the site's own "save video" hands over, watermark and all; offered only when it
            // is a second file, since a page may name the same one twice.
            file.downloadAddr?.ifBlank { null }?.takeIf { it != file.playAddr }
                ?.let { ScrapedQualityDto(it, MediaType.Video, QualityLabels.WATERMARK) },
        ).ifEmpty { return null }
        return ScrapedMediaDto(
            qualities = qualities,
            title = video.description?.trim()?.ifBlank { null },
            thumbnailUrl = file.cover?.ifBlank { null },
            durationMillis = file.durationSeconds?.takeIf { it > 0 }?.times(1_000),
            headers = headers,
        )
    }

    private fun headersFor(url: String): Map<String, String> = buildMap {
        put(Network.HEADER_USER_AGENT, TikTok.USER_AGENT)
        put(Network.HEADER_REFERER, TikTok.SITE_URL)
        cookies.cookiesFor(url)?.takeIf { it.isNotBlank() }?.let { put(Network.HEADER_COOKIE, it) }
    }
}

@Serializable
internal data class TikTokPageDto(
    @SerialName("videoDetail") val detail: TikTokDetailDto? = null,
)

@Serializable
internal data class TikTokDetailDto(val itemInfo: TikTokItemInfoDto? = null)

@Serializable
internal data class TikTokItemInfoDto(@SerialName("itemStruct") val item: TikTokItemDto? = null)

@Serializable
internal data class TikTokItemDto(
    @SerialName("desc") val description: String? = null,
    val video: TikTokFileDto? = null,
)

/**
 * The files. `bitrateInfo` lists the same video at several rates, each with the size of its own
 * file; the one the player was given is the one taken, and the list is only read for how big it is.
 */
@Serializable
internal data class TikTokFileDto(
    @SerialName("duration") val durationSeconds: Long? = null,
    val cover: String? = null,
    val playAddr: String? = null,
    val downloadAddr: String? = null,
    val bitrateInfo: List<TikTokBitrateDto> = emptyList(),
) {
    val playSize: Long?
        get() = bitrateInfo.firstNotNullOfOrNull { rate ->
            rate.playAddr?.takeIf { it.urls.contains(playAddr) }?.size?.takeIf { it > 0 }
        }
}

@Serializable
internal data class TikTokBitrateDto(
    @SerialName("PlayAddr") val playAddr: TikTokPlayAddrDto? = null,
)

@Serializable
internal data class TikTokPlayAddrDto(
    @SerialName("DataSize") val size: Long? = null,
    @SerialName("UrlList") val urls: List<String> = emptyList(),
)
