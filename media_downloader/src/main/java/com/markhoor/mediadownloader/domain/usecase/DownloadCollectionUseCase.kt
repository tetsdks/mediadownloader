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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Queues a collection's entries: several at a time, each into the collection's own folder, named by
 * its place in the list.
 *
 * Each entry has to be read for its qualities before it can be queued, and that is a second or two
 * of network per entry. Doing it one after another drip-fed the downloads - with a short video the
 * next one was not even queued before the last had finished, so a playlist that could have come
 * down at once came down in single file. [READERS_AT_ONCE] of them are read together instead, which
 * is enough to keep the downloader busy without asking the site for fifty pages at once.
 *
 * Entries are read when their turn comes rather than all up front: a site mints a video's urls for
 * whoever asked and they go stale within hours, so a long playlist read in one go would have its
 * tail expire before it was reached.
 *
 * This does not make the caller wait. The work runs in the module's own scope, the downloads appear
 * as they are added, and [progress] is there for a host that wants to say "adding 3 of 50".
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

    /** What is left to add, so a paused collection can carry on where it stopped. */
    private var pending: Pending? = null

    private class Pending(
        val collection: MediaCollectionModel,
        val preferredQuality: String?,
        /** Entries not yet queued, by their place in the collection - which is their number. */
        val remaining: List<IndexedValue<MediaCollectionItem>>,
    )

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
        add(Pending(collection, preferredQuality, collection.items.withIndex().toList()))
    }

    /**
     * Stops adding what is left of [title], leaving what is already queued alone - pausing those is
     * the download repository's work, and a host pausing a whole collection does both.
     *
     * Nothing happens when another collection is being added: the one on screen is the one meant.
     */
    fun pause(title: String) {
        if (_progress.value.title != title) return
        job?.cancel()
        job = null
        _progress.update { it.copy(isAdding = false, isPaused = true) }
    }

    /** Carries on adding what [pause] left, from where it stopped. */
    fun resume(title: String) {
        val left = pending?.takeIf { it.collection.title == title && it.remaining.isNotEmpty() }
        _progress.update { it.copy(isPaused = false) }
        if (left == null || job?.isActive == true) return
        _progress.update { it.copy(isAdding = true) }
        add(left)
    }

    /** Stops adding what is left; what is already queued stays queued. */
    fun stop() {
        job?.cancel()
        job = null
        pending = null
        _progress.update { it.copy(isAdding = false) }
    }

    private fun add(work: Pending) {
        pending = work
        val permits = Semaphore(READERS_AT_ONCE)
        val left = work.remaining.toMutableList()
        job = scope.launch {
            try {
                coroutineScope {
                    work.remaining.map { entry ->
                        async {
                            val added = permits.withPermit {
                                add(entry.value, work.collection, entry.index, work.preferredQuality)
                            }
                            // Counted and crossed off even if the pause lands in this instant:
                            // an entry queued and then forgotten would be queued twice on resume.
                            withContext(NonCancellable) {
                                synchronized(left) { left.remove(entry) }
                                _progress.update { progress ->
                                    if (added) progress.copy(added = progress.added + 1)
                                    else progress.copy(failed = progress.failed + 1)
                                }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                // What is left is recorded however this ended - finished, paused or replaced.
                pending = Pending(work.collection, work.preferredQuality, synchronized(left) { left.toList() })
                _progress.update { it.copy(isAdding = false) }
            }
        }
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
            collectionTitle = collection.title,
        )
        return enqueue(request).isSuccess
    }

    private fun numberedName(index: Int, total: Int, title: String): String {
        val width = total.toString().length.coerceAtLeast(2)
        return "${(index + 1).toString().padStart(width, '0')} - $title"
            .take(Download.MAX_STORED_TITLE_LENGTH)
    }

    private companion object {
        /**
         * How many entries are read at once. Enough to keep the downloader fed - it runs several
         * downloads at a time itself - without turning a playlist into a burst of requests at the
         * site it came from.
         */
        const val READERS_AT_ONCE = 3
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
