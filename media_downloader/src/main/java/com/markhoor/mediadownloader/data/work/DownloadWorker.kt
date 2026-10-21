package com.markhoor.mediadownloader.data.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.markhoor.mediadownloader.MediaDownloader
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.data.download.DownloadTask
import com.markhoor.mediadownloader.data.download.ProgressMeter
import com.markhoor.mediadownloader.data.download.UnusableMediaException
import com.markhoor.mediadownloader.core.isHlsPlaylistUrl
import com.markhoor.mediadownloader.domain.usecase.closestTo
import com.markhoor.mediadownloader.data.local.DownloadEntity
import com.markhoor.mediadownloader.data.network.HttpStatusException
import com.markhoor.mediadownloader.data.network.isMediaGone
import com.markhoor.mediadownloader.data.network.isRefused
import com.markhoor.mediadownloader.di.MediaDownloaderComponent
import com.markhoor.mediadownloader.domain.models.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs one download, in the foreground with its progress notification.
 *
 * The row decides: the worker only starts a download that is still waiting to run, and when it is
 * stopped it leaves a pause or a delete the user made alone. Failures from a lost connection wait
 * for one without counting; any other failure counts, and after [Download.MAX_ATTEMPTS] of them
 * the download is given up.
 *
 * Needs `MediaDownloader.initialize` to have run in `Application.onCreate` - WorkManager can start
 * this in a fresh process before any screen.
 */
