package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.domain.models.DownloadException
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.domain.repo.DownloadRepository
import kotlinx.coroutines.CancellationException

/**
 * Starts a download after checking it may be made. The page and the media are both checked
 * against the block list: a restricted site's file must stay undownloadable even when it reaches
 * here through another page. `Unsupported` is not refused - media lives on CDN hosts no list
 * names, and whether to offer a download for a page was decided before this.
 */
internal class EnqueueDownloadUseCase(
    private val checkSiteAccess: CheckSiteAccessUseCase,
    private val repository: DownloadRepository,
) {

    suspend operator fun invoke(request: DownloadRequest): Result<Long> {
        val mediaUrl = request.mediaUrl.trim()
        if (!mediaUrl.isHttpUrl() || mediaUrl.length > Download.MAX_STORED_URL_LENGTH) {
            return Result.failure(DownloadException.InvalidMediaUrl(mediaUrl.take(200)))
        }
        val blocked = listOfNotNull(request.sourceUrl, mediaUrl, request.audioUrl)
            .firstOrNull { checkSiteAccess(it) == SiteAccess.Blocked }
        if (blocked != null) return Result.failure(DownloadException.SiteBlocked(blocked.take(200)))

        return try {
            Result.success(repository.enqueue(request.copy(mediaUrl = mediaUrl)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
