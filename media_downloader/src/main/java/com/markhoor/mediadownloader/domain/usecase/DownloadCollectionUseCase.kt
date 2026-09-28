package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.domain.models.CollectionProgress
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.MediaCollectionItem
import com.markhoor.mediadownloader.domain.models.MediaCollectionModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Queues every entry of a collection, in the order it was listed.
 *
 * One entry at a time, and read only when its turn comes: a video's urls are minted for whoever
 * asked and go stale, so reading fifty up front would leave the back of the list expired before it
 * was reached. Reading one takes a second or two, which is why this does not make the caller wait -
 * the work runs in the module's own scope and the downloads appear in the list as they are added.
 * [progress] is there for a host that wants to say "adding 3 of 50".
 *
 * Each entry is saved into a folder named after the collection, under a name that starts with its
 * place in it - `01 - …`, `02 - …` - so the order survives in the folder whatever order the
 * downloads themselves finish in.
 */
internal class DownloadCollectionUseCase(
    private val parseLink: ParseLinkUseCase,
    private val enqueue: EnqueueDownloadUseCase,
    private val scope: CoroutineScope,
) {

    private val _progress = MutableStateFlow(CollectionProgress())
    val progress: StateFlow<CollectionProgress> = _progress.asStateFlow()

    /** The collection being added right now; starting another replaces it. */
    private var job: Job? = null

    /**
     * Starts adding [collection]. [preferredQuality] is a label to aim for - `"720p"` - and each
     * entry gets the closest it actually offers, which is not always the same one.
     */
    fun start(collection: MediaCollectionModel, preferredQuality: String?) {
        job?.cancel()
        _progress.value = CollectionProgress(
            title = collection.title,
            total = collection.items.size,
            isAdding = true,
        )
        job = scope.launch {
            try {
                collection.items.forEachIndexed { index, item ->
                    val added = add(item, collection, index, preferredQuality)
                    _progress.update { progress ->
                        if (added) progress.copy(added = progress.added + 1)
                        else progress.copy(failed = progress.failed + 1)
                    }
                }
            } finally {
                _progress.update { it.copy(isAdding = false) }
            }
        }
    }

    /** Stops adding what is left; what is already queued stays queued. */
    fun stop() {
        job?.cancel()
        job = null
        _progress.update { it.copy(isAdding = false) }
    }

    /** Reads one entry and queues it. `false` when it cannot be read or holds nothing to fetch. */
    private suspend fun add(
        item: MediaCollectionItem,
        collection: MediaCollectionModel,
        index: Int,
        preferredQuality: String?,
    ): Boolean {
        val media = try {
            parseLink(item.url).getOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            null
        } ?: return false
        val quality = media.qualities.filter { it.url.isNotBlank() }.closestTo(preferredQuality)
            ?: return false
        val title = media.title.ifBlank { item.title }
        val request = DownloadRequest.of(
            media = media.copy(title = title),
            quality = quality,
            fileName = numberedName(index, collection.items.size, title),
            siteFolder = collection.title,
        )
        return enqueue(request).isSuccess
    }

    private fun numberedName(index: Int, total: Int, title: String): String {
        val width = total.toString().length.coerceAtLeast(2)
        return "${(index + 1).toString().padStart(width, '0')} - $title"
            .take(Download.MAX_STORED_TITLE_LENGTH)
    }
}

/**
 * The quality closest to [preferred], which is a label such as `"720p"`.
 *
 * A collection is downloaded at one quality, and its entries do not all offer the same ones: a
 * video with no 1080p should come down at the best it has rather than not at all. Heights are
 * compared where both labels state one, and the list's own order - best first, as every reader
 * hands it over - settles everything else.
 */
internal fun List<MediaQualityModel>.closestTo(preferred: String?): MediaQualityModel? {
    if (isEmpty()) return null
    val wanted = preferred?.heightInLabel() ?: return first()
    firstOrNull { it.label.equals(preferred, ignoreCase = true) }?.let { return it }
    val withHeights = mapNotNull { quality -> quality.label.heightInLabel()?.let { quality to it } }
    if (withHeights.isEmpty()) return first()
    // The tallest that is no taller than asked for; failing that, the shortest above it.
    return withHeights.filter { it.second <= wanted }.maxByOrNull { it.second }?.first
        ?: withHeights.minByOrNull { it.second }?.first
}

/** The height a quality's label states - `"1080p60"` is 1080 - or `null` when it states none. */
private fun String.heightInLabel(): Int? =
    Regex("""(\d{2,4})\s*[pP]""").find(this)?.groupValues?.get(1)?.toIntOrNull()
