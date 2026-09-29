package com.markhoor.mediadownloader.data.scraper.tube

import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.decodeHtmlEntities
import com.markhoor.mediadownloader.core.decodeJsonEscapes
import com.markhoor.mediadownloader.core.isAdvertMediaUrl
import com.markhoor.mediadownloader.core.isHlsPlaylistUrl
import com.markhoor.mediadownloader.core.isVideoFileUrl
import com.markhoor.mediadownloader.core.looksLikeImageUrl
import com.markhoor.mediadownloader.core.metaProperty
import com.markhoor.mediadownloader.core.normalizedHost
import com.markhoor.mediadownloader.core.titleFromHtml
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.domain.models.MediaType

/**
 * Reads the media out of a tube site's own page, without knowing which site it is.
 *
 * There are thousands of these sites and a handful of players between them, so this recognises the
 * shapes rather than the hosts. In the order they are tried, because each knows more than the next:
 *
 * 1. **The player object** - `var flashvars_<id> = {…}` - which lists every quality. Aylo's network
 *    (PornHub, RedTube, YouPorn, Tube8) writes it. Handled by the scraper, which has a second
 *    request to make for it; this only finds it.
 * 2. **Player calls** - `html5player.setVideoUrlHigh('…')` and its siblings, which xVideos and XNXX
 *    build their player with. Plain files, and a master playlist beside them.
 * 3. **Linked data** - the `schema.org` VideoObject nearly every tube page carries for search
 *    engines, whose `contentUrl` is the video itself.
 * 4. **Open Graph** - what the page tells a chat app it is showing, when nothing else said.
 *
 * A site that encrypts its urls (xHamster hands its player hex blobs) is read by none of these, and
 * is meant to fall back to the browser, which watches what the player actually fetches.
 *
 * Everything here drops advert urls: a tube page plays a pre-roll from an advert network, and that
 * file is often the only one on the page before the video is asked for.
 */
internal object TubePlayerParser {

