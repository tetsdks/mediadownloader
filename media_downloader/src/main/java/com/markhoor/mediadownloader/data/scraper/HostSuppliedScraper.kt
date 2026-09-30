package com.markhoor.mediadownloader.data.scraper

import com.markhoor.mediadownloader.MediaSource
import com.markhoor.mediadownloader.core.isUnderAnyOf
import com.markhoor.mediadownloader.core.normalizedHost

/**
 * A reader the host app brought, wearing the same face as the module's own.
 *
 * Everything past this point treats it like any other scraper: it is raced, its answer is sized,
 * its streams are expanded, and its failures are failures. The host's [MediaSource] therefore never
 * has to know about any of that - it hands over what it found and stops.
 */
internal class HostSuppliedScraper(private val source: MediaSource) : SiteScraper() {

    /** Its hosts, normalised once: an app may write "www.YouTube.com/" and mean `youtube.com`. */
    val hosts: Set<String> = source.hosts.mapNotNull { it.normalizedHost() }.toSet()

    /**
     * Whether this reads [url] at all: its host, and then the source's own word on the link. Both
     * are needed - the host alone would make a site's feed look like one of its posts.
     */
    fun reads(url: String): Boolean = isOnItsHosts(url) && source.handles(url)

    /** Its hosts, without asking the source about the link; this is what "its site" means. */
    fun isOnItsHosts(url: String): Boolean =
        hosts.isNotEmpty() && url.normalizedHost()?.isUnderAnyOf(hosts) == true

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        if (!reads(url)) return null
        val media = source.read(url) ?: return null
        return ScrapedMediaDto(
            qualities = media.qualities.map { quality ->
                ScrapedQualityDto(
                    url = quality.url,
                    type = quality.type,
                    label = quality.label.ifBlank { null },
                    sizeBytes = quality.sizeBytes,
                    audioUrl = quality.audioUrl,
                )
            },
            title = media.title.ifBlank { null },
            thumbnailUrl = media.thumbnailUrl,
            durationMillis = media.durationMillis,
            // One set of headers for the download, as every other scraper hands over. The first
            // quality's are taken: a source that needs headers needs the same ones throughout.
            headers = media.qualities.firstOrNull()?.headers.orEmpty(),
        )
    }
}
