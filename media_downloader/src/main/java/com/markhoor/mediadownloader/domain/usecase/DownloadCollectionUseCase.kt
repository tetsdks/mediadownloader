package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.domain.models.CollectionProgress
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.MediaCollectionItem
import com.markhoor.mediadownloader.domain.models.MediaCollectionModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Queues every entry of a collection at once, each into the collection's own folder, named by its
 * place in the list.
 *
 * A row per entry from the first moment, before anything has been read: a reader who asks for a
 * fifty-video playlist sees fifty downloads waiting their turn, not three that trickle into being.
 * What each entry is - its qualities, its file, its real name - is read by the download itself when
 * its turn comes ([DownloadRequest.mediaUrl] is blank until then), which is also what keeps its
 * urls fresh: a site mints them for whoever asked and they go stale within hours.
 *
 * That leaves nothing here to pause or resume: the entries are ordinary downloads, and the download
 * repository pauses and resumes a collection of them like any other set.
 */
internal class DownloadCollectionUseCase(
    private val enqueue: EnqueueDownloadUseCase,
    private val scope: CoroutineScope,
) {

    private val _progress = MutableStateFlow(CollectionProgress())
    val progress: StateFlow<CollectionProgress> = _progress.asStateFlow()

    /**
     * Queues [collection]. [preferredQuality] is a label to aim for - `"720p"` - and each entry
     * gets the closest it turns out to offer, which is not always the same one.
     */
    fun start(collection: MediaCollectionModel, preferredQuality: String?) {
        _progress.value = CollectionProgress(
            title = collection.title,
            total = collection.items.size,
            isAdding = true,
        )
        scope.launch {
            try {
                collection.items.forEachIndexed { index, item ->
                    val queued = enqueue(request(item, collection, index, preferredQuality)).isSuccess
                    _progress.update { progress ->
                        if (queued) progress.copy(added = progress.added + 1)
                        else progress.copy(failed = progress.failed + 1)
                    }
                }
            } finally {
                _progress.update { it.copy(isAdding = false) }
            }
        }
    }

    private fun request(
        item: MediaCollectionItem,
        collection: MediaCollectionModel,
        index: Int,
        preferredQuality: String?,
    ) = DownloadRequest(
        // Blank: the page below is read when this download runs, and answers with the file.
        mediaUrl = "",
        type = MediaType.Video,
        title = item.title.ifBlank { collection.title },
        sourceUrl = item.url,
        thumbnailUrl = item.thumbnailUrl,
        // Until it is read, this is the quality being asked for rather than the one it has.
        qualityLabel = preferredQuality.orEmpty(),
        fileName = numberedName(index, collection.items.size, item.title.ifBlank { collection.title }),
        siteFolder = collection.title,
        collectionTitle = collection.title,
    )

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
