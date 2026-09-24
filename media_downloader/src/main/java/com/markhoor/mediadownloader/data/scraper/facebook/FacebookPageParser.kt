package com.markhoor.mediadownloader.data.scraper.facebook

import com.markhoor.mediadownloader.core.Constants.Json
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.titleFromHtml
import com.markhoor.mediadownloader.core.unescapeEmbeddedUrl
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.domain.models.MediaType

/** Reads a Facebook video page: its HD and SD files, its title and its cover. Pure. */
internal object FacebookPageParser {

    private val HD_URL = Regex(""""browser_native_hd_url":${Json.STRING_VALUE}""")
    private val SD_URL = Regex(""""browser_native_sd_url":${Json.STRING_VALUE}""")
    private val COVER_URL = Regex(""""image":\{"uri":${Json.STRING_VALUE}""")

    /** The page's video, or `null` when the page states no playable file. */
    fun parse(html: String): ScrapedMediaDto? {
        val qualities = listOfNotNull(
            urlOf(HD_URL, html)?.let { ScrapedQualityDto(it, MediaType.Video, QualityLabels.HD) },
            urlOf(SD_URL, html)?.let { ScrapedQualityDto(it, MediaType.Video, QualityLabels.SD) },
        )
        if (qualities.isEmpty()) return null
        return ScrapedMediaDto(
            qualities = qualities,
            title = html.titleFromHtml().ifBlank { null },
            thumbnailUrl = urlOf(COVER_URL, html),
        )
    }

    private fun urlOf(pattern: Regex, html: String): String? =
        pattern.find(html)?.groupValues?.get(1)?.unescapeEmbeddedUrl()?.takeIf { it.isHttpUrl() }
}