internal class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val component = MediaDownloader.componentOrNull()
            ?: return failWith("MediaDownloader.initialize(context) was not called in Application.onCreate()")
        val id = inputData.getLong(Download.KEY_DOWNLOAD_ID, Download.NO_DOWNLOAD_ID)
        if (id == Download.NO_DOWNLOAD_ID) return failWith("The download job carries no download id")
        return component.downloadRunLocks.withLock(id) { run(id, component) }
    }

    private companion object {
        /** A download this worker is carrying: fetching its bytes, or writing them into a file. */
        private val runningStates = listOf(DownloadState.Downloading, DownloadState.Finishing)
    }

    private suspend fun run(id: Long, component: MediaDownloaderComponent): Result {
        val dao = component.downloadDao
        val notifier = component.downloadNotifier
        val storage = component.downloadStorage

        // Finishing too: a process death while the file was being written leaves the row there,
        // and the work has to be able to pick it up again.
        val runnable = listOf(
            DownloadState.Queued,
            DownloadState.WaitingForNetwork,
            DownloadState.Downloading,
            DownloadState.Finishing,
        )
        // Paused or deleted before it got here: nothing to do, and nothing wrong.
        if (dao.moveState(id, from = runnable, state = DownloadState.Downloading) == 0) return Result.success()
        val queued = dao.get(id) ?: return Result.success()
        notifier.clearResult(id)
        runInForeground(queued, component.downloadNotifier)

        // A download queued with only a page to read - every entry of a playlist is - reads it now,
        // in its own turn. Now rather than when it was queued, because a site mints a video's urls
        // for whoever asked and they go stale within hours.
        val entity = if (queued.mediaUrl.isBlank()) {
            when (val read = readItsPage(queued, component)) {
                is PageRead.Read -> read.entity
                // Whatever the failure asked for. It used to be thrown away and answered with
                // success: an ordinary failure - a connection reset while the page was read -
                // puts the row back in `Queued` and asks for a retry, so answering success left
                // WorkManager thinking the work was done with nothing scheduled to run it. The
                // row then sat in `Queued` for ever, and `resume` would not take it either,
                // since that accepts only `Paused`, `Failed` and `WaitingForNetwork`.
                is PageRead.Failed -> return read.result
            }
        } else {
            queued
        }

        val meter = ProgressMeter(startBytes = entity.downloadedBytes, startTotal = entity.totalBytes)
        val task = DownloadTask(
            url = entity.mediaUrl,
            audioUrl = entity.audioUrl,
            headers = entity.headers,
            isStream = entity.isStream,
            singleConnection = entity.singleConnection,
            workDir = storage.workDirFor(id),
            output = storage.outputFor(entity),
        )

        return try {
            coroutineScope {
                val reporter = launch {
                    while (true) {
                        delay(Download.PROGRESS_INTERVAL_MS.milliseconds)
                        // Pieces arrive in parallel between estimates, so what is on disk can
                        // pass the estimate for a moment; a total below it would read over 100%.
                        val downloaded = meter.downloadedBytes
                        val total = meter.totalBytes?.let { maxOf(it, downloaded) }
                        dao.setProgress(id, downloaded, total)
                        notifier.showProgress(entity, downloaded, total)
                    }
                }
                try {
                    component.downloadEngine.download(task, meter) {
                        // The bytes are in and a join has begun, which can be the longer half of
                        // the wait on a long video.
                        markFinishing(id, reporter, component, meter)
                    }
                } finally {
                    reporter.cancel()
                }
            }
            // Publishing is a copy into the public folder, which on a big file is a wait of its
            // own - and the download that has nothing left to fetch is not downloading any more.
            markFinishing(id, reporter = null, component = component, meter = meter)
            // Publishing and recording it are one step: stopped between them, the file would be
            // published and the row sent to download it again, into a second copy.
            withContext(NonCancellable) { complete(entity, component) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { onStopped(entity, component) }
            throw e
        } catch (e: Exception) {
            onFailed(entity, e, component)
        }
    }

    /** A job that cannot run at all: said in the log and in the job's output, never retried. */
    private fun failWith(message: String): Result {
        Log.e(Download.LOG_TAG, message)
        return Result.failure(workDataOf(Download.KEY_ERROR to message))
    }

    /**
     * Says the download has everything it is going to fetch and is now being written: joined,
     * stitched, moved into the public folder. There is nothing left to count in that, so the
     * progress reporter stops and what it wrote last stands.
     */
    private suspend fun markFinishing(
        id: Long,
        reporter: Job?,
        component: MediaDownloaderComponent,
        meter: ProgressMeter,
    ) {
        reporter?.cancel()
        val dao = component.downloadDao
        if (dao.moveState(id, from = listOf(DownloadState.Downloading), state = DownloadState.Finishing) == 0) return
        dao.get(id)?.let { component.downloadNotifier.showProgress(it, meter.downloadedBytes, meter.totalBytes) }
    }

    /**
     * Reads the page a download was queued with and fills the row in, or fails the download when
     * the page has nothing to give. [DownloadEntity.qualityLabel] is the quality to aim for until
     * this runs; afterwards it is the one that was actually found.
     */
    /** What reading a queued entry's page came to: the row as it now stands, or what to answer. */
    private sealed interface PageRead {
        data class Read(val entity: DownloadEntity) : PageRead
        data class Failed(val result: Result) : PageRead
    }

    private suspend fun readItsPage(
        entity: DownloadEntity,
        component: MediaDownloaderComponent,
    ): PageRead {
        val media = component.parseLink(entity.sourceUrl).getOrElse { error ->
            // A parse failure is an Exception in every path that can reach here; anything else is
            // the page being impossible rather than the read going wrong.
            return PageRead.Failed(
                onFailed(entity, error as? Exception ?: UnusableMediaException(error.message.orEmpty()), component),
            )
        }
        val quality = media.qualities.filter { it.url.isNotBlank() }.closestTo(entity.qualityLabel)
        if (quality == null) {
            return PageRead.Failed(
                onFailed(entity, UnusableMediaException("Nothing to download at ${entity.sourceUrl}"), component),
            )
        }
        component.downloadDao.setResolved(
            id = entity.id,
            mediaUrl = quality.url,
            audioUrl = quality.audioUrl,
            headers = quality.headers,
            type = quality.type,
            qualityLabel = quality.label,
            isStream = quality.url.isHlsPlaylistUrl(),
            totalBytes = quality.sizeBytes,
            // The page's own name beats the one the list gave, which is often shortened.
            title = media.title.ifBlank { entity.title }.take(Download.MAX_STORED_TITLE_LENGTH),
            thumbnailUrl = media.thumbnailUrl ?: entity.thumbnailUrl,
        )
        // Gone from under us - deleted while its page was read - is nothing to answer with but
        // success, as it was before.
        val read = component.downloadDao.get(entity.id) ?: return PageRead.Failed(Result.success())
        return PageRead.Read(read)
    }

    private suspend fun complete(entity: DownloadEntity, component: MediaDownloaderComponent): Result {
        val file = component.downloadStorage.publish(entity)
        component.downloadDao.complete(entity.id, file.name, file.length())
        if (component.downloadDao.get(entity.id) == null) {
            // Deleted at the moment it finished: its file goes with it.
            file.delete()
            component.downloadStorage.deleteWorkFiles(entity)
            return Result.success()
        }
        component.downloadStorage.deleteWorkFiles(entity)
        component.downloadStorage.announce(file)
        component.downloadNotifier.showCompleted(entity.copy(fileName = file.name))
        return Result.success()
    }

    /**
     * Stopped from outside. A pause or a delete already wrote its own state; anything else - the
     * system reclaiming the job, the connection dropping - is put back in line, and WorkManager
     * runs it again.
     *
     * A lost connection arrives here, not in [onFailed]: the job carries a network constraint, so
     * the system stops it rather than letting it fail. Its progress notification goes with it, and
     * one saying what happened takes its place - otherwise the download disappears from the shade
     * with nothing said, and comes back only when the connection does.
     */
    private suspend fun onStopped(entity: DownloadEntity, component: MediaDownloaderComponent) {
        component.downloadNotifier.cancelProgress(entity.id)
        val waiting =
            if (component.networkStatus.hasConnection()) DownloadState.Queued else DownloadState.WaitingForNetwork
        val moved = component.downloadDao.moveState(entity.id, from = runningStates, state = waiting)
        // Zero rows means the user paused or deleted it meanwhile, and that is not news to report.
        if (moved > 0 && waiting == DownloadState.WaitingForNetwork) {
            component.downloadNotifier.showWaitingForNetwork(entity)
        }
        if (component.downloadDao.get(entity.id) == null) component.downloadStorage.deleteWorkFiles(entity)
    }

    private suspend fun onFailed(entity: DownloadEntity, error: Exception, component: MediaDownloaderComponent): Result {
        val dao = component.downloadDao
        val refusal = (error as? HttpStatusException)?.takeIf { it.isRefused() }
        // A refusal says nothing about the reader and everything about the link, so it says so -
        // rather than "HTTP 403 for <a signed url two hundred characters long>", which is what the
        // row used to carry and what a host had to put in front of its reader.
        val message = (refusal?.let { refusedMessage(it.status) } ?: error.message ?: error::class.simpleName)
            ?.take(Download.MAX_ERROR_LENGTH)
        component.downloadNotifier.cancelProgress(entity.id)
        // Media that is not there to take: the site answered with a page or a placeholder instead
        // of the video - a private or deleted post. Asking four more times over a growing backoff
        // would be told the same thing, so it fails now, and the notification says what happened
        // instead of inviting a retry. A refusal is not that, and is left to the ordinary path.
        if (error is UnusableMediaException || (error is HttpStatusException && error.isMediaGone() && !error.isRefused())) {
            // Marked, so the row can say this too rather than reading as an ordinary failure.
            dao.countFailure(entity.id, Download.MEDIA_GONE_MARKER + message)
            if (dao.moveState(entity.id, from = runningStates, state = DownloadState.Failed) > 0) {
                component.downloadNotifier.showUnavailable(entity)
            }
            return Result.failure()
        }
        // Turned away: most often a signed link that has been spent, and the page it came from is
        // the only place a fresh one is minted. Forgetting what was resolved sends the next run
        // back through `readItsPage`, which is the same path every playlist entry already takes.
        // Only where a parser actually reads that page - a stream found by sniffing a page nobody
        // parses has nowhere to go back to, and asking again is all that is left to try.
        if (refusal != null && component.canReadPageOf(entity.sourceUrl)) {
            dao.forgetResolvedMedia(entity.id)
            component.downloadStorage.deleteWorkFiles(entity)
        }
        if (!component.networkStatus.hasConnection()) {
            dao.moveState(entity.id, from = runningStates, state = DownloadState.WaitingForNetwork)
            component.downloadNotifier.showWaitingForNetwork(entity)
            // A fresh job once the connection is back, not a retry that waits out a growing backoff.
            component.downloadScheduler.continueWhenOnline(entity.id)
            return Result.success()
        }
        dao.countFailure(entity.id, message)
        if (entity.failedAttempts + 1 < Download.MAX_ATTEMPTS) {
            dao.moveState(entity.id, from = runningStates, state = DownloadState.Queued)
            return Result.retry()
        }
        // Parts already fetched stay, so trying again later resumes rather than starts over.
        if (dao.moveState(entity.id, from = runningStates, state = DownloadState.Failed) > 0) {
            component.downloadNotifier.showFailed(entity)
        }
        return Result.failure()
    }

    /** What a refusal tells the reader, in place of the raw status and the signed url it names. */
    private fun refusedMessage(status: Int) =
        "The site turned this download away (HTTP $status). Its link may have expired - try again."

    /**
     * Keeps the process alive while the app is in the background. Android 12+ refuses a foreground
     * start from the background in some cases; the download then runs as ordinary work instead of
     * failing.
     */
    private suspend fun runInForeground(entity: DownloadEntity, notifier: DownloadNotifier) {
        val notification = notifier.progress(entity, downloadedBytes = 0, totalBytes = null)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifier.progressId(entity.id), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifier.progressId(entity.id), notification)
        }
        try {
            setForeground(info)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notifier.showProgress(entity, downloadedBytes = 0, totalBytes = null)
        }
    }
}
