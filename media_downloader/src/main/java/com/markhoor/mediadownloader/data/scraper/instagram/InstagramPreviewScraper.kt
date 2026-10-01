package com.markhoor.mediadownloader.data.scraper.instagram

import com.markhoor.mediadownloader.core.Constants.Instagram
import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.decodeJsonEscapes
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.isImageFileUrl
import com.markhoor.mediadownloader.core.isThreadsPostLink
import com.markhoor.mediadownloader.core.unescapeEmbeddedUrl
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType

/**
 * The reel's `/reels/` page as a mobile browser receives it, which still embeds the post's
 * `video_versions` and `image_versions2` JSON without a sign-in. A Threads post's own page is the
 * same read, and the same JSON.
 */
internal class InstagramPreviewScraper(private val fetcher: HttpFetcher) : SiteScraper() {

    private val headers = mapOf(
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Dest" to "document",
        "x-requested-with" to "com.android.browser",
        "Upgrade-Insecure-Requests" to "1",
        "Accept" to Network.ACCEPT_HTML,
        "User-Agent" to Instagram.MOBILE_USER_AGENT,
        "Cookie" to Instagram.PREVIEW_COOKIE,
    )

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val reelUrl = InstagramPageParser.canonicalReelUrl(url, embed = false)
        val pageUrl = if (reelUrl.contains("reel/")) reelUrl.replace("reel", "reels") else reelUrl
        val page = fetcher.getText(pageUrl, headers).getOrThrow()

        val videoUrl = page.substringAfter("video_versions\":", "")
            .substringBefore("}],\"", "")
            .substringAfter("\"url\":\"", "")
            .substringBefore("\"", "")
            .unescapeEmbeddedUrl(passes = 2)
            .takeIf { it.isHttpUrl() }
            ?: return photographOnThreads(pageUrl, page)
        val cover = page.substringAfterLast("\"image_versions2\":", "")
            .substringBefore("},\"", "")
            .substringAfter("\"url\":\"", "")
            .substringBefore("\",\"", "")
            .unescapeEmbeddedUrl(passes = 2)
        val caption = page.substringAfter("caption\":{\"text\":\"", "")
            .substringBefore("\",", "")
            .decodeJsonEscapes()

        return ScrapedMediaDto(
            qualities = listOf(
                ScrapedQualityDto(
                    url = videoUrl,
                    type = if (videoUrl.isImageFileUrl()) MediaType.Image else MediaType.Video,
                    label = QualityLabels.HD,
                ),
            ),
            title = caption.ifBlank { InstagramPageParser.ogCaption(page) },
            thumbnailUrl = cover.takeIf { it.isHttpUrl() } ?: InstagramPageParser.ogCover(page),
        )
    }

    /**
     * A Threads post that is a photograph rather than a video. The page says so itself -
     * `"video_versions":null,"media_type":1` - and carries the picture as the first
     * `image_versions2` candidate, which is the file its og:image points at; nothing else here
     * reads a post without a video, so a photograph on Threads was turned away as media that could
     * not be found. Only asked of a Threads post: on an Instagram reel the same candidates are the
     * video's cover, and a cover must never be offered as the media.
     */
    private fun photographOnThreads(pageUrl: String, page: String): ScrapedMediaDto? =
        if (pageUrl.isThreadsPostLink()) InstagramPageParser.parseSignedInPage(page) else null
}
