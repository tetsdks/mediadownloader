package com.markhoor.mediadownloader.domain.models

/**
 * A link that names many pieces of media rather than one: a playlist, an album, a board.
 *
 * What is listed here is links, not downloads - reading a playlist states what is in it, and each
 * video is read for its own qualities only when it is about to be fetched. A fifty-video playlist
 * would otherwise mean fifty page reads before anything at all could start, and the urls read at
 * the front would have expired before the back of the list was reached.
 *
 * @param title what the collection is called; it is also the folder its downloads are saved in.
 * @param sourceUrl the link it was read from.
 * @param items the media it holds, **in the order it lists them**.
 */
data class MediaCollectionModel(
    val title: String,
    val sourceUrl: String,
    val items: List<MediaCollectionItem>,
)

/**
 * One entry of a [MediaCollectionModel]: a link to read when its turn comes, and what the
 * collection says about it.
 */
data class MediaCollectionItem(
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val durationMillis: Long? = null,
)

/** What a link turned out to be. */
sealed interface ParsedLink {

    /** One piece of media, ready to offer. */
    data class One(val media: MediaModel) : ParsedLink

    /** Many, listed but not yet read. */
    data class Many(val collection: MediaCollectionModel) : ParsedLink
}

/**
 * How far a collection has got towards being downloaded: not the downloads' own progress, which is
 * in `observeDownloads`, but how many of its entries have been read and queued.
 *
 * A collection is added one entry at a time, in order, because each has to be read for its
 * qualities first. A host that wants to say "adding 3 of 50" says it from here.
 *
 * @param failed entries that could not be read - a video taken down, or one nothing can download.
 * @param isPaused the collection was paused; [isAdding] is then `false` because nothing is being
 *   added, and resuming carries on from where it stopped.
 */
data class CollectionProgress(
    val title: String = "",
    val added: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
    val isAdding: Boolean = false,
    val isPaused: Boolean = false,
)
