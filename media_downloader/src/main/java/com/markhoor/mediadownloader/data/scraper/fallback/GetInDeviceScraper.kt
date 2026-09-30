package com.markhoor.mediadownloader.data.scraper.fallback

import com.markhoor.mediadownloader.core.Constants.GetInDevice
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.Serializable

/** A third-party resolver raced alongside several sites' own scrapers as a fallback. */
internal class GetInDeviceScraper(private val fetcher: HttpFetcher) : SiteScraper() {

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val answer = fetcher.postForm(GetInDevice.API_URL, form = mapOf("url" to url)).getOrThrow()
        val response = fetcher.json.decodeFromString<GetInDeviceResponseDto>(answer)
        val qualities = response.medias.orEmpty()
            .filter { it.quality != "audio" && !it.url.isNullOrBlank() }
            .map { media ->
                ScrapedQualityDto(
                    url = media.url.orEmpty(),
                    type = when {
                        media.videoAvailable == true -> MediaType.Video
                        media.audioAvailable == true -> MediaType.Audio
                        else -> MediaType.Image
                    },
                    label = media.quality,
                    sizeBytes = media.size,
                )
            }
        if (qualities.isEmpty()) return null
        return ScrapedMediaDto(
            qualities = qualities,
            title = response.title,
            thumbnailUrl = response.thumbnail,
            durationMillis = response.duration?.toLongOrNull()?.times(1_000),
        )
    }
}

@Serializable
internal data class GetInDeviceResponseDto(
    val title: String? = null,
    val thumbnail: String? = null,
    val duration: String? = null,
    val medias: List<GetInDeviceMediaDto>? = null,
)

@Serializable
internal data class GetInDeviceMediaDto(
    val url: String? = null,
    val quality: String? = null,
    val size: Long? = null,
    val videoAvailable: Boolean? = null,
    val audioAvailable: Boolean? = null,
)
