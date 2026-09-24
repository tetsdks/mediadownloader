package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.core.isSmallPlaylistFile
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Media that cannot be saved however often it is tried - an encryption the module cannot undo, a
 * playlist with nothing in it. The download fails at once, with this message, instead of retrying.
 */
internal class UnusableMediaException(message: String) : IOException(message)

/** Runs one download to its file: a stream through [hls], anything else through [direct]. */
internal class DownloadEngine(
    private val direct: DirectFileDownloader,
    private val hls: HlsStreamDownloader,
    private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun download(task: DownloadTask, meter: ProgressMeter) = withContext(ioDispatcher) {
        task.workDir.mkdirs()
        task.output.parentFile?.mkdirs()
        if (task.isStream) {
            hls.download(task, meter)
        } else {
            direct.download(task, meter)
            // A stream whose url did not say so: what arrived is its playlist, not the media.
            if (task.output.isSmallPlaylistFile()) {
                task.output.delete()
                hls.download(task.copy(isStream = true), meter)
            }
        }
    }
}
