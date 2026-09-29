package com.markhoor.mediadownloader.data.scraper.tube

import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.decodeHtmlEntities
import com.markhoor.mediadownloader.core.decodeJsonEscapes
import com.markhoor.mediadownloader.core.isAdvertMediaUrl
import com.markhoor.mediadownloader.core.metaProperty
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
    private val PLAYER_CALL = Regex("""html5player\.set([A-Za-z]+)\s*\(\s*'([^']*)'\s*\)""")
    private val LINKED_DATA = Regex("""<script[^>]+application/ld\+json[^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    // A field is a string, or a list of them: linked data writes one thumbnail as ["…"].
    private val LD_FIELD = { name: String -> Regex(""""$name"\s*:\s*\[?\s*"([^"]+)"""") }
    private val ISO_DURATION = Regex("""P(?:T)?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""")
    private val HEIGHT_IN_URL = Regex("""(\d{3,4})p""")

    /** The Aylo player object as JSON text, or `null` when the page carries none. */
    fun playerObjectOf(html: String): String? {
        val openingBrace = PLAYER_OBJECT.find(html)?.range?.last ?: return null
        return objectAt(html, openingBrace)
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

    /** The schema.org VideoObject a tube page carries for search engines. */
    fun fromLinkedData(html: String): ScrapedMediaDto? {
        for (block in LINKED_DATA.findAll(html).map { it.groupValues[1] }) {
            if (!block.contains("VideoObject")) continue
            val contentUrl = field(block, "contentUrl")?.takeIf(::usable) ?: continue
            return ScrapedMediaDto(
                qualities = listOf(quality(contentUrl, labelOf(contentUrl, QualityLabels.HD))),
                title = field(block, "name")?.decodeHtmlEntities()?.trim()?.ifBlank { null },
                thumbnailUrl = field(block, "thumbnailUrl")?.ifBlank { null },
                durationMillis = field(block, "duration")?.let(::millisOf),
            )
        }
        return null
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
