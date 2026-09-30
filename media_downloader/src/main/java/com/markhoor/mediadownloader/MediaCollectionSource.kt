package com.markhoor.mediadownloader

import com.markhoor.mediadownloader.domain.models.MediaCollectionModel

/**
 * A [MediaSource] that can also read links naming many pieces of media - a playlist, an album, a
 * board - rather than one.
 *
 * Implement it on the same class as [MediaSource]; the module asks the sources the host supplied
 * whether any of them reads a link this way before it treats the link as one piece of media. What
 * comes back is a list of links, not of downloads: each is read for its own qualities when its turn
 * to be fetched comes, which is also why a collection of any size can be started at once.
 *
 * Nothing else about the host's side changes. A collection is offered, queued and saved by the
 * module: its downloads go to a folder named after it, numbered in the order it listed them.
 */
interface MediaCollectionSource {

    /**
     * Whether [url] names a collection. Asked only for links on [MediaSource.hosts], before
     * anything is fetched, and asked **first** - a link this returns `true` for is never read as
     * one piece of media.
     */
    fun handlesCollection(url: String): Boolean

    /**
     * What [url] lists, or `null` when it turns out to hold nothing. Called off the main thread and
     * may throw, which is treated as "holds nothing".
     */
    suspend fun readCollection(url: String): MediaCollectionModel?
}
