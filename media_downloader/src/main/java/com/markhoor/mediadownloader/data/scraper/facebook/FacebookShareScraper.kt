package com.markhoor.mediadownloader.data.scraper.facebook

import android.util.Base64
import com.markhoor.mediadownloader.core.Constants.Facebook
import com.markhoor.mediadownloader.core.Constants.Instagram
import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.metaProperty
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType

/**
 * A Facebook `share/...` link that is not a video link.
 *
 * The share page names the post only indirectly - the author's id and the story id - so the post's
 * own page is read next. That page is one of three things: a cross-posted Instagram reel (handed
 * to [instagram]), a native video post (read like any video page), or a photo post, whose picture
 * is its og:image.
 */
internal class FacebookShareScraper(
    private val fetcher: HttpFetcher,
    private val instagram: SiteScraper,
) : SiteScraper() {

    private val jsonString = """"((?:\\.|[^"\\])*)""""
    private val storyFbId = Regex(""""(?:storyFBID|story_fbid)":$jsonString""")
    private val storyId = Regex(""""storyID":$jsonString""")
    private val authorIdBesideStory =
        Regex(""""story_fbid":"[^"]*".*?"id":"(\d+)"""", RegexOption.DOT_MATCHES_ALL)
    private val crossPostedReel = Regex("""https:\\/\\/www\.instagram\.com\\/reel\\/([A-Za-z0-9_-]{1,20})\\/""")

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val sharePage = fetcher.getText(url).getOrThrow()
        // Both spellings appear, and the first may not be a number while a later one is.
        val story = storyFbId.findAll(sharePage).firstNotNullOfOrNull { it.groupValues[1].toLongOrNull() } ?: return null
        val author = authorIdOf(sharePage) ?: return null

        val postPage = fetcher.getText(
            url = "${Facebook.PAGE_URL}$author/posts/$story",
            headers = mapOf("Accept" to Network.ACCEPT_HTML),
        ).getOrThrow()

        crossPostedReel.find(postPage)?.groupValues?.get(1)?.let { reelId ->
            return instagram.scrape("${Instagram.REEL_URL}$reelId/").getOrThrow()
        }
        return FacebookPageParser.parse(postPage) ?: photoPost(postPage)
    }

    /** The author's id: from the base64 story id (`S:_I<author>:...`), or the id beside the story. */
    private fun authorIdOf(sharePage: String): Long? {
        val fromStoryId = storyId.find(sharePage)?.groupValues?.get(1)?.let { encoded ->
            runCatching { String(Base64.decode(encoded, Base64.DEFAULT)) }.getOrNull()
                ?.substringAfter("_I", "")?.substringBefore(":")?.toLongOrNull()
        }
        return fromStoryId ?: authorIdBesideStory.find(sharePage)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun photoPost(postPage: String): ScrapedMediaDto? {
        val picture = postPage.metaProperty("og:image")?.takeIf { it.startsWith("http") } ?: return null
        return ScrapedMediaDto(
            qualities = listOf(ScrapedQualityDto(picture, MediaType.Image, QualityLabels.HD)),
            title = postPage.metaProperty("og:title") ?: postPage.metaProperty("og:image:alt"),
            thumbnailUrl = picture,
        )
    }
}
