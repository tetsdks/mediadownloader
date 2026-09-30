package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.MediaCollectionSource
import com.markhoor.mediadownloader.core.extractLink
import com.markhoor.mediadownloader.domain.models.MediaParseException
import com.markhoor.mediadownloader.domain.models.ParsedLink
import com.markhoor.mediadownloader.domain.models.SiteAccess
import kotlinx.coroutines.CancellationException

/**
 * What a pasted or shared link is: one piece of media, or a collection of them.
 *
 * A collection is asked about first and by link alone, before anything is fetched, so a playlist is
 * never read as though it were a video. Everything else is [ParseLinkUseCase]'s answer, wrapped -
 * which is what keeps one entry point enough for a host: it hands over a link and is told what it
 * turned out to be.
 */
internal class ReadLinkUseCase(
    private val checkSiteAccess: CheckSiteAccessUseCase,
    private val parseLink: ParseLinkUseCase,
    private val collectionSources: List<MediaCollectionSource>,
) {

    suspend operator fun invoke(text: String): Result<ParsedLink> {
        val link = text.extractLink()
        when (checkSiteAccess(link)) {
            SiteAccess.Blocked -> return Result.failure(MediaParseException.SiteBlocked(link))
            SiteAccess.Unsupported -> return Result.failure(MediaParseException.SiteUnsupported(link))
            SiteAccess.Allowed -> Unit
        }
        readCollection(link)?.let { return it }
        return parseLink(link).map(ParsedLink::One)
    }

    /**
     * The collection behind [link], or `null` when no source claims it. A source that claims a link
     * and then finds nothing in it ends the search: the link was a playlist, and an empty playlist
     * is not a video to fall back to.
     */
    private suspend fun readCollection(link: String): Result<ParsedLink>? {
        val source = collectionSources.firstOrNull { claims(it, link) } ?: return null
        val collection = try {
            source.readCollection(link)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return Result.failure(MediaParseException.MediaNotFound(link, error))
        }
        return when {
            collection == null || collection.items.isEmpty() ->
                Result.failure(MediaParseException.MediaNotFound(link, null))
            else -> Result.success(ParsedLink.Many(collection))
        }
    }

    /** A source deciding by link must not take the whole read down when it throws. */
    private fun claims(source: MediaCollectionSource, link: String): Boolean =
        runCatching { source.handlesCollection(link) }.getOrDefault(false)
}
