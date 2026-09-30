package com.markhoor.mediadownloader.data.scraper.instagram

import com.markhoor.mediadownloader.core.Constants.Instagram
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.array
import com.markhoor.mediadownloader.core.obj
import com.markhoor.mediadownloader.core.text
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Instagram's public GraphQL query for one post. Answers for posts (`/p/`) as well as reels, and
 * lists every item of a carousel as its own quality.
 */
internal class InstagramGraphQlScraper(private val fetcher: HttpFetcher) : SiteScraper() {

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val postId = InstagramPageParser.postIdOf(url)
        val answer = fetcher.postForm(
            url = Instagram.GRAPHQL_URL,
            form = mapOf(
                "doc_id" to Instagram.GRAPHQL_DOC_ID,
                "variables" to buildJsonObject { put("shortcode", postId) }.toString(),
            ),
            headers = mapOf(
                "Cookie" to Instagram.GRAPHQL_COOKIE,
                "Referer" to "${Instagram.POST_URL}$postId/",
                "User-Agent" to Instagram.MOBILE_USER_AGENT,
            ),
        ).getOrThrow()
        val media = fetcher.json.parseToJsonElement(answer).obj("data").obj("xdt_shortcode_media") ?: return null

        return ScrapedMediaDto(
            qualities = carouselItems(media) ?: singleItem(media),
            title = captionOf(media),
            thumbnailUrl = media.text("thumbnail_src"),
        )
    }

    private fun carouselItems(media: JsonObject): List<ScrapedQualityDto>? =
        media.obj("edge_sidecar_to_children").array("edges")?.mapIndexedNotNull { index, edge ->
            val node = edge.obj("node") ?: return@mapIndexedNotNull null
            val videoUrl = node.text("video_url")?.takeIf { it.isNotBlank() }
            val url = videoUrl ?: node.text("display_url") ?: return@mapIndexedNotNull null
            ScrapedQualityDto(
                url = url,
                type = if (videoUrl != null) MediaType.Video else MediaType.Image,
                label = "${QualityLabels.MEDIA}_${index + 1}",
            )
        }

    private fun singleItem(media: JsonObject): List<ScrapedQualityDto> {
        media.text("video_url")?.let { return listOf(ScrapedQualityDto(it, MediaType.Video, QualityLabels.HD)) }
        val displayUrl = media.text("display_url") ?: return emptyList()
        val resources = media.array("display_resources").orEmpty().mapNotNull { resource ->
            val source = resource.text("src") ?: return@mapNotNull null
            ScrapedQualityDto(source, MediaType.Image, label = resource.text("config_width") ?: QualityLabels.HD)
        }
        return resources.ifEmpty { listOf(ScrapedQualityDto(displayUrl, MediaType.Image, QualityLabels.IMAGE)) }
    }

    private fun captionOf(media: JsonObject): String? =
        media.text("accessibility_caption")?.takeIf { it != "null" }
            ?: media.obj("edge_media_to_caption").array("edges")?.firstOrNull().obj("node").text("text")
}
