package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.core.extractLink
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaParseException
import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.domain.repo.MediaParserRepository
import kotlinx.coroutines.CancellationException

/**
 * A pasted or shared link to its media. The site is checked first, so a blocked or unsupported
 * site is never fetched at all.
 *
 * Accepts text as well as a bare url - "look at this https://..." is what a share arrives as.
 */
internal class ParseLinkUseCase(
    private val checkSiteAccess: CheckSiteAccessUseCase,
    private val repository: MediaParserRepository,
) {

    suspend operator fun invoke(text: String): Result<MediaModel> {
        val link = text.extractLink()
        return when (checkSiteAccess(link)) {
            SiteAccess.Blocked -> Result.failure(MediaParseException.SiteBlocked(link))
            SiteAccess.Unsupported -> Result.failure(MediaParseException.SiteUnsupported(link))
            SiteAccess.Allowed -> withParseError(repository.parse(link), link)
        }
    }

    /** Every failure reaches the caller as a [MediaParseException]; cancellation is never swallowed. */
    private fun withParseError(result: Result<MediaModel>, link: String): Result<MediaModel> {
        val error = result.exceptionOrNull() ?: return result
        if (error is CancellationException) throw error
        return Result.failure(error as? MediaParseException ?: MediaParseException.MediaNotFound(link, error))
    }
}
