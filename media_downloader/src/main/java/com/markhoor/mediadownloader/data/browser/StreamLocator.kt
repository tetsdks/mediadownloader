package com.markhoor.mediadownloader.data.browser

import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.isAdvertMediaUrl
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.isSamePageAs
import com.markhoor.mediadownloader.core.isSiteOf
import com.markhoor.mediadownloader.core.namesMediaFile
import com.markhoor.mediadownloader.core.unescapeEmbeddedUrl
import com.markhoor.mediadownloader.core.urlPath
import com.markhoor.mediadownloader.data.network.HttpFetcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Finds the stream behind what a page let slip: the playlist beside a segment, the stream a
 * Brightcove player was told to play, the file a card's page names, Bitchute's media. Each answer
 * is `null` when there is none; nothing here throws but cancellation.
 *
 * @param playlistProbesAtOnce how many candidate playlists are asked for at once; fewer on a
 *   low-end device, which costs a round trip or two but keeps the device responsive.
 */
internal class StreamLocator(
    private val fetcher: HttpFetcher,
    private val playlistProbesAtOnce: Int,
    /** Whether a found media url's host is blocked; see `CheckSiteAccessUseCase.blocksHostOf`. */
    private val blocksHostOf: (String) -> Boolean,
) {

    private val metaVideo = listOf(
        Regex("""<meta[^>]+property=["']og:video(?::secure_url|:url)?["'][^>]+content=["']([^"']+)["']"""),
        Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:video(?::secure_url|:url)?["']"""),
        Regex("""<meta[^>]+name=["']twitter:player:stream["'][^>]+content=["']([^"']+)["']"""),
        Regex(""""contentUrl"\s*:\s*"([^"]+)""""),
        Regex("""<source[^>]+src=["']([^"']+\.(?:mp4|m3u8|webm)[^"']*)["']"""),
    )
    private val jsonSrc = Regex(""""src"\s*:\s*"(https:[^"]+)"""")
    private val bitchuteMediaUrl = Regex(""""media_url"\s*:\s*"([^"]+)"""")

    /**
     * The playlist a segment belongs to, when the page read it before anything was listening. It is
     * the segment's neighbour, in its folder or up to two above, under one of the names CDNs use,
     * signed with the segment's query or [altQuery] (the query the page spent on a playlist).
     * Nearest folder first.
     */
    suspend fun playlistForSegment(segmentUrl: String, headers: Map<String, String>, altQuery: String): String? {
        val queries = listOf(segmentUrl.substringAfter('?', ""), altQuery).filter { it.isNotBlank() }.distinct()
            .ifEmpty { listOf("") }
        val candidates = buildList {
            var folder = segmentUrl.substringBefore('?').substringBeforeLast('/')
            repeat(3) {
                if (folder.count { it == '/' } < 3) return@repeat
                Browser.PLAYLIST_NAMES.forEach { name ->
                    queries.forEach { query -> add(if (query.isBlank()) "$folder/$name" else "$folder/$name?$query") }
                }
                folder = folder.substringBeforeLast('/')
            }
        }
        val permits = Semaphore(playlistProbesAtOnce)
        val answering = coroutineScope {
            candidates.map { candidate ->
                async {
                    permits.withPermit {
                        candidate.takeIf { isPlaylist(fetcher.getText(it, headers, maxBytes = Browser.PAGE_READ_MAX_BYTES).getOrNull()) }
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }
        return candidates.firstOrNull { it in answering }
    }

    /**
     * The stream a Brightcove player was given, asked for again with the player's own headers. Its
     * answer is the only place every rendition is named; the adaptive master is preferred.
     */
    suspend fun brightcoveStream(apiUrl: String, headers: Map<String, String>): String? {
        val body = fetcher.getText(apiUrl, headers, maxBytes = Browser.PAGE_READ_MAX_BYTES).getOrNull() ?: return null
        val sources = jsonSrc.findAll(body).map { it.groupValues[1].replace("\\/", "/") }.toList()
        return sources.firstOrNull { it.contains(".m3u8") && !it.contains("/clear/") }
            ?: sources.firstOrNull { it.contains(".m3u8") }
            ?: sources.firstOrNull { it.contains(".mp4") }
    }

    /**
     * The media a card's page names in its head, read without opening it - so a tap on a feed stays
     * on the feed. `null` when the page names nothing but itself, an embed page or an advert.
     */
    suspend fun mediaOnPage(pageUrl: String, headers: Map<String, String>): String? {
        val html = fetcher.getText(pageUrl, headers, maxBytes = Browser.PAGE_READ_MAX_BYTES).getOrNull() ?: return null
        return metaVideo.asSequence()
            .mapNotNull { it.find(html)?.groupValues?.getOrNull(1)?.unescapeEmbeddedUrl() }
            .firstOrNull { found ->
                found.isHttpUrl() && !found.isAdvertMediaUrl() && !blocksHostOf(found) &&
                    found.namesMediaFile() && !found.isSamePageAs(pageUrl)
            }
    }

    /** Bitchute's video id, from a video page's url. */
    fun bitchuteVideoId(pageUrl: String): String? {
        if (!pageUrl.isSiteOf("bitchute.com")) return null
        val path = pageUrl.urlPath() ?: return null
        if (!path.startsWith("/video/")) return null
        return path.removePrefix("/video/").trim('/').takeIf { it.isNotBlank() && '/' !in it }
    }

    /** Bitchute's page names no file; its api, asked the way its player asks, does. */
    suspend fun bitchuteMedia(videoId: String): String? {
        val request = buildJsonObject { put("video_id", videoId) }.toString()
        val body = fetcher.postJson(
            url = Browser.BITCHUTE_MEDIA_API,
            body = request,
            headers = mapOf("Referer" to Browser.BITCHUTE_REFERER, "Origin" to Browser.BITCHUTE_ORIGIN),
        ).getOrNull() ?: return null
        return bitchuteMediaUrl.find(body)?.groupValues?.getOrNull(1)?.replace("\\/", "/")?.takeIf { it.isHttpUrl() }
    }

    /**
     * Whether a playlist carries subtitles rather than video. Brightcove names both
     * `rendition.m3u8`, so only the first piece's ending tells them apart.
     */
    suspend fun isSubtitlePlaylist(playlistUrl: String, headers: Map<String, String>): Boolean {
        val text = fetcher.getText(playlistUrl, headers, maxBytes = Browser.PAGE_READ_MAX_BYTES).getOrNull() ?: return false
        if (!text.contains("#EXTINF")) return false
        val firstPiece = text.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?.trim()?.substringBefore('?')?.lowercase() ?: return false
        return listOf(".vtt", ".srt", ".ttml").any { firstPiece.endsWith(it) }
    }

    private fun isPlaylist(text: String?): Boolean = text?.trimStart()?.startsWith("#EXTM3U") == true
}
