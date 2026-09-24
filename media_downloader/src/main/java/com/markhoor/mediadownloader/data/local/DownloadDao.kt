package com.markhoor.mediadownloader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.markhoor.mediadownloader.domain.models.DownloadState
import kotlinx.coroutines.flow.Flow

/**
 * State changes are conditional where two parties can race: the worker only starts a row that
 * is still waiting to run, and progress writes never touch the state, so a pause the user makes
 * mid-download is never overwritten by the worker's next progress tick.
 */
@Dao
internal interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state IN (:states)")
    suspend fun getInStates(states: List<DownloadState>): List<DownloadEntity>

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    /** Returns how many rows went: 0 when another caller deleted it first. */
    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("UPDATE downloads SET state = :state, errorMessage = :errorMessage WHERE id = :id")
    suspend fun setState(id: Long, state: DownloadState, errorMessage: String? = null)

    /** Moves [id] to [state] only while it is in one of [from]; returns whether it moved. */
    @Query("UPDATE downloads SET state = :state WHERE id = :id AND state IN (:from)")
    suspend fun moveState(id: Long, from: List<DownloadState>, state: DownloadState): Int

    @Query("UPDATE downloads SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes WHERE id = :id")
    suspend fun setProgress(id: Long, downloadedBytes: Long, totalBytes: Long?)

    @Query(
        "UPDATE downloads SET failedAttempts = failedAttempts + 1, errorMessage = :errorMessage " +
            "WHERE id = :id",
    )
    suspend fun countFailure(id: Long, errorMessage: String?)

    @Query(
        "UPDATE downloads SET state = :state, fileName = :fileName, downloadedBytes = :sizeBytes, " +
            "totalBytes = :sizeBytes, errorMessage = NULL WHERE id = :id",
    )
    suspend fun complete(id: Long, fileName: String, sizeBytes: Long, state: DownloadState = DownloadState.Completed)

    /** Puts [id] back in line with a clean slate, only while it is in one of [from]. */
    @Query(
        "UPDATE downloads SET state = :state, failedAttempts = 0, errorMessage = NULL " +
            "WHERE id = :id AND state IN (:from)",
    )
    suspend fun requeue(id: Long, from: List<DownloadState>, state: DownloadState = DownloadState.Queued): Int
}
