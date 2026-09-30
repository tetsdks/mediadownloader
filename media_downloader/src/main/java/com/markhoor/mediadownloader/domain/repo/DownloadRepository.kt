package com.markhoor.mediadownloader.domain.repo

import com.markhoor.mediadownloader.domain.models.DownloadException
import com.markhoor.mediadownloader.domain.models.DownloadModel
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import kotlinx.coroutines.flow.Flow

/**
 * The downloads and their progress. The database is the one source of truth, and every change
 * is a conditional write on it, so callers acting on the same download at once cannot both win.
 * Actions fail with a [DownloadException] saying why.
 */
internal interface DownloadRepository {

    /** Every download, newest first, updated as they progress. */
    fun observeDownloads(): Flow<List<DownloadModel>>

    /** One download, or `null` once it is deleted. */
    fun observeDownload(id: Long): Flow<DownloadModel?>

    /** Records [request] and starts it. Returns its id. */
    suspend fun enqueue(request: DownloadRequest): Long

    /** Pauses a queued, running or waiting download. */
    suspend fun pause(id: Long): Result<Unit>

    /** Pauses every download of one collection that can be paused; returns how many were. */
    suspend fun pauseCollection(title: String): Result<Int>

    /** Carries on every paused or failed download of one collection; returns how many were. */
    suspend fun resumeCollection(title: String): Result<Int>

    /** Carries on a paused download, or tries a failed one again. */
    suspend fun resume(id: Long): Result<Unit>

    /** Stops and forgets a download; with [deleteFile], a completed one's file goes too. */
    suspend fun delete(id: Long, deleteFile: Boolean): Result<Unit>

    /** Starts again whatever was running when the process last died and is not scheduled. */
    suspend fun restoreUnfinished()
}
