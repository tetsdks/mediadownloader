package com.markhoor.mediadownloader.data.hls

import com.markhoor.mediadownloader.core.Constants.Hls
import com.markhoor.mediadownloader.core.inheritQueryFrom
import com.markhoor.mediadownloader.core.resolveAgainst

/**
 * Reads HLS playlists. Pure: text in, playlist out, nothing fetched and no state kept between
 * calls.
 */
internal object HlsPlaylistParser {

    /** Rumble names each piece by its offsets inside one archive: `...&r_range=512-13033611`. */
    private val URL_BYTE_RANGE = Regex("""[?&]r_range=(\d+)-(\d+)""")

    /** An attribute list entry: `KEY=value` or `KEY="value, with commas"`. */
    private val ATTRIBUTE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    /** A master lists encodes; a media playlist lists pieces of one encode. */
    fun isMaster(text: String): Boolean = text.contains(Hls.STREAM_INF)

    /**
     * The encodes a master playlist offers, in the order it lists them. A variant that names an
     * AUDIO group is picture only; it is given the group's playlist as [HlsVariantDto.audioUrl].
     */
    fun parseMaster(text: String, playlistUrl: String): List<HlsVariantDto> {
        val lines = text.lines().map { it.trim() }
        val audioPlaylists = lines
            .filter { it.startsWith(Hls.MEDIA) }
            .map { attributesOf(it.removePrefix(Hls.MEDIA)) }
            .filter { it["TYPE"] == "AUDIO" && !it["URI"].isNullOrBlank() && it["GROUP-ID"] != null }
            .associate { it.getValue("GROUP-ID") to it.getValue("URI").resolveAgainst(playlistUrl) }

        val variants = mutableListOf<HlsVariantDto>()
        var pending: Map<String, String>? = null
        for (line in lines) {
            when {
                line.startsWith(Hls.STREAM_INF) -> pending = attributesOf(line.removePrefix(Hls.STREAM_INF))
                pending != null && line.isNotEmpty() && !line.startsWith("#") -> {
                    variants += variantOf(pending, url = line.resolveAgainst(playlistUrl), audioPlaylists)
                    pending = null
                }
            }
        }
        return variants
    }

    /** The pieces of one encode, absolute and in order, with the byte ranges they are cut at. */
    fun parseMedia(text: String, playlistUrl: String): HlsMediaPlaylistDto {
        val segments = mutableListOf<HlsSegmentDto>()
        var initSegment: HlsSegmentDto? = null
        var pendingDuration: Double? = null
        var pendingRange: LongRange? = null
        var nextRangeStart = 0L
        var mediaSequence = 0L
        var key: HlsKeyDto? = null
        var unsupportedEncryption: String? = null

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith(Hls.MEDIA_SEQUENCE) ->
                    mediaSequence = line.removePrefix(Hls.MEDIA_SEQUENCE).trim().toLongOrNull() ?: 0L
                line.startsWith(Hls.KEY) -> {
                    // A key applies to every piece after it, until the next one.
                    val attributes = attributesOf(line.removePrefix(Hls.KEY))
                    val method = attributes["METHOD"].orEmpty()
                    key = when (method) {
                        Hls.METHOD_NONE -> null
                        Hls.METHOD_AES_128 -> attributes["URI"]?.takeIf { it.isNotBlank() }?.let { uri ->
                            HlsKeyDto(uri.resolveAgainst(playlistUrl).inheritQueryFrom(playlistUrl), attributes["IV"])
                        }
                        else -> {
                            unsupportedEncryption = method.ifBlank { "an unknown method" }
                            null
                        }
                    }
                }
                line.startsWith(Hls.MAP) -> initSegment = mapSegment(line, playlistUrl)?.copy(key = key, sequence = mediaSequence)
                line.startsWith(Hls.EXTINF) -> pendingDuration =
                    line.removePrefix(Hls.EXTINF).substringBefore(',').trim().toDoubleOrNull()
                line.startsWith(Hls.BYTERANGE) -> pendingRange =
                    byteRange(line.removePrefix(Hls.BYTERANGE), defaultStart = nextRangeStart)
                        ?.also { nextRangeStart = it.last + 1 }
                pendingDuration != null && line.isNotEmpty() && !line.startsWith("#") -> {
                    segments += HlsSegmentDto(
                        url = line.resolveAgainst(playlistUrl).inheritQueryFrom(playlistUrl),
                        durationSeconds = pendingDuration,
                        byteRange = pendingRange,
                        statedBytes = (pendingRange ?: urlByteRange(line))?.let(::lengthOf),
                        key = key,
                        sequence = mediaSequence + segments.size,
                    )
                    pendingDuration = null
                    pendingRange = null
                }
            }
        }
        return HlsMediaPlaylistDto(
            segments,
            initSegment,
            isComplete = text.contains(Hls.ENDLIST),
            unsupportedEncryption = unsupportedEncryption,
        )
    }

    private fun mapSegment(line: String, playlistUrl: String): HlsSegmentDto? {
        val attributes = attributesOf(line.removePrefix(Hls.MAP))
        val uri = attributes["URI"]?.takeIf { it.isNotBlank() } ?: return null
        val range = attributes["BYTERANGE"]?.let { byteRange(it, defaultStart = 0L) }
        return HlsSegmentDto(
            url = uri.resolveAgainst(playlistUrl).inheritQueryFrom(playlistUrl),
            durationSeconds = 0.0,
            byteRange = range,
            statedBytes = range?.let(::lengthOf),
        )
    }

    /** `<length>[@<offset>]`; without an offset the range continues from the previous one. */
    private fun byteRange(value: String, defaultStart: Long): LongRange? {
        val length = value.substringBefore('@').trim().toLongOrNull() ?: return null
        val start = if ('@' in value) value.substringAfter('@').trim().toLongOrNull() ?: return null else defaultStart
        return if (length > 0) start until start + length else null
    }

    private fun urlByteRange(url: String): LongRange? {
        val match = URL_BYTE_RANGE.find(url) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        return if (end >= start) start..end else null
    }

    private fun lengthOf(range: LongRange): Long = range.last - range.first + 1

    private fun attributesOf(list: String): Map<String, String> =
        ATTRIBUTE.findAll(list).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun variantOf(attributes: Map<String, String>, url: String, audioPlaylists: Map<String, String>) =
        HlsVariantDto(
            url = url,
            bandwidth = attributes["BANDWIDTH"]?.toLongOrNull(),
            averageBandwidth = attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
            resolution = attributes["RESOLUTION"],
            codecs = attributes["CODECS"],
            frameRate = attributes["FRAME-RATE"]?.toFloatOrNull(),
            audioUrl = attributes["AUDIO"]?.let { group -> audioPlaylists[group] },
        )
}
