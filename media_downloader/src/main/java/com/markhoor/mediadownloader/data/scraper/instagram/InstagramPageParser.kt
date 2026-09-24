package com.markhoor.mediadownloader.data.scraper.instagram

import com.markhoor.mediadownloader.core.Constants.Instagram
import com.markhoor.mediadownloader.core.Constants.Json
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.decodeHtmlEntities
import com.markhoor.mediadownloader.core.decodeJsonEscapes
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.isThreadsMediaPage
import com.markhoor.mediadownloader.core.metaProperty
import com.markhoor.mediadownloader.core.unescapeEmbeddedUrl
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.domain.models.MediaType

/**
 * Reads Instagram and Threads pages, and builds the urls the Instagram scrapers ask for. Pure.
 *
 * A signed-in page embeds the post as JSON; a signed-out one keeps only its og tags, so every
 * reader falls back to those for the caption and the cover.
 */
internal object InstagramPageParser {

    private val webInfoItems = Regex(
        """"xdt_api__v1__media__shortcode__web_info":\s*\{[^}]*"items":\s*\[(\{.+?\})\]""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val videoFileUrl = Regex(""""url":\s*"([^"]*\.mp4[^"]*)"""")
    private val firstVideoVersion = Regex(""""video_versions":\s*\[\s*\{[^}]*"url":\s*${Json.STRING_VALUE}""")
    private val firstImageCandidate =
        Regex(""""image_versions2":\s*\{[^}]*"candidates":\s*\[\s*\{[^}]*"url":\s*${Json.STRING_VALUE}""")
    private val coverPatterns = listOf(
        firstImageCandidate,
        Regex(""""display_url":\s*${Json.STRING_VALUE}"""),
        Regex(""""thumbnail_url":\s*${Json.STRING_VALUE}"""),
        Regex("""<meta\s+property="og:image"\s+content="([^"]+)""""),
    )

    private val captionInJson = listOf(
        Regex(""""caption":\s*\{[^}]*"text":\s*${Json.STRING_VALUE}"""),
        Regex(""""accessibility_caption":\s*${Json.STRING_VALUE}"""),
        Regex(""""edge_media_to_caption":\s*\{[^}]*"text":\s*${Json.STRING_VALUE}"""),
    )
    private val looseTextInJson = Regex(""""text":\s*${Json.STRING_VALUE}""")
    private val captionInMetaTags = listOf(
        """<meta property="og:title" content="([^"]+)"""",
        """<meta property="og:description" content="([^"]+)"""",
        """<meta name="description" content="([^"]+)"""",
        """<meta name="twitter:title" content="([^"]+)"""",
        """<meta name="twitter:description" content="([^"]+)"""",
    ).map(::Regex)

    /** Labels that name the account or the site, not the post. */
    private val boilerplate = listOf("Instagram", " on Instagram:", " on Threads", "Followers", "Following")

    private val reelUrl = Regex("""https://www\.instagram\.com/reels?/[^/?]+""")
    private val shortcodeMarkers = listOf("/reel/", "/reels/", "/p/", "/tv/")

    // region Signed-in page

    /**
     * The post on a signed-in page: from the embedded web-info JSON when present, otherwise from
     * the first video or picture the page names. `null` when it names neither.
     */
    fun parseSignedInPage(html: String): ScrapedMediaDto? {
        val media = webInfoItems.find(html)?.groupValues?.get(1)?.let { mediaInItems(it, html) }
            ?: firstVideo(html)
            ?: firstImage(html)
            ?: return null
        return media.copy(title = media.title ?: captionIn(html))
    }

    private fun mediaInItems(itemsJson: String, html: String): ScrapedMediaDto? {
        val isVideo = itemsJson.contains("video_versions")
        val mediaUrl = (if (isVideo) videoFileUrl else firstImageCandidate)
            .find(itemsJson)?.groupValues?.get(1)?.let(::cleanUrl)?.takeIf { it.isHttpUrl() }
            ?: return null
        val cover = if (isVideo) {
            firstImageCandidate.find(itemsJson)?.groupValues?.get(1)?.let(::cleanUrl) ?: coverIn(html)
        } else {
            mediaUrl
        }
        return single(mediaUrl, if (isVideo) MediaType.Video else MediaType.Image, cover, captionInItems(itemsJson))
    }

    private fun firstVideo(html: String): ScrapedMediaDto? {
        val videoUrl = firstVideoVersion.find(html)?.groupValues?.get(1)?.let(::cleanUrl)?.takeIf { it.isHttpUrl() }
            ?: return null
        val cover = coverIn(html) ?: firstImageCandidate.find(html)?.groupValues?.get(1)?.let(::cleanUrl)
        return single(videoUrl, MediaType.Video, cover, title = null)
    }

    private fun firstImage(html: String): ScrapedMediaDto? {
        val imageUrl = firstImageCandidate.find(html)?.groupValues?.get(1)?.let(::cleanUrl)?.takeIf { it.isHttpUrl() }
            ?: return null
        return single(imageUrl, MediaType.Image, cover = imageUrl, title = null)
    }

    private fun coverIn(html: String): String? = coverPatterns.firstNotNullOfOrNull { pattern ->
        pattern.find(html)?.groupValues?.get(1)?.let(::cleanUrl)?.takeIf { it.contains("http") }
    }

    private fun captionInItems(itemsJson: String): String? {
        captionInJson.forEachIndexed { index, pattern ->
            val caption = pattern.find(itemsJson)?.groupValues?.get(1)?.let(::decodeText)
            // accessibility_caption is often a machine description; only a long one is kept.
            val minLength = if (index == 1) Instagram.MIN_LOOSE_CAPTION_LENGTH + 1 else 1
            if (caption != null && caption.length >= minLength) return caption
        }
        return looseTextInJson.findAll(itemsJson)
            .mapNotNull { decodeText(it.groupValues[1]) }
            .firstOrNull { text ->
                text.length >= Instagram.MIN_LOOSE_CAPTION_LENGTH &&
                    !text.contains("Photo by") && !text.contains("Video by") && !text.startsWith("@")
            }
    }

    /** The caption from the page's JSON or meta tags, skipping labels that name the account. */
    private fun captionIn(html: String): String? =
        (captionInJson + captionInMetaTags).firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.get(1)?.let(::decodeText)?.takeIf { caption ->
                caption.length > 10 && !caption.startsWith("@") && boilerplate.none { caption.contains(it) }
            }
        }

    // endregion

    // region Signed-out page

    /**
     * The caption from a signed-out page's og tags, without Instagram's boilerplate around it:
     * `"<counts> - <author> on <date>: "<caption>"`.
     */
    fun ogCaption(html: String): String? {
        val raw = html.metaProperty("og:description") ?: html.metaProperty("og:title") ?: return null
        val quoted = raw.substringAfter(": \"", "").substringBeforeLast("\"", "")
        return quoted.ifBlank { raw }.trim().takeIf { it.isNotBlank() }
    }

    fun ogCover(html: String): String? = html.metaProperty("og:image")?.takeIf { it.startsWith("http") }

    // endregion

    // region Urls

    /** The post's shortcode, or the url itself for a Threads media page. */
    fun shortcodeOf(url: String): String {
        if (url.isThreadsMediaPage()) return url
        val marker = shortcodeMarkers.firstOrNull { url.contains(it) } ?: return url.trim()
        return url.substringAfter(marker).substringBefore("/").trim()
    }

    /** The post's id as the GraphQL api names it: the segment after `reel/`, `reels/` or `p/`. */
    fun postIdOf(url: String): String = when {
        url.contains("reel/") -> url.substringAfter("reel/").substringBefore("/")
        url.contains("reels/") -> url.substringAfter("reels/").substringBefore("/")
        url.contains("p/") -> url.substringAfter("p/").substringBefore("/")
        else -> url
    }

    /** The post as a `/reel/` url, optionally its `/embed/` page. */
    fun canonicalReelUrl(url: String, embed: Boolean): String {
        val asReel = url.replace("/p/", "/reel/").replace("reels", "reel")
        val base = reelUrl.find(asReel)?.value ?: asReel
        return if (embed) "$base/embed/" else base
    }

    // endregion

    private fun single(url: String, type: MediaType, cover: String?, title: String?) = ScrapedMediaDto(
        qualities = listOf(ScrapedQualityDto(url, type, QualityLabels.HD)),
        title = title,
        thumbnailUrl = cover,
    )

    /** Pages embed JSON in JSON, so urls are unescaped twice. */
    private fun cleanUrl(raw: String): String = raw.unescapeEmbeddedUrl(passes = 2)

    private fun decodeText(raw: String): String? =
        raw.decodeJsonEscapes(passes = 2).decodeHtmlEntities().takeIf { it.isNotBlank() }
}
