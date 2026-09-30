package com.markhoor.mediadownloader.data.scraper.facebook

import android.util.Base64
import com.markhoor.mediadownloader.core.Constants.Facebook
import com.markhoor.mediadownloader.core.Constants.Instagram
import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.metaProperty
import com.markhoor.mediadownloader.data.network.CookieSource
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
 *
 * Read as a stranger first and only then as the reader, for the reason [FacebookVideoScraper]
 * gives: a public post comes back in the shape these parsers know, and the same page fetched with a
 * session comes back as the signed-in app instead. The session is the second question, asked only
 * where there is one and the first came back empty - which is what a post shared with friends, or
 * one inside a group the reader belongs to, looks like from outside. A share link to something
 * Facebook itself will not show - "this content isn't available at the moment" - still comes back
 * with nothing, because there is nothing to come back with.
 */
internal class FacebookShareScraper(
    private val fetcher: HttpFetcher,
    private val instagram: SiteScraper,
    private val cookies: CookieSource,
) : SiteScraper() {

    private val jsonString = """"((?:\\.|[^"\\])*)""""
    private val storyFbId = Regex(""""(?:storyFBID|story_fbid)":$jsonString""")
    private val storyId = Regex(""""storyID":$jsonString""")
    private val authorIdBesideStory =
        Regex(""""story_fbid":"[^"]*".*?"id":"(\d+)"""", RegexOption.DOT_MATCHES_ALL)
    private val crossPostedReel = Regex("""https:\\/\\/www\.instagram\.com\\/reel\\/([A-Za-z0-9_-]{1,20})\\/""")

    private fun headersFor(session: String?): Map<String, String> = buildMap {
        put("Accept", Network.ACCEPT_HTML)
        session?.let { put("Cookie", it) }
    }

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? =
        read(url, session = null) ?: sessionFor(url)?.let { read(url, session = it) }

    /** The browser's cookies for this site, or `null` when the reader has never signed in. */
    private fun sessionFor(url: String): String? = cookies.cookiesFor(url)?.takeIf { it.isNotBlank() }

    private suspend fun read(url: String, session: String?): ScrapedMediaDto? {
        val sharePage = fetcher.getText(url, headersFor(session)).getOrThrow()
        // Where the share link simply leads to the post, the page in hand is already the post's:
        // a group's post lives at /groups/<group>/posts/<story>/, which is not the address built
        // below, so rebuilding it asked Facebook for a page that does not exist and the reader was
        // told the media could not be found. Whatever is in front of us is read first.
        readMedia(sharePage)?.let { return it }
        // Both spellings appear, and the first may not be a number while a later one is.
        val story = storyFbId.findAll(sharePage).firstNotNullOfOrNull { it.groupValues[1].toLongOrNull() } ?: return null
        val author = authorIdOf(sharePage) ?: return null

        val postUrl = "${Facebook.PAGE_URL}$author/posts/$story"
        val postPage = fetcher.getText(postUrl, headersFor(session)).getOrThrow()

        return readMedia(postPage)
    }

    /** A page that holds the post: a cross-posted reel, a native video, or a picture. */
    private suspend fun readMedia(page: String): ScrapedMediaDto? {
        crossPostedReel.find(page)?.groupValues?.get(1)?.let { reelId ->
            return instagram.scrape("${Instagram.REEL_URL}$reelId/").getOrThrow()
        }
        return FacebookPageParser.parse(page) ?: photoPost(page)
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
