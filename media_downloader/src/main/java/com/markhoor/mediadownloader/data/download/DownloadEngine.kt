package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.core.Constants.Scratch
import com.markhoor.mediadownloader.core.isSmallPlaylistFile
import com.markhoor.mediadownloader.core.moveTo
import java.io.File
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
    private val remuxer: StreamRemuxer,
    private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun download(task: DownloadTask, meter: ProgressMeter) = withContext(ioDispatcher) {
        task.workDir.mkdirs()
        task.output.parentFile?.mkdirs()
        when {
            task.isStream -> hls.download(task, meter)
            task.audioUrl != null -> downloadWithSound(task, meter)
            else -> {
                direct.download(task, meter)
                // A stream whose url did not say so: what arrived is its playlist, not the media.
                if (task.output.isSmallPlaylistFile()) {
                    task.output.delete()
                    hls.download(task.copy(isStream = true), meter)
                }
            }
        }
    }

    /**
     * A file whose sound is a second file. Sites that offer a resolution above their lowest tend to
     * keep the picture and the sound apart - YouTube does for everything over 360p - and a download
     * that takes only the first url saves a video nobody can hear. Both are fetched and joined the
     * same way a stream's tracks are.
     *
     * The sound is allowed to fail: a silent video is worth more than no video, so anything short of
     * both tracks arriving and joining leaves the picture as the file.
     */
    private suspend fun downloadWithSound(task: DownloadTask, meter: ProgressMeter) {
        val sound = task.audioUrl ?: return direct.download(task, meter)
        val videoFile = File(task.workDir, Scratch.VIDEO_TRACK)
        val audioFile = File(task.workDir, Scratch.AUDIO_TRACK)
        direct.download(task.copy(audioUrl = null, output = videoFile), meter)
        // The sound gets a meter of its own, folded in once it is here. A direct download starts
        // its meter from what is on disk for the file it is fetching, so sharing one would have the
        // reader watch the progress fall back to nothing the moment the picture finished.
        val soundMeter = ProgressMeter()
        val gotSound = runCatching {
            direct.download(task.copy(url = sound, audioUrl = null, output = audioFile), soundMeter)
        }.isSuccess && audioFile.length() > 0
        meter.add(soundMeter.downloadedBytes)
        val joined = gotSound &&
            runCatching { remuxer.remux(videoFile, audioFile, task.output) }.isSuccess &&
            task.output.length() > 0
        if (joined) videoFile.delete() else videoFile.moveTo(task.output)
        audioFile.delete()
    }
}
