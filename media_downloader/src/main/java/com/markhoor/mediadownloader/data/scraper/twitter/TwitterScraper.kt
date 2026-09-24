package com.markhoor.mediadownloader.data.scraper.twitter

import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.Constants.Twitter
import com.markhoor.mediadownloader.core.qualityNameFromResolution
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An X/Twitter status, through the tweeload api: every video's encodes and every picture.
 *
 * @param apiKey tweeload's key, supplied by the host app from its own configuration.
 */
internal class TwitterScraper(
    private val fetcher: HttpFetcher,
    private val apiKey: String,
) : SiteScraper() {

    /** Each encode names its resolution as a path segment: `.../vid/avc1/720x1280/...`. */
    private val resolutionInUrl = Regex("""/(\d{2,5}x\d{2,5})/""")

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val statusId = url.substringAfter("status/", "").takeWhile { it.isDigit() }.ifEmpty { return null }
        val tweet = fetcher.getJson<TweeloadResponseDto>(
            url = "${Twitter.TWEELOAD_URL}$statusId.json",
            headers = mapOf("Authorization" to apiKey),
        ).getOrThrow().tweet ?: return null

        val videos = tweet.media?.videos.orEmpty()
        val videoQualities = videos.flatMapIndexed { index, video ->
            video.urls.orEmpty().mapNotNull { it.url }.map { videoUrl ->
                val label = resolutionInUrl.find(videoUrl)?.groupValues?.get(1)?.qualityNameFromResolution()
                    ?: QualityLabels.HD
                ScrapedQualityDto(
                    url = videoUrl,
                    type = MediaType.Video,
                    label = if (videos.size > 1) "$label - (Video ${index + 1})" else label,
                )
            }
        }
        val images = tweet.media?.images.orEmpty().mapNotNull { it.url }
        val imageQualities = images.map { ScrapedQualityDto(it, MediaType.Image, QualityLabels.IMAGE) }

        return ScrapedMediaDto(
            qualities = videoQualities + imageQualities,
            title = tweet.text,
            thumbnailUrl = videos.firstNotNullOfOrNull { it.thumbnailUrl } ?: images.firstOrNull(),
        )
    }
}

@Serializable
internal data class TweeloadResponseDto(val tweet: TweetDto? = null)

@Serializable
internal data class TweetDto(
    val text: String? = null,
    val media: TweetMediaDto? = null,
)

@Serializable
internal data class TweetMediaDto(
    val videos: List<TweetVideoDto>? = null,
    val images: List<TweetImageDto>? = null,
)

@Serializable
internal data class TweetVideoDto(
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("video_urls") val urls: List<TweetVideoUrlDto>? = null,
)

@Serializable
internal data class TweetVideoUrlDto(val url: String? = null)

@Serializable
internal data class TweetImageDto(@SerialName("image_url") val url: String? = null)
