package com.markhoor.mediadownloader.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.domain.models.DownloadModel
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.domain.models.MediaType
import java.io.File

/**
 * A download's row. Everything the worker needs to start it again after the process dies is
 * here - the media url, its sound, its headers - so a resumed download is the same download.
 *
 * @param directoryPath the folder the file is saved into; [fileName] is its name there.
 * @param isStream an HLS playlist rather than a file.
 * @param failedAttempts failures other than a lost connection, counted towards giving up.
 */
@Entity(tableName = "downloads")
internal data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val fileName: String,
    val directoryPath: String,
    val sourceUrl: String,
    val mediaUrl: String,
    val audioUrl: String?,
    val headers: Map<String, String>,
    val thumbnailUrl: String?,
    val type: MediaType,
    val qualityLabel: String,
    val isStream: Boolean,
    val singleConnection: Boolean,
    val state: DownloadState,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
    val failedAttempts: Int = 0,
    val createdAtMillis: Long,
) {
    val file: File get() = File(directoryPath, fileName)

    fun toModel() = DownloadModel(
        id = id,
        title = title,
        fileName = fileName,
        filePath = file.path,
        sourceUrl = sourceUrl,
        thumbnailUrl = thumbnailUrl,
        type = type,
        qualityLabel = qualityLabel,
        state = state,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        // The marker says why it failed; the host is given the reason without it.
        errorMessage = errorMessage?.removePrefix(Download.MEDIA_GONE_MARKER),
        createdAtMillis = createdAtMillis,
        isMediaGone = errorMessage?.startsWith(Download.MEDIA_GONE_MARKER) == true,
    )
}
