package com.markhoor.mediadownloader.domain.models

/** Where a download is in its life. */
enum class DownloadState {
    /** Waiting for its turn, or for the system to run it. */
    Queued,
    Downloading,
    Paused,

    /** Lost its connection; carries on by itself once one is back. */
    WaitingForNetwork,
    Completed,
    Failed;

    /** Still on its way to a file: not paused, finished or given up. */
    val isActive: Boolean get() = this == Queued || this == Downloading || this == WaitingForNetwork
}

/**
 * One download, as the host shows it.
 *
 * @param filePath where the file is, or will be once it completes.
 * @param totalBytes the full size when known; a stream's is an estimate that firms up as it goes.
 * @param errorMessage why the last attempt failed, while it is [DownloadState.Failed] or retrying.
 * @param isMediaGone the download failed because the media was not there to take - a private or
 *   deleted post, a link already spent - rather than because something went wrong on the way.
 *   Trying again gets the same answer, so a screen should say so instead of offering a retry.
 */
data class DownloadModel(
    val id: Long,
    val title: String,
    val fileName: String,
    val filePath: String,
    val sourceUrl: String,
    val thumbnailUrl: String?,
    val type: MediaType,
    val qualityLabel: String,
    val state: DownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val errorMessage: String?,
    val createdAtMillis: Long,
    val isMediaGone: Boolean = false,
) {
    /** 0-100, or `null` while the size is unknown. */
    val progressPercent: Int?
        get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes * 100 / it).toInt().coerceIn(0, 100) }
}

/**
 * What to download.
 *
 * @param mediaUrl the file or HLS playlist.
 * @param sourceUrl the page it was found on; decides the folder and is checked against the block list.
 * @param fileName the name to save under, without extension; the title is used when `null`.
 * @param startPaused add it paused, to be started with `resume` - e.g. downloads carried over
 *   from an older version of the host app that the user had paused.
 * @param siteFolder the folder under `Websites/` to save in, when the host keeps a kind of media
 *   apart (its own Shorts); by default the folder of [sourceUrl]'s site.
 * @param expectedSizeBytes the size the user was shown before downloading - for a stream, an
 *   estimate. Progress starts from it, so the download and the sheet show the same number until
 *   the real size is known.
 */
data class DownloadRequest(
    val mediaUrl: String,
    val type: MediaType,
    val title: String,
    val sourceUrl: String,
    val thumbnailUrl: String? = null,
    val qualityLabel: String = "",
    val audioUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val fileName: String? = null,
    val startPaused: Boolean = false,
    val siteFolder: String? = null,
    val expectedSizeBytes: Long? = null,
) {
    companion object {
        /** The request for one of [media]'s qualities. */
        fun of(
            media: MediaModel,
            quality: MediaQualityModel,
            fileName: String? = null,
            siteFolder: String? = null,
        ) = DownloadRequest(
            mediaUrl = quality.url,
            type = quality.type,
            title = media.title,
            sourceUrl = media.sourceUrl,
            thumbnailUrl = media.thumbnailUrl,
            qualityLabel = quality.label,
            audioUrl = quality.audioUrl,
            headers = quality.headers,
            fileName = fileName,
            siteFolder = siteFolder,
            expectedSizeBytes = quality.sizeBytes,
        )
    }
}
