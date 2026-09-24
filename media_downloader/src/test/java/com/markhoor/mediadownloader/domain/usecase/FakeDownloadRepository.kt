package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.domain.models.DownloadModel
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.repo.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Records what it is asked to do; [downloads] is what it reports. */
internal class FakeDownloadRepository : DownloadRepository {
    val downloads = MutableStateFlow<List<DownloadModel>>(emptyList())
    val enqueued = mutableListOf<DownloadRequest>()
    val actions = mutableListOf<String>()

    override fun observeDownloads(): Flow<List<DownloadModel>> = downloads

    override fun observeDownload(id: Long): Flow<DownloadModel?> = downloads.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun enqueue(request: DownloadRequest): Long {
        enqueued += request
        return enqueued.size.toLong()
    }

    /** What pause, resume and delete answer with. */
    var actionResult: Result<Unit> = Result.success(Unit)

    override suspend fun pause(id: Long): Result<Unit> {
        actions += "pause $id"
        return actionResult
    }

    override suspend fun resume(id: Long): Result<Unit> {
        actions += "resume $id"
        return actionResult
    }

    override suspend fun delete(id: Long, deleteFile: Boolean): Result<Unit> {
        actions += "delete $id $deleteFile"
        return actionResult
    }

    override suspend fun restoreUnfinished() {
        actions += "restore"
    }
}
