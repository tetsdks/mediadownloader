package com.markhoor.mediadownloader.data.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.markhoor.mediadownloader.core.Constants.Download
import java.util.concurrent.TimeUnit

/**
 * Hands downloads to WorkManager: one unique job per download, run only while connected, retried
 * with a growing pause. A lost connection therefore needs no watcher of its own - the job simply
 * waits for one.
 */
internal class DownloadScheduler(private val workManager: WorkManager) {

    /** [replace] restarts a job that exists; otherwise one already queued or running is kept. */
    fun start(id: Long, replace: Boolean) {
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(workName(id), policy, request(id))
    }

    private fun request(id: Long) = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(workDataOf(Download.KEY_DOWNLOAD_ID to id))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Download.BACKOFF_SECONDS, TimeUnit.SECONDS)
        .addTag(Download.WORK_TAG)
        .build()

    /**
     * Runs [id] again once there is a connection, as a fresh job after the one running now. A
     * retry would wait out a growing backoff each time the connection drops - minutes after the
     * signal is back - while a fresh job starts the moment it is.
     */
    fun continueWhenOnline(id: Long) {
        workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.APPEND_OR_REPLACE, request(id))
    }

    fun stop(id: Long) {
        workManager.cancelUniqueWork(workName(id))
    }

    private fun workName(id: Long) = Download.WORK_NAME_PREFIX + id
}
