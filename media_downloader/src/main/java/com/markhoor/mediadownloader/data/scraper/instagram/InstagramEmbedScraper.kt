package com.markhoor.mediadownloader.data.scraper.instagram

import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.unescapeEmbeddedUrl
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType

/**
 * The reel's `/embed/` page, which names its clips' `video_url`s inside doubly escaped JSON. One
 * video per clip; the first is listed as HD and the second as SD.
 */
internal class InstagramEmbedScraper(private val fetcher: HttpFetcher) : SiteScraper() {

    private val clipMarker = "\"clips\\\""
    private val videoUrlStart = "\\\"video_url\\\":\\\""
    private val videoUrlEnd = "\\\",\\\""

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val page = fetcher.getText(InstagramPageParser.canonicalReelUrl(url, embed = true)).getOrThrow()
        val videoUrls = page.split(clipMarker)
            .map { clip -> clip.substringAfter(videoUrlStart, "").substringBefore(videoUrlEnd, "") }
            .map { it.unescapeEmbeddedUrl(passes = 2) }
            .filter { it.isHttpUrl() }
            .distinct()
        if (videoUrls.isEmpty()) return null

        return ScrapedMediaDto(
            qualities = videoUrls.mapIndexed { index, videoUrl ->
                ScrapedQualityDto(
                    url = videoUrl,
                    type = MediaType.Video,
                    label = if (index == 1) QualityLabels.SD else QualityLabels.HD,
                )
            },
            title = InstagramPageParser.ogCaption(page) ?: "Insta_${System.currentTimeMillis().toString().takeLast(5)}",
            thumbnailUrl = InstagramPageParser.ogCover(page),
        )
    }
}