    private val PLAYER_OBJECT = Regex("""var\s+flashvars_\d+\s*=\s*\{""")
    private const val DEFINITIONS_KEY = "\"mediaDefinitions\""
    private const val VIDEO_OBJECT = "VideoObject"
    private val PLAYER_CALL = Regex("""html5player\.set([A-Za-z]+)\s*\(\s*'([^']*)'\s*\)""")
    private val LINKED_DATA = Regex("""<script[^>]+application/ld\+json[^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    // A field is a string, or a list of them: linked data writes one thumbnail as ["…"].
    private val LD_FIELD = { name: String -> Regex(""""$name"\s*:\s*\[?\s*"([^"]+)"""") }
    private val ISO_DURATION = Regex("""P(?:T)?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""")
    private val HEIGHT_IN_URL = Regex("""(\d{3,4})p""")
    private val KVS_FIELD = Regex("""(\w+):\s*'([^']*)'""")

    /** What KVS calls its qualities, in the order the player writes them. */
    private val KVS_URL_FIELDS = listOf(
        "video_url", "video_alt_url", "video_alt_url2", "video_alt_url3", "video_alt_url4",
    )

    private val PRELOAD_LINK = Regex("""<link[^>]+preload[^>]*>""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val FILE_IN_PAGE = Regex("""https?:(?:\\?/){2}[^"'\s]{10,200}?\.mp4(?:\?[^"'\s]{0,80})?""")

    /** A hovered thumbnail's clip, which these sites file under a name of its own. */
    private fun String.namesAPreview(): Boolean =
        listOf("/tmb/", "/thumb", "/preview", "/trailer").any { contains(it, ignoreCase = true) }

    /**
     * The Aylo player object as JSON text, or `null` when the page carries none. The network's
     * mirrors write the same object without the assignment that usually introduces it, so it is
     * also looked for by the one key that matters: the object around it is the player's.
     */
    fun playerObjectOf(html: String): String? {
        PLAYER_OBJECT.find(html)?.range?.last?.let { return objectAt(html, it) }
        val key = html.indexOf(DEFINITIONS_KEY)
        return if (key < 0) null else objectAround(html, key)
    }

    /** xVideos and XNXX: the player is assembled by a run of `html5player.setX('…')` calls. */
    fun fromPlayerCalls(html: String): ScrapedMediaDto? {
        val calls = PLAYER_CALL.findAll(html)
            .associate { it.groupValues[1] to it.groupValues[2].decodeJsonEscapes() }
            .takeIf { it.isNotEmpty() } ?: return null
        val files = listOfNotNull(
            calls["VideoUrlHigh"]?.let { it to QualityLabels.HD },
            calls["VideoUrlLow"]?.let { it to QualityLabels.SD },
        )
        // The files when there are any, and the master playlist alone when there are not: a
        // playlist is expanded into its own qualities later, and only when it arrives on its own.
        val qualities = files
            .filter { (url, _) -> usable(url) }
            .map { (url, fallback) -> quality(url, labelOf(url, fallback)) }
            .ifEmpty { listOfNotNull(calls["VideoHLS"]?.takeIf(::usable)?.let { quality(it, QualityLabels.HD) }) }
        if (qualities.isEmpty()) return null
        return ScrapedMediaDto(
            qualities = qualities,
            title = calls["VideoTitle"]?.decodeHtmlEntities()?.trim()?.ifBlank { null },
            thumbnailUrl = calls["ThumbUrl"]?.ifBlank { null },
        )
    }

    /**
     * The schema.org VideoObject a tube page carries for search engines - the node itself, not the
     * block it sits in. A page often describes its cover in the same block, as an ImageObject with
     * a `contentUrl` of its own, and reading the block whole offered the cover as the video.
     */
    fun fromLinkedData(html: String): ScrapedMediaDto? {
        // The blocks a page files under `ld+json`, and then the page itself: a site built on a
        // JavaScript framework writes the same description into the data its pages are built from,
        // where no script tag names it (brazzers does).
        val blocks = LINKED_DATA.findAll(html).map { it.groupValues[1] } + sequenceOf(html)
        for (whole in blocks) {
            val marker = whole.indexOf(VIDEO_OBJECT)
            if (marker < 0) continue
            val block = objectAround(whole, marker) ?: whole
            val contentUrl = field(block, "contentUrl")
                ?.takeIf { usable(it) && !it.looksLikeImageUrl() } ?: continue
            return ScrapedMediaDto(
                qualities = listOf(quality(contentUrl, labelOf(contentUrl, QualityLabels.HD))),
                title = field(block, "name")?.decodeHtmlEntities()?.trim()?.ifBlank { null },
                thumbnailUrl = field(block, "thumbnailUrl")?.ifBlank { null },
                durationMillis = field(block, "duration")?.let(::millisOf),
            )
        }
        return null
    }

    /**
     * The KVS player's own fields. The script most of these sites run writes its video out as
     * `video_url`, with `video_url_text` for what to call that quality, and any others beside it
     * as `video_alt_url`, `video_alt_url2` and so on - so the qualities are named by the site
     * instead of guessed from a file name, and a page whose files carry no height still has them.
     */
    fun fromKvsPlayer(html: String, pageUrl: String): ScrapedMediaDto? {
        val host = pageUrl.normalizedHost() ?: return null
        val fields = KVS_FIELD.findAll(html)
            .associate { it.groupValues[1] to it.groupValues[2].decodeJsonEscapes() }
            .takeIf { it.isNotEmpty() } ?: return null
        val qualities = KVS_URL_FIELDS
            .mapNotNull { name -> fields[name]?.let { url -> url to fields[name + "_text"] } }
            .filter { (url, _) -> url.normalizedHost() == host && usable(url) && !url.namesAPreview() }
            .distinctBy { (url, _) -> url }
            .map { (url, name) -> quality(url, name?.ifBlank { null } ?: labelOf(url, QualityLabels.HD)) }
            .ifEmpty { return null }
        return ScrapedMediaDto(
            qualities = qualities,
            title = fields["video_title"]?.decodeHtmlEntities()?.trim()?.ifBlank { null }
                ?: html.titleFromHtml().ifBlank { null },
            thumbnailUrl = fields["preview_url"]?.ifBlank { null },
        )
    }

    /**
     * A file the page names outright. KVS - the script most of these sites run - writes its
     * download as `/get_file/<keys>/<id>.mp4`, on the site's own host; others simply name an mp4.
     * Only files on the page's own site count, so a preview on a thumbnail cdn and an advert's
     * creative on somebody else's are not mistaken for the video.
     */
    fun fromNamedFile(html: String, pageUrl: String): ScrapedMediaDto? {
        val host = pageUrl.normalizedHost() ?: return null
        val files = FILE_IN_PAGE.findAll(html)
            .map { it.value.decodeJsonEscapes() }
            .filter { it.normalizedHost() == host && usable(it) && !it.namesAPreview() }
            .distinct()
            .map { quality(it, labelOf(it, QualityLabels.HD)) }
            .sortedByDescending { it.label?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0 }
            .distinctBy { it.label }
            .toList()
            .ifEmpty { return null }
        return ScrapedMediaDto(
            qualities = files,
            title = html.titleFromHtml().ifBlank { null },
            thumbnailUrl = html.metaProperty("og:image")?.ifBlank { null },
        )
    }

    /**
     * The stream a page asks the browser to start fetching before its player is even built:
     * `<link rel="preload" as="fetch" href="…master.m3u8">`. xHamster writes it, and it is the
     * video itself - the advert before it belongs to another network and is never preloaded - so
     * a site that encrypts everything its player is handed still says here what it is about to
     * play.
     */
    fun fromPreloadedStream(html: String, pageUrl: String): ScrapedMediaDto? {
        val stream = PRELOAD_LINK.findAll(html)
            .mapNotNull { HREF.find(it.value)?.groupValues?.get(1)?.decodeJsonEscapes() }
            .firstOrNull { usable(it) && (it.isHlsPlaylistUrl() || it.isVideoFileUrl()) }
            ?: return null
        return ScrapedMediaDto(
            qualities = listOf(quality(stream, labelOf(stream, QualityLabels.HD))),
            title = html.titleFromHtml().ifBlank { null },
            thumbnailUrl = html.metaProperty("og:image")?.ifBlank { null },
        )
    }

    /** What the page tells a chat app it is showing, when nothing better is on the page. */
    fun fromOpenGraph(html: String): ScrapedMediaDto? {
        val video = listOf("og:video:secure_url", "og:video:url", "og:video")
            .firstNotNullOfOrNull { html.metaProperty(it) }
            ?.decodeJsonEscapes()
            ?.takeIf(::usable) ?: return null
        return ScrapedMediaDto(
            qualities = listOf(quality(video, labelOf(video, QualityLabels.HD))),
            title = html.metaProperty("og:title")?.decodeHtmlEntities()?.trim()?.ifBlank { null },
            thumbnailUrl = html.metaProperty("og:image")?.ifBlank { null },
        )
    }

    /** `PT1H2M3S`, as the linked data writes a running time, in milliseconds. */
    fun millisOf(isoDuration: String): Long? {
        val match = ISO_DURATION.matchEntire(isoDuration.trim()) ?: return null
        val (hours, minutes, seconds) = match.destructured
        val total = (hours.toLongOrNull() ?: 0) * 3_600 +
            (minutes.toLongOrNull() ?: 0) * 60 +
            (seconds.toDoubleOrNull()?.toLong() ?: 0)
        return (total * 1_000).takeIf { it > 0 }
    }

    /** The height the url names, when it names one: `…/video_720p.mp4`. */
    fun labelOf(url: String, fallback: String): String =
        HEIGHT_IN_URL.find(url.substringBefore('?'))?.value ?: fallback

    private fun quality(url: String, label: String) =
        ScrapedQualityDto(url = url, type = MediaType.Video, label = label)

    private fun usable(url: String): Boolean = url.startsWith("http") && !url.isAdvertMediaUrl()

    private fun field(block: String, name: String): String? =
        LD_FIELD(name).find(block)?.groupValues?.get(1)?.decodeJsonEscapes()

    /** The innermost object [text] holds around [marker], or `null` when it is in none. */
    private fun objectAround(text: String, marker: Int): String? {
        val starts = ArrayDeque<Int>()
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val character = text[index]
            when {
                escaped -> escaped = false
                inString && character == '\\' -> escaped = true
                character == '"' -> inString = !inString
                inString -> Unit
                character == '{' -> starts.addLast(index)
                character == '}' -> {
                    val start = starts.removeLastOrNull() ?: continue
                    if (start <= marker && marker <= index) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    /**
     * The JSON object that starts at [open], counted out brace by brace rather than matched by a
     * regex: it nests objects, and holds strings with braces of their own.
     */
    private fun objectAt(text: String, open: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in open until text.length) {
            val character = text[index]
            when {
                escaped -> escaped = false
                inString && character == '\\' -> escaped = true
                character == '"' -> inString = !inString
                inString -> Unit
                character == '{' -> depth++
                character == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, index + 1)
                }
            }
        }
        return null
    }
}
