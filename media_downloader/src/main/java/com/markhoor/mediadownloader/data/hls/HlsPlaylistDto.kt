package com.markhoor.mediadownloader.data.hls

/** One encode listed in a master playlist. [url] and [audioUrl] are absolute. */
internal data class HlsVariantDto(
    val url: String,
    val bandwidth: Long? = null,
    val averageBandwidth: Long? = null,
    val resolution: String? = null,
    val codecs: String? = null,
    val frameRate: Float? = null,
    /** The playlist holding this encode's sound, when the master keeps sound and picture apart. */
    val audioUrl: String? = null,
)

/**
 * One piece of a media playlist. [url] is absolute and carries the playlist's query when it has
 * none of its own.
 *
 * @param byteRange the slice of [url] this piece is, for playlists that cut one file by offset;
 *   the download must ask for it with a `Range` header.
 * @param statedBytes this piece's size when the playlist says it - from [byteRange], or from a
 *   range the url itself names (rumble's `r_range=`).
 */
internal data class HlsSegmentDto(
    val url: String,
    val durationSeconds: Double,
    val byteRange: LongRange? = null,
    val statedBytes: Long? = null,
    /** The AES-128 key this piece is encrypted with, or `null` for a clear piece. */
    val key: HlsKeyDto? = null,
    /** This piece's media sequence number; its IV when the key names none. */
    val sequence: Long = 0,
)

/** An `#EXT-X-KEY` with METHOD=AES-128: where the key is, and the IV when the playlist gives one. */
internal data class HlsKeyDto(
    val url: String,
    /** The IV as the playlist writes it (`0x` and 32 hex digits), or `null` to use the piece's sequence. */
    val ivHex: String? = null,
)

/**
 * A media playlist: the pieces of one encode, in order.
 *
 * @param initSegment the `#EXT-X-MAP` piece that must come first, when there is one.
 * @param isComplete `#EXT-X-ENDLIST` is present; a live playlist is still being written.
 */
internal data class HlsMediaPlaylistDto(
    val segments: List<HlsSegmentDto>,
    val initSegment: HlsSegmentDto? = null,
    val isComplete: Boolean,
    /** An encryption method the downloader cannot undo (SAMPLE-AES, DRM), when the playlist uses one. */
    val unsupportedEncryption: String? = null,
) {
    val durationSeconds: Double get() = segments.sumOf { it.durationSeconds }

    /**
     * The whole encode's size when the playlist states every piece's, or `null`. Part of a total
     * presented as the total would be worse than an estimate.
     */
    val statedBytes: Long?
        get() {
            if (segments.isEmpty() || segments.any { it.statedBytes == null }) return null
            return segments.sumOf { it.statedBytes ?: 0L } + (initSegment?.statedBytes ?: 0L)
        }
}
