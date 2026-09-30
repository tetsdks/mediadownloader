package com.markhoor.mediadownloader.data.scraper.linkedin

import com.markhoor.mediadownloader.core.Constants.LinkedIn
import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.decodeHtmlEntities
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.isLinkedInPostLink
import com.markhoor.mediadownloader.core.isSiteOf
import com.markhoor.mediadownloader.core.titleFromHtml
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType

/**
 * A public LinkedIn post, read from LinkedIn's own page. The player's `data-sources` attribute
 * lists every encode (url, type, bitrate), largest first here; a post without a player is a
 * picture post, whose picture is its og:image.
 *
 * A `lnkd.in` short link is read the same way: the request follows it, and what comes back is the
 * page it was written for.
 */
internal class LinkedInScraper(private val fetcher: HttpFetcher) : SiteScraper() {

    private val dataSources = Regex("""data-sources="([^"]+)"""")
    private val poster = Regex("""data-poster-url="([^"]+)"""")
    private val source = Regex(""""src"\s*:\s*"([^"]+)"""")
    private val bitrate = Regex(""""data-bitrate"\s*:\s*(\d+)""")
    private val heightInUrl = Regex("""/mp4-(\d{3,4})p-""")
    private val ogImage = Regex("""property="og:image"\s+content="([^"]+)"""")
    private val ownAddress = Regex("""property="og:url"\s+content="([^"]+)"""")

    private val headers = mapOf(
        "User-Agent" to LinkedIn.USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to Network.ACCEPT_LANGUAGE,
    )

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val page = fetcher.getText(url, headers).getOrThrow().ifBlank { return null }
        if (url.isSiteOf("lnkd.in") && !leadsToAPost(page)) return null
        val title = page.titleFromHtml().ifBlank { null }

        val videos = videoQualities(page)
        if (videos.isNotEmpty()) {
            val cover = poster.find(page)?.groupValues?.get(1)?.decodeHtmlEntities()?.takeIf { it.isHttpUrl() }
            return ScrapedMediaDto(videos, title, cover)
        }
        val picture = ogImage.find(page)?.groupValues?.get(1)?.decodeHtmlEntities()?.takeIf { it.isHttpUrl() }
            ?: return null
        return ScrapedMediaDto(
            qualities = listOf(ScrapedQualityDto(picture, MediaType.Image, QualityLabels.IMAGE)),
            title = title,
            thumbnailUrl = picture,
        )
    }

    /**
     * Whether the page a short link led to is a post's. LinkedIn wraps every outside link a post
     * mentions in a `lnkd.in` of its own, so such a link lands as often on somebody else's site as
     * on a post - and that site has a cover picture too, which would otherwise be offered as this
     * post's media. The page says its own address, so it is asked.
     */
    private fun leadsToAPost(page: String): Boolean =
        ownAddress.find(page)?.groupValues?.get(1)?.decodeHtmlEntities()?.isLinkedInPostLink() == true

    /** Every encode, named by the height in its path (`/mp4-720p-...`) or else by its bitrate. */
    private fun videoQualities(page: String): List<ScrapedQualityDto> {
        val sources = dataSources.find(page)?.groupValues?.get(1)?.decodeHtmlEntities() ?: return emptyList()
        // One object per encode. Split rather than parsed: JSON inside an html attribute.
        return sources.split("},{")
            .mapNotNull { entry ->
                val src = source.find(entry)?.groupValues?.get(1)?.replace("\\/", "/")?.takeIf { it.isHttpUrl() }
                    ?: return@mapNotNull null
                Encode(
                    url = src,
                    height = heightInUrl.find(src)?.groupValues?.get(1)?.toIntOrNull(),
                    bitrate = bitrate.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                )
            }
            .distinctBy { it.url }
            .sortedByDescending { it.height ?: it.bitrate }
            .map { encode ->
                ScrapedQualityDto(
                    url = encode.url,
                    type = MediaType.Video,
                    label = encode.height?.let { "${it}p" } ?: "${encode.bitrate / 1000} kbps",
                )
            }
    }

    private data class Encode(val url: String, val height: Int?, val bitrate: Int)
}
