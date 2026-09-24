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
import com.markhoor.mediadownloader.data.local.DownloadEntity
import com.markhoor.mediadownloader.data.network.HttpStatusException
import com.markhoor.mediadownloader.data.network.isMediaGone
import com.markhoor.mediadownloader.di.MediaDownloaderComponent
import com.markhoor.mediadownloader.domain.models.DownloadState
import kotlinx.coroutines.CancellationException
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

    private suspend fun run(id: Long, component: MediaDownloaderComponent): Result {
        val dao = component.downloadDao
        val notifier = component.downloadNotifier
        val storage = component.downloadStorage

        val runnable = listOf(DownloadState.Queued, DownloadState.WaitingForNetwork, DownloadState.Downloading)
        // Paused or deleted before it got here: nothing to do, and nothing wrong.
        if (dao.moveState(id, from = runnable, state = DownloadState.Downloading) == 0) return Result.success()
        val entity = dao.get(id) ?: return Result.success()
        notifier.clearResult(id)
        runInForeground(entity, component.downloadNotifier)

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
                    component.downloadEngine.download(task, meter)
                } finally {
                    reporter.cancel()
                }
            }
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
        val moved = component.downloadDao.moveState(entity.id, from = listOf(DownloadState.Downloading), state = waiting)
        // Zero rows means the user paused or deleted it meanwhile, and that is not news to report.
        if (moved > 0 && waiting == DownloadState.WaitingForNetwork) {
            component.downloadNotifier.showWaitingForNetwork(entity)
        }
        if (component.downloadDao.get(entity.id) == null) component.downloadStorage.deleteWorkFiles(entity)
    }

    private suspend fun onFailed(entity: DownloadEntity, error: Exception, component: MediaDownloaderComponent): Result {
        val dao = component.downloadDao
        val message = (error.message ?: error::class.simpleName)?.take(Download.MAX_ERROR_LENGTH)
        component.downloadNotifier.cancelProgress(entity.id)
        // Media that is not there to take: the site answered with a page or a placeholder instead
        // of the video, or refused the request outright - a private or deleted post, a link already
        // spent. Asking four more times over a growing backoff would be told the same thing, so it
        // fails now, and the notification says what happened instead of inviting a retry.
        if (error is UnusableMediaException || (error is HttpStatusException && error.isMediaGone())) {
            // Marked, so the row can say this too rather than reading as an ordinary failure.
            dao.countFailure(entity.id, Download.MEDIA_GONE_MARKER + message)
            if (dao.moveState(entity.id, from = listOf(DownloadState.Downloading), state = DownloadState.Failed) > 0) {
                component.downloadNotifier.showUnavailable(entity)
            }
            return Result.failure()
        }
        if (!component.networkStatus.hasConnection()) {
            dao.moveState(entity.id, from = listOf(DownloadState.Downloading), state = DownloadState.WaitingForNetwork)
            component.downloadNotifier.showWaitingForNetwork(entity)
            // A fresh job once the connection is back, not a retry that waits out a growing backoff.
            component.downloadScheduler.continueWhenOnline(entity.id)
            return Result.success()
        }
        dao.countFailure(entity.id, message)
        if (entity.failedAttempts + 1 < Download.MAX_ATTEMPTS) {
            dao.moveState(entity.id, from = listOf(DownloadState.Downloading), state = DownloadState.Queued)
            return Result.retry()
        }
        // Parts already fetched stay, so trying again later resumes rather than starts over.
        if (dao.moveState(entity.id, from = listOf(DownloadState.Downloading), state = DownloadState.Failed) > 0) {
            component.downloadNotifier.showFailed(entity)
        }
        return Result.failure()
    }

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
