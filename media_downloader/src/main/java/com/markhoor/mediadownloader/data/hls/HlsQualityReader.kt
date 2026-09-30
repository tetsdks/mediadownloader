package com.markhoor.mediadownloader.data.hls

import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.Constants.MediaSize
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.qualityNameFromResolution
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.network.MediaSizeProbe
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType

/**
 * What an HLS playlist turned out to hold: its encodes, and how long they run.
 *
 * The running time falls out of reading the playlist, which is done for the sizes anyway, so it
 * costs nothing to say. A live playlist has none and says `null`.
 */
internal data class HlsMedia(
    val qualities: List<MediaQualityModel>,
    val durationMillis: Long?,
)

/**
 * The qualities an HLS playlist offers, labelled and sized.
 *
 * A master lists its encodes and states what each averages; one variant is read for the running
 * time, and rate × time is each encode's size. A media playlist is one encode: its size is the
 * sum of the sizes it states for its pieces, or else one piece is measured and scaled over the
 * running time. A live playlist has no running time yet and is left unsized.
 */
internal class HlsQualityReader(
    private val fetcher: HttpFetcher,
    private val sizeProbe: MediaSizeProbe,
) {

    private val labelInUrl = Regex("""(\d{3,4})p""")

    /** What is behind [playlistUrl], or nothing at all when it cannot be read. */
    suspend fun qualitiesOf(playlistUrl: String, headers: Map<String, String>): HlsMedia {
        val text = fetcher.getText(playlistUrl, headers).getOrNull()
            ?: return HlsMedia(emptyList(), null)
        return if (HlsPlaylistParser.isMaster(text)) {
            masterQualities(text, playlistUrl, headers)
        } else {
            val playlist = HlsPlaylistParser.parseMedia(text, playlistUrl)
            HlsMedia(
                qualities = listOf(mediaPlaylistQuality(playlist, playlistUrl, headers)),
                durationMillis = runningMillis(playlist.durationSeconds.takeIf { playlist.isComplete }),
            )
        }
    }

    private suspend fun masterQualities(
        text: String,
        playlistUrl: String,
        headers: Map<String, String>,
    ): HlsMedia {
        // Best first, and one entry per label: a master can list the same height twice at two
        // rates ("360p", "360p"), and the sheet cannot tell the user which is which.
        val variants = HlsPlaylistParser.parseMaster(text, playlistUrl)
            .sortedWith(compareByDescending<HlsVariantDto> { shortSideOf(it) }.thenByDescending { rateOf(it) })
            .distinctBy(::labelOf)
        val seconds = variants.firstOrNull()?.let { runningSeconds(it.url, headers) } ?: 0.0
        return HlsMedia(
            qualities = variants.map { variant ->
                MediaQualityModel(
                    url = variant.url,
                    label = labelOf(variant),
                    type = MediaType.Video,
                    sizeBytes = estimatedBytes(variant, seconds),
                    audioUrl = variant.audioUrl,
                    headers = headers,
                )
            },
            durationMillis = runningMillis(seconds),
        )
    }

    private suspend fun mediaPlaylistQuality(
        playlist: HlsMediaPlaylistDto,
        playlistUrl: String,
        headers: Map<String, String>,
    ): MediaQualityModel {
        return MediaQualityModel(
            url = playlistUrl,
            label = labelInUrl.find(playlistUrl)?.let { "${it.groupValues[1]}p" } ?: QualityLabels.HD,
            type = MediaType.Video,
            sizeBytes = if (playlist.isComplete) playlist.statedBytes ?: sampledBytes(playlist, headers) else null,
            headers = headers,
        )
    }

    /** One piece measured and scaled over the whole running time. */
    private suspend fun sampledBytes(playlist: HlsMediaPlaylistDto, headers: Map<String, String>): Long? {
        val sample = playlist.segments.firstOrNull { it.durationSeconds > 0 } ?: return null
        val sampleBytes = sizeProbe.sizeOf(sample.url, headers) ?: return null
        val bytesPerSecond = sampleBytes / sample.durationSeconds
        // Lighter than this is the CDN's refusal, not a piece of video.
        if (bytesPerSecond < MediaSize.MIN_SAMPLED_BYTES_PER_SECOND) return null
        return (bytesPerSecond * playlist.durationSeconds).toLong().takeIf { it > 0 }
    }

    /** Seconds a playlist states, as a length worth showing; a live one states none. */
    private fun runningMillis(seconds: Double?): Long? = seconds
        ?.takeIf { it.isFinite() && it > 0.0 && it < Browser.LONGEST_BELIEVABLE_SECONDS }
        ?.let { (it * 1_000).toLong() }

    private suspend fun runningSeconds(variantUrl: String, headers: Map<String, String>): Double {
        val text = fetcher.getText(variantUrl, headers).getOrNull() ?: return 0.0
        return HlsPlaylistParser.parseMedia(text, variantUrl).durationSeconds
    }

    /** Average bits per second over the running time, in bytes; the playlist states a rate, not a size. */
    private fun estimatedBytes(variant: HlsVariantDto, seconds: Double): Long? {
        val bitsPerSecond = variant.averageBandwidth ?: variant.bandwidth ?: return null
        if (seconds <= 0.0 || bitsPerSecond <= 0) return null
        return (bitsPerSecond / 8.0 * seconds).toLong().takeIf { it > 0 }
    }

    private fun shortSideOf(variant: HlsVariantDto): Int =
        variant.resolution?.split('x')?.mapNotNull { it.trim().toIntOrNull() }?.minOrNull() ?: 0

    private fun rateOf(variant: HlsVariantDto): Long = variant.averageBandwidth ?: variant.bandwidth ?: 0L

    private fun labelOf(variant: HlsVariantDto): String =
        variant.resolution?.qualityNameFromResolution()
            ?: variant.bandwidth?.let { "${it / 1000} kbps" }
            ?: QualityLabels.HD
}
