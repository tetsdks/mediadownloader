package com.markhoor.mediadownloader.data.scraper.imdb

import com.markhoor.mediadownloader.core.decodeHtmlEntities
import com.markhoor.mediadownloader.core.decodeJsonEscapes
import com.markhoor.mediadownloader.core.iso8601DurationMillis
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.domain.models.MediaType

/**
 * What an IMDb video page says about the video it plays.
 *
 * The page hands its player a list of files, one per height, and a playlist of the same video
 * beside them; its linked data names the video, pictures it and says how long it runs. Everything
 * here is read out of the page as text, since the list sits inside the page's own state rather
 * than in any markup.
 */
internal object ImdbVideoParser {

    /**
     * One entry of the player's list. The label is required to read like a height or `AUTO`,
     * because the page names other things the same way - the kind of video ("Trailer") is written
     * as a `displayName` too, and a looser match paired that with the first file after it.
     */
    private val PLAYBACK = Regex(
        """"displayName":\{"value":"(\d{3,4}p|AUTO)".{0,160}?"videoMimeType":"([A-Z0-9]{3,5})".{0,120}?"url":"([^"]{20,})"""",
        // A page writes its state on one line; one pretty-printed is read just the same.
        RegexOption.DOT_MATCHES_ALL,
    )
    private val LINKED_DATA = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val NAME = Regex(""""name"\s*:\s*"([^"]+)"""")
    private val THUMBNAIL = Regex(""""thumbnailUrl"\s*:\s*"([^"]+)"""")
    private val DURATION = Regex(""""duration"\s*:\s*"(PT[^"]+)"""")

    private const val FILE = "MP4"

    /**
     * Every file the page offers, largest first, and the playlist only when it offers no files.
     * A file is one request, a size that can be measured before the download starts and no remux
     * afterwards; the playlist is the same video and is left to be expanded further down.
     */
    fun qualitiesOf(page: String): List<ScrapedQualityDto> {
        val entries = PLAYBACK.findAll(page)
            .map { match ->
                val (label, kind, url) = match.destructured
                Entry(label = label, kind = kind, url = url.decodeJsonEscapes())
            }
            .distinctBy { it.url }
            .toList()
        val files = entries.filter { it.kind == FILE }
            .sortedByDescending { it.height }
            .map { ScrapedQualityDto(url = it.url, type = MediaType.Video, label = it.label) }
        // One playlist, not all of them: a master is expanded into every height it lists, and
        // handing over several would offer the same video several times over.
        return files.ifEmpty {
            entries.firstOrNull { it.kind != FILE }
                ?.let { listOf(ScrapedQualityDto(url = it.url, type = MediaType.Video, label = it.label)) }
                .orEmpty()
        }
    }

    /** The video's name, as the page states it for anything that reads pages. */
    fun titleOf(page: String): String? = linkedData(page)
        ?.let { NAME.find(it)?.groupValues?.get(1) }
        ?.decodeJsonEscapes()?.decodeHtmlEntities()?.trim()?.ifBlank { null }

    fun thumbnailOf(page: String): String? = linkedData(page)
        ?.let { THUMBNAIL.find(it)?.groupValues?.get(1) }
        ?.decodeJsonEscapes()?.trim()?.ifBlank { null }

    fun durationMillisOf(page: String): Long? = linkedData(page)
        ?.let { DURATION.find(it)?.groupValues?.get(1) }
        ?.iso8601DurationMillis()

    /** The page's own description of itself. Everything else on the page states names too. */
    private fun linkedData(page: String): String? = LINKED_DATA.find(page)?.groupValues?.get(1)

    private data class Entry(val label: String, val kind: String, val url: String) {
        /** What the entry is named after; a playlist names no height and sorts below every file. */
        val height: Int get() = label.removeSuffix("p").toIntOrNull() ?: 0
    }
}
