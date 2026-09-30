package com.markhoor.mediadownloader.data.scraper.dailymotion

import com.markhoor.mediadownloader.core.Constants.Dailymotion
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.isDailymotionMetadataLink
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Dailymotion video, through its player metadata: the video's name, cover and HLS master.
 * Dailymotion's CDN refuses the playlist without its own Referer and Origin, so they travel with
 * every quality.
 *
 * @param appPackage the host app's package name, which the metadata request identifies itself by.
 */
internal class DailymotionScraper(
    private val fetcher: HttpFetcher,
    private val appPackage: String,
) : SiteScraper() {

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val metadata = fetcher.getJson<DailymotionMetadataDto>(metadataUrlOf(url)).getOrThrow()
        val streamUrl = metadata.qualities?.auto?.firstOrNull()?.url ?: return null
        return ScrapedMediaDto(
            qualities = listOf(ScrapedQualityDto(streamUrl, MediaType.Video, QualityLabels.HD)),
            title = metadata.title?.ifBlank { null } ?: "DailyMotion",
            thumbnailUrl = metadata.thumbnails?.let { it.w360 ?: it.w480 ?: it.w720 ?: it.w1080 },
            durationMillis = metadata.durationSeconds?.times(1_000),
            headers = mapOf("Referer" to Dailymotion.REFERER, "Origin" to Dailymotion.ORIGIN),
        )
    }

    /** A playlist or fragment rides along on feed links (`/video/x8abc?playlist=...`); the id precedes it. */
    private fun metadataUrlOf(url: String): String {
        if (url.isDailymotionMetadataLink()) return url
        val videoId = url.substringAfter("/video/", "")
            .substringBefore("?").substringBefore("#").substringBefore("/")
        return Dailymotion.METADATA_URL + videoId + Dailymotion.METADATA_QUERY + appPackage
    }
}

@Serializable
internal data class DailymotionMetadataDto(
    val title: String? = null,
    @SerialName("duration") val durationSeconds: Long? = null,
    val qualities: DailymotionQualitiesDto? = null,
    val thumbnails: DailymotionThumbnailsDto? = null,
)

@Serializable
internal data class DailymotionQualitiesDto(val auto: List<DailymotionStreamDto>? = null)

@Serializable
internal data class DailymotionStreamDto(val url: String? = null)

@Serializable
internal data class DailymotionThumbnailsDto(
    @SerialName("360") val w360: String? = null,
    @SerialName("480") val w480: String? = null,
    @SerialName("720") val w720: String? = null,
    @SerialName("1080") val w1080: String? = null,
)
