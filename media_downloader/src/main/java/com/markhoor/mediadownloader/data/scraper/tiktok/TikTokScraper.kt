package com.markhoor.mediadownloader.data.scraper.tiktok

import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.Constants.TikTok
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/**
 * A TikTok video, through the tikwm api, which gives its title, cover and SD, watermarked and HD
 * files. When the api has nothing, a video link still yields tikwm's direct play url for its id.
 */
internal class TikTokScraper(private val fetcher: HttpFetcher) : SiteScraper() {

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = fromApi(url) ?: directPlay(url)

    private suspend fun fromApi(url: String): ScrapedMediaDto? {
        val apiUrl = "${TikTok.TIKWM_API_URL}?url=${URLEncoder.encode(url, "UTF-8")}&hd=1"
        val video = fetcher.getJson<TikWmResponseDto>(apiUrl).getOrNull()?.data ?: return null
        val qualities = listOf(
            ScrapedQualityDto(video.play.orEmpty(), MediaType.Video, QualityLabels.SD, video.size),
            ScrapedQualityDto(video.watermarkedPlay.orEmpty(), MediaType.Video, QualityLabels.WATERMARK, video.watermarkedSize),
            ScrapedQualityDto(video.hdPlay.orEmpty(), MediaType.Video, QualityLabels.HD, video.hdSize),
        ).filter { it.url.isNotBlank() }
        if (qualities.isEmpty()) return null
        return ScrapedMediaDto(
            qualities = qualities,
            title = video.title,
            thumbnailUrl = video.dynamicCover ?: video.originCover,
            durationMillis = video.durationSeconds?.times(1_000),
        )
    }

    private fun directPlay(url: String): ScrapedMediaDto? {
        val videoId = url.substringAfter("video/", "").takeWhile { it.isDigit() }.ifEmpty { return null }
        return ScrapedMediaDto(
            qualities = listOf(
                ScrapedQualityDto("${TikTok.TIKWM_PLAY_URL}$videoId.mp4", MediaType.Video, QualityLabels.HD),
            ),
        )
    }
}

@Serializable
internal data class TikWmResponseDto(val data: TikWmVideoDto? = null)

@Serializable
internal data class TikWmVideoDto(
    val title: String? = null,
    val play: String? = null,
    val size: Long? = null,
    @SerialName("wmplay") val watermarkedPlay: String? = null,
    @SerialName("wm_size") val watermarkedSize: Long? = null,
    @SerialName("hdplay") val hdPlay: String? = null,
    @SerialName("hd_size") val hdSize: Long? = null,
    @SerialName("ai_dynamic_cover") val dynamicCover: String? = null,
    @SerialName("origin_cover") val originCover: String? = null,
    @SerialName("duration") val durationSeconds: Long? = null,
)
