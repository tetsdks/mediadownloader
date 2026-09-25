package com.markhoor.mediadownloader.data.browser

import com.markhoor.mediadownloader.core.isHlsPlaylistUrl
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.sensibleSize
import com.markhoor.mediadownloader.data.hls.HlsQualityReader
import com.markhoor.mediadownloader.data.network.MediaSizeProbe
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.domain.usecase.ParseLinkUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Turns media found on a page into what the download sheet shows: a stream's qualities, a post's
 * name and every quality the parser knows, and a size for each.
 *
 * Found media is shown at once and described afterwards, so nothing here is on the way to the
 * user seeing that something was found.
 *
 * @param sizeProbesAtOnce how many sizes are asked for at once; fewer on a low-end device.
 */
internal class MediaDescriber(
    private val parseLink: ParseLinkUseCase,
    private val hlsQualityReader: HlsQualityReader,
    private val sizeProbe: MediaSizeProbe,
    private val sizeProbesAtOnce: Int,
) {

    /**
     * [found], filled in. [postUrl] is the post it came from, when a parser can read it;
     * [keepFoundUrl] keeps the file the page itself handed over as the first quality (Threads,
     * where the parser's guess is worse than the page's own file).
     */
    suspend fun describe(found: MediaModel, postUrl: String?, keepFoundUrl: Boolean): MediaModel {
        val first = found.qualities.firstOrNull() ?: return found
        val richer = when {
            postUrl != null -> parseLink(postUrl).getOrNull()?.let { parsed ->
                if (keepFoundUrl && first.url.isHttpUrl()) {
                    parsed.copy(qualities = parsed.qualities.mapIndexed { index, quality ->
                        if (index == 0) quality.copy(url = first.url) else quality
                    })
                } else {
                    parsed
                }
            }
            first.url.isHlsPlaylistUrl() && found.qualities.size == 1 ->
                hlsQualityReader.qualitiesOf(first.url, first.headers).takeIf { it.qualities.isNotEmpty() }
                    ?.let { stream ->
                        // The playlist states how long it runs, and on a sniffed stream nothing else
                        // does unless the player that was pressed happened to know.
                        found.copy(
                            qualities = stream.qualities,
                            durationMillis = stream.durationMillis ?: found.durationMillis,
                        )
                    }
            else -> null
        }
        val source = richer ?: found
        val qualities = sized(source.qualities.ifEmpty { found.qualities })
        // Fewer qualities than were found is a worse answer, not a better one: keep what was found.
        return if (qualities.size >= found.qualities.size) {
            found.copy(
                qualities = qualities,
                title = source.title.ifBlank { found.title },
                thumbnailUrl = source.thumbnailUrl?.takeIf { it.isNotBlank() } ?: found.thumbnailUrl,
                durationMillis = source.durationMillis ?: found.durationMillis,
            )
        } else {
            source.copy(qualities = found.qualities, title = source.title.ifBlank { found.title })
        }
    }

    /** Every quality with a size where one can be known; a size already known is not asked again. */
    private suspend fun sized(qualities: List<MediaQualityModel>): List<MediaQualityModel> {
        val permits = Semaphore(sizeProbesAtOnce)
        return coroutineScope {
            qualities.map { quality ->
                async {
                    val isVideo = quality.type == MediaType.Video
                    val size = quality.sizeBytes
                        ?: if (quality.url.isHlsPlaylistUrl()) null else permits.withPermit {
                            sizeProbe.sizeOf(quality.url, quality.headers, acceptsImage = !isVideo)
                        }
                    quality.copy(sizeBytes = size.sensibleSize(isVideo))
                }
            }.awaitAll()
        }
    }
}
