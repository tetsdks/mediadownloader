package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.core.Constants.Scratch
import com.markhoor.mediadownloader.core.isSmallPlaylistFile
import com.markhoor.mediadownloader.core.moveTo
import com.markhoor.mediadownloader.data.network.HttpStatusException
import com.markhoor.mediadownloader.data.network.isMediaGone
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Media that cannot be saved however often it is tried - an encryption the module cannot undo, a
 * playlist with nothing in it. The download fails at once, with this message, instead of retrying.
 */
internal class UnusableMediaException(message: String) : IOException(message)

/**
 * Runs [block]; `null` when what it asked for is not there to take - a private post, a spent link,
 * media this module cannot save - and anything else passed on.
 *
 * Only *gone* answers here. A dropped connection and a cancelled run are not answers at all: they
 * go to the caller, so the run is stopped or tried again rather than finished with half of what it
 * came for and called complete.
 */
internal suspend fun <T> unlessMediaIsGone(block: suspend () -> T): T? =
    try {
        block()
    } catch (_: UnusableMediaException) {
        null
    } catch (refused: HttpStatusException) {
        if (refused.isMediaGone()) null else throw refused
    }

/** Runs one download to its file: a stream through [hls], anything else through [direct]. */
internal class DownloadEngine(
    private val direct: DirectFileDownloader,
    private val hls: HlsStreamDownloader,
    private val remuxer: StreamRemuxer,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Runs [task] to its file.
     *
     * [onFinishing] is called once there is nothing left to fetch and the file is being written -
     * a video and its sound being joined, a stream's pieces being stitched. It can be a long
     * moment on a long video, and progress has nothing left to say in it, so the caller is told
     * rather than left showing "100%" beside a pause button.
     */
    suspend fun download(
        task: DownloadTask,
        meter: ProgressMeter,
        onFinishing: suspend () -> Unit = {},
    ) = withContext(ioDispatcher) {
        task.workDir.mkdirs()
        task.output.parentFile?.mkdirs()
        when {
            task.isStream -> hls.download(task, meter, onFinishing)
            task.audioUrl != null -> downloadWithSound(task, meter, onFinishing)
            else -> {
                direct.download(task, meter)
                // A stream whose url did not say so: what arrived is its playlist, not the media.
                if (task.output.isSmallPlaylistFile()) {
                    task.output.delete()
                    hls.download(task.copy(isStream = true), meter, onFinishing)
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
     * Sound that is gone for good - refused by its server, or unusable - leaves the picture a
     * download of its own: a silent video is worth more than no video. Sound lost to a dropped
     * connection fails the run instead, so it is tried again, exactly as the stream downloader
     * reads the same two cases. `runCatching` here read them as one, and it swallowed cancellation
     * with them: a reader pausing between the two tracks was taken for sound that would never
     * come, so the picture was moved out of the work dir as the finished file and the next run
     * found no picture there and fetched all of it again.
     */
    private suspend fun downloadWithSound(
        task: DownloadTask,
        meter: ProgressMeter,
        onFinishing: suspend () -> Unit = {},
    ) {
        val sound = task.audioUrl ?: return direct.download(task, meter)
        val videoFile = File(task.workDir, Scratch.VIDEO_TRACK)
        val audioFile = File(task.workDir, Scratch.AUDIO_TRACK)
        direct.download(task.forTrack(Scratch.VIDEO_DIR, videoFile), meter)
        // The sound gets a meter of its own, folded in once it is here. A direct download starts
        // its meter from what is on disk for the file it is fetching, so sharing one would have the
        // reader watch the progress fall back to nothing the moment the picture finished.
        val soundMeter = ProgressMeter()
        val gotSound = unlessMediaIsGone {
            direct.download(task.forTrack(Scratch.AUDIO_DIR, audioFile, sound), soundMeter)
        } != null && audioFile.length() > 0
        meter.add(soundMeter.downloadedBytes)
        // Both tracks are here; the join is what is left, and it counts no bytes.
        onFinishing()
        val joined = gotSound &&
            runCatching { remuxer.remux(videoFile, audioFile, task.output) }.isSuccess &&
            task.output.length() > 0
        if (joined) videoFile.delete() else videoFile.moveTo(task.output)
        audioFile.delete()
    }

    /**
     * One of the two tracks as a download of its own: its file, and scratch space of its own under
     * [dir] to build it in.
     *
     * A dir each because both tracks are fetched in parallel parts, and the parts of one are named
     * exactly like the parts of the other - `part-0` beside one record of the length they were cut
     * for. Sharing a dir, each run's picture step deleted whatever the sound had fetched, so the
     * sound began again from nothing however often the download was retried.
     */
    private fun DownloadTask.forTrack(dir: String, file: File, from: String = url): DownloadTask =
        copy(url = from, audioUrl = null, workDir = File(workDir, dir).apply { mkdirs() }, output = file)
}
