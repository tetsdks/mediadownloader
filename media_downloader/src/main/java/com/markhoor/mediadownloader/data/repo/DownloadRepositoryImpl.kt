package com.markhoor.mediadownloader.data.repo

import com.markhoor.mediadownloader.core.Constants.Download
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
    private val scheduler: DownloadScheduler,
    private val storage: DownloadStorage,
    private val notifier: DownloadNotifier,
    private val network: NetworkStatus,
    private val ioDispatcher: CoroutineDispatcher,
) : DownloadRepository {

    private val activeStates = DownloadState.entries.filter { it.isActive }
    /** Waiting for a connection counts: resuming it tries again now, as the old app did. */
    private val resumableStates = listOf(DownloadState.Paused, DownloadState.Failed, DownloadState.WaitingForNetwork)

    override fun observeDownloads(): Flow<List<DownloadModel>> =
        dao.observeAll().map { rows -> rows.map(DownloadEntity::toModel) }.distinctUntilChanged()

    override fun observeDownload(id: Long): Flow<DownloadModel?> =
        dao.observe(id).map { it?.toModel() }.distinctUntilChanged()

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
                state = if (request.startPaused) DownloadState.Paused else DownloadState.Queued,
                totalBytes = request.expectedSizeBytes?.takeIf { it > 0 },
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        if (!request.startPaused) startAndSayIfItMustWait(id)
        id
    }

    /**
     * Starts the job, and where there is no connection says so straight away. The job waits for one
     * before it runs, so nothing else would be shown until the connection came back - and a
     * download asked for and then silent reads as a download that never started. The worker clears
     * this the moment it runs.
     */
    private suspend fun startAndSayIfItMustWait(id: Long, replace: Boolean = false) {
        scheduler.start(id, replace = replace)
        if (network.hasConnection()) return
        val entity = dao.get(id) ?: return
        notifier.showWaitingForNetwork(entity)
    }

    override suspend fun pause(id: Long): Result<Unit> = attempt {
        if (dao.moveState(id, from = activeStates, state = DownloadState.Paused) > 0) {
            scheduler.stop(id)
            notifier.cancel(id)
            Result.success(Unit)
        } else {
            refusal(id, action = "pause")
        }
    }

    override suspend fun resume(id: Long): Result<Unit> = attempt {
        if (dao.requeue(id, from = resumableStates) > 0) {
            startAndSayIfItMustWait(id, replace = true)
            Result.success(Unit)
        } else {
            refusal(id, action = "resume")
        }
    }

    override suspend fun delete(id: Long, deleteFile: Boolean): Result<Unit> = attempt {
        val entity = dao.get(id) ?: return@attempt Result.failure(DownloadException.NotFound(id))
        // Two deletes at once: only the one that removed the row cleans up after it.
        if (dao.delete(id) == 0) return@attempt Result.failure(DownloadException.NotFound(id))
        scheduler.stop(id)
        notifier.cancel(id)
        storage.deleteWorkFiles(entity)
        if (deleteFile && entity.state == DownloadState.Completed) entity.file.delete()
        Result.success(Unit)
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
        dao.getInStates(activeStates).forEach { scheduler.start(it.id, replace = false) }
    }
}
