package com.markhoor.mediadownloader.data.repo

import android.util.Log
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.core.endingOnFailure
import com.markhoor.mediadownloader.core.isHlsPlaylistUrl
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.isUnderAnyOf
import com.markhoor.mediadownloader.core.isUsableThumbnail
import com.markhoor.mediadownloader.core.normalizedHost
import com.markhoor.mediadownloader.data.device.NetworkStatus
import com.markhoor.mediadownloader.data.local.DownloadDao
import com.markhoor.mediadownloader.data.local.DownloadEntity
import com.markhoor.mediadownloader.data.storage.DownloadStorage
import com.markhoor.mediadownloader.data.work.DownloadNotifier
import com.markhoor.mediadownloader.data.work.DownloadScheduler
import com.markhoor.mediadownloader.domain.models.DownloadException
import com.markhoor.mediadownloader.domain.models.DownloadModel
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.domain.repo.DownloadRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Downloads on Room and WorkManager. The row is written first and the job started second, so a
 * job never runs without its row; every later change goes through the row the same way.
 *
 * Pause and resume are single conditional writes: when two callers act on one download at once,
 * exactly one write matches, and only that caller touches the job.
 */
internal class DownloadRepositoryImpl(
    private val dao: DownloadDao,
    schedulerProvider: Lazy<DownloadScheduler>,
    private val storage: DownloadStorage,
    private val notifier: DownloadNotifier,
    private val network: NetworkStatus,
    private val ioDispatcher: CoroutineDispatcher,
) : DownloadRepository {

    /**
     * Built the first time something is actually scheduled, and never to read the list.
     *
     * Building it is `WorkManager.getInstance`, which initialises WorkManager, and that is not
     * free of risk: WorkManager 2.12.0 calls `JobScheduler.forNamespace` on anything reporting
     * SDK 34 or above, with no fallback, and a device whose framework lacks that method answers
     * with `NoSuchMethodError`. Reported from the field in October 2026 on an Android 14 build -
     * seven crashes, raised by a host that only wanted to count finished downloads and so was
     * collecting `observeDownloads` at startup. Reading the list is Room and nothing else, so it
     * has no business initialising a scheduler; now it does not, and such a device gets its list
     * rather than a dead app. Starting a download there still cannot work, and still says so.
     */
    private val scheduler by schedulerProvider

    private val activeStates = DownloadState.entries.filter { it.isActive }

    /** What a reader may stop: a file part-written is not one of them - see [DownloadState.canPause]. */
    private val pausableStates = DownloadState.entries.filter { it.canPause }
    /** Waiting for a connection counts: resuming it tries again now, as the old app did. */
    private val resumableStates = listOf(DownloadState.Paused, DownloadState.Failed, DownloadState.WaitingForNetwork)

    override fun observeDownloads(): Flow<List<DownloadModel>> =
        dao.observeAll().map { rows -> rows.map(DownloadEntity::toModel) }.distinctUntilChanged()
            .endingOnFailure("The downloads could not be read")

    override fun observeDownload(id: Long): Flow<DownloadModel?> =
        dao.observe(id).map { it?.toModel() }.distinctUntilChanged()
            .endingOnFailure("A download could not be read")

    override suspend fun enqueue(request: DownloadRequest): Long = withContext(ioDispatcher) {
        // Refused here rather than downloaded and then lost: without a writable folder the file
        // cannot be saved however well it downloads, and the host can act on the reason.
        storage.writeRefusalOrNull()?.let { throw it }
        val (directory, fileName) = storage.placeFor(request)
        val id = dao.insert(
            DownloadEntity(
                title = request.title.take(Download.MAX_STORED_TITLE_LENGTH),
                fileName = fileName,
                directoryPath = directory.path,
                sourceUrl = request.sourceUrl.take(Download.MAX_STORED_URL_LENGTH),
                mediaUrl = request.mediaUrl,
                audioUrl = request.audioUrl?.takeIf { it.isHttpUrl() && it.length <= Download.MAX_STORED_URL_LENGTH },
                // Page-supplied: a row past the cursor window's 2 MB can never be read back.
                headers = request.headers.entries
                    .filter { (name, value) -> name.length <= Download.MAX_HEADER_NAME_LENGTH && value.length <= Download.MAX_STORED_URL_LENGTH }
                    .take(Download.MAX_STORED_HEADERS)
                    .associate { it.key to it.value },
                // An image link or a small inline image; not a stream, and not a page's megabyte data: url.
                thumbnailUrl = request.thumbnailUrl?.takeIf { it.isUsableThumbnail() },
                type = request.type,
                qualityLabel = request.qualityLabel,
                isStream = request.mediaUrl.isHlsPlaylistUrl(),
                singleConnection = listOf(request.sourceUrl, request.mediaUrl)
                    .any { it.normalizedHost()?.isUnderAnyOf(Download.SINGLE_CONNECTION_HOSTS) == true },
                collectionTitle = request.collectionTitle?.takeIf { it.isNotBlank() },
                state = if (request.startPaused) DownloadState.Paused else DownloadState.Queued,
                totalBytes = request.expectedSizeBytes?.takeIf { it > 0 },
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        if (!request.startPaused) startAll(listOf(id), replace = false)
        id
    }

    /**
     * Starts the job, and where there is no connection says so straight away. The job waits for one
     * before it runs, so nothing else would be shown until the connection came back - and a
     * download asked for and then silent reads as a download that never started. The worker clears
     * this the moment it runs.
     *
     * Reached through [startAll], which is what answers for the row when the device cannot run a
     * download at all; on its own this only starts one.
     */
    private suspend fun startAndSayIfItMustWait(id: Long, replace: Boolean = false) {
        scheduler.start(id, replace = replace)
        if (network.hasConnection()) return
        val entity = dao.get(id) ?: return
        notifier.showWaitingForNetwork(entity)
    }

    /**
     * Starts [ids], and where the device cannot run a download at all says so on every one.
     *
     * [DownloadException.SchedulerUnavailable] can only come from the scheduler being built, which
     * happens once in a process, so only the first start here can raise it - nothing in [ids] was
     * scheduled, and the caller has moved all of them to [DownloadState.Queued] in a single
     * statement. Each therefore says why rather than sitting in Queued for ever with nothing to
     * run it, which is what a reader on such a device was left looking at.
     */
    private suspend fun startAll(ids: List<Long>, replace: Boolean) {
        try {
            ids.forEach { startAndSayIfItMustWait(it, replace = replace) }
        } catch (e: DownloadException.SchedulerUnavailable) {
            ids.forEach { failAsUnschedulable(it) }
            throw e
        }
    }

    /** Says on the row what no job will ever say: this device cannot run the download. */
    private suspend fun failAsUnschedulable(id: Long) {
        dao.countFailure(id, Download.SCHEDULER_UNAVAILABLE_MESSAGE)
        dao.moveState(id, from = activeStates, state = DownloadState.Failed)
        notifier.cancel(id)
    }

    /**
     * Stops the job for [id], where there can be one to stop.
     *
     * A scheduler that cannot be built has never scheduled anything in this process, so there is
     * nothing here to stop and nothing to report. Starting is the opposite - a download the caller
     * asked for and did not get - but failing on the way *out* leaves the caller's own work half
     * done: [delete] has removed the row by this point, and a failure here left the files on disk,
     * the notification up, and a host told that a download it can no longer see is still there.
     */
    private fun stopWhatCouldBeRunning(id: Long) {
        try {
            scheduler.stop(id)
        } catch (e: DownloadException.SchedulerUnavailable) {
            Log.w(Download.LOG_TAG, "Nothing to stop for download $id", e)
        }
    }

    override suspend fun pause(id: Long): Result<Unit> = attempt {
        if (dao.moveState(id, from = pausableStates, state = DownloadState.Paused) > 0) {
            stopWhatCouldBeRunning(id)
            notifier.cancel(id)
            Result.success(Unit)
        } else {
            refusal(id, action = "pause")
        }
    }

    override suspend fun resume(id: Long): Result<Unit> = attempt {
        if (dao.requeue(id, from = resumableStates) > 0) {
            startAll(listOf(id), replace = true)
            Result.success(Unit)
        } else {
            refusal(id, action = "resume")
        }
    }

    /**
     * A whole collection at once. The ids are read first, then moved in one statement, so a
     * download that finishes or is deleted in between is simply not among them - and the scheduler
     * is told about each one, which is what actually stops the work.
     */
    override suspend fun pauseCollection(title: String): Result<Int> = attemptCounting {
        val ids = dao.idsInCollection(title, pausableStates)
        val moved = dao.moveCollection(title, from = pausableStates, state = DownloadState.Paused)
        ids.forEach { id ->
            stopWhatCouldBeRunning(id)
            notifier.cancel(id)
        }
        Result.success(moved)
    }

    override suspend fun resumeCollection(title: String): Result<Int> = attemptCounting {
        val ids = dao.idsInCollection(title, resumableStates)
        val moved = dao.requeueCollection(title, from = resumableStates)
        startAll(ids, replace = true)
        Result.success(moved)
    }

    override suspend fun delete(id: Long, deleteFile: Boolean): Result<Unit> = attempt {
        val entity = dao.get(id) ?: return@attempt Result.failure(DownloadException.NotFound(id))
        // Two deletes at once: only the one that removed the row cleans up after it.
        if (dao.delete(id) == 0) return@attempt Result.failure(DownloadException.NotFound(id))
        stopWhatCouldBeRunning(id)
        notifier.cancel(id)
        storage.deleteWorkFiles(entity)
        if (deleteFile && entity.state == DownloadState.Completed) entity.file.delete()
        Result.success(Unit)
    }

    /** [attempt] for an action that answers with a count rather than nothing. */
    private suspend fun attemptCounting(action: suspend () -> Result<Int>): Result<Int> =
        withContext(ioDispatcher) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Runs an action off the main thread; a database or scheduler error becomes its failure. */
    private suspend fun attempt(action: suspend () -> Result<Unit>): Result<Unit> = withContext(ioDispatcher) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Why a conditional change did not happen: no such download, or not in a state it applies to. */
    private suspend fun refusal(id: Long, action: String): Result<Unit> {
        val state = dao.get(id)?.state ?: return Result.failure(DownloadException.NotFound(id))
        return Result.failure(DownloadException.InvalidState(id, state, action))
    }

    override suspend fun restoreUnfinished() = withContext(ioDispatcher) {
        val unfinished = dao.getInStates(activeStates).map { it.id }
        try {
            // Not startAll: at app start with no connection that would raise a notification for
            // every row, and a restore is not something a reader asked for just now.
            unfinished.forEach { scheduler.start(it, replace = false) }
        } catch (e: DownloadException.SchedulerUnavailable) {
            // Rows this module left in Queued on such a device before it knew to refuse: the row
            // went in and the app died on the way to the job. They would be restored at every
            // start, raise this again, and read as downloads about to begin. None can begin, so
            // each says so - and the clean-up below still runs, which it did not while this came
            // out of here as an Error.
            Log.w(Download.LOG_TAG, "No scheduler to restore ${unfinished.size} download(s) with", e)
            unfinished.forEach { failAsUnschedulable(it) }
        }
        // And the pieces of downloads that have no row any more - a schema change starts the
        // table again, and what they were writing would otherwise sit there for ever.
        storage.deleteWorkFilesExcept(dao.allIds().toSet())
    }
}
