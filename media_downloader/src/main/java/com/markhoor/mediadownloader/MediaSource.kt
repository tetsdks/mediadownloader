package com.markhoor.mediadownloader

import com.markhoor.mediadownloader.domain.models.MediaModel

/**
 * A reader for links this module does not read itself, supplied by the host app through
 * [MediaDownloaderConfig.extraSources].
 *
 * It is used exactly like the module's own readers: a link on one of its [hosts] is handed to
 * [read], and what comes back goes through the same pipeline as everything else - qualities are
 * sized, a playlist is expanded into its encodes, and a card on a feed of that site hands over the
 * post's own page rather than the feed's.
 *
 * Two rules worth knowing:
 *
 * - **The module's own readers win.** A source for a site that is already read here is never asked;
 *   nothing a host app supplies can quietly replace Instagram or TikTok.
 * - **Site access still decides.** A source's hosts count as supported, so strict mode does not
 *   refuse them - but a blocked site stays blocked. YouTube is blocked unless
 *   [MediaDownloaderConfig.allowYouTube] is on, whatever source is supplied for it.
 *
 * This is the seam for anything that cannot live in this library: a reader built on a
 * copyleft-licensed extractor, a site only one app cares about, a service with a key the library
 * must not hold.
 */
interface MediaSource {

    /**
     * The hosts whose links this reads, as registrable domains - `"youtube.com"`, `"youtu.be"`.
     * A host matches when it equals one of these or ends in `"." + one`, so subdomains are covered.
     */
    val hosts: Set<String>

    /**
     * Whether [url] names one piece of media this source can read. Asked only for links on [hosts],
     * before anything is fetched, and `true` by default.
     *
     * Worth writing whenever a site is more than its posts. The module asks this to tell a post's
     * own page from the rest of the site: a page that names one video is read as that video - its
     * title and artwork are the media's - while a feed, a channel or a search page is a list of
     * other pages, where a press means "read the card I pressed". A source that claims every link
     * on its hosts makes the feed itself look like a video, and one button appears where there
     * should be one per card.
     */
    fun handles(url: String): Boolean = true

    /**
     * The media behind [url], or `null` when this source has none for it - a profile page, a link
     * shape it does not recognise, a post that turned out to hold nothing.
     *
     * Called off the main thread, and may throw: a failure is treated as "found nothing" and the
     * link is answered as unparseable, the same as for the module's own readers. Cancellation is
     * honoured - the call is cancelled when the reader leaves the page.
     *
     * [MediaModel.sourceUrl] is ignored; the link that was asked for is what the download records.
     * Headers needed by the download go on the qualities, and the first quality's headers are used
     * for all of them, which is how the module's own readers work too.
     */
    suspend fun read(url: String): MediaModel?
}
