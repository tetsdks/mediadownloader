package com.markhoor.mediadownloader.data.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.markhoor.mediadownloader.core.Constants.Download
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Rewrites a joined stream as a plain MP4. An interface only so the stream downloader can be tested
 * on the JVM, where the platform's extractor and muxer do not run.
 */
internal fun interface StreamRemuxer {

    /**
     * Writes [video]'s tracks - and [audio]'s first soundtrack, when given, in place of any sound
     * [video] carries - into [output]. Throws when the sources cannot be read or written.
     */
    fun remux(video: File, audio: File?, output: File)
}

/**
 * [StreamRemuxer] on the platform's MediaExtractor and MediaMuxer: samples are copied, nothing is
 * decoded, so it runs at disk speed.
 *
 * Each track gets its own extractor, and samples are written in presentation order across all of
 * them so the muxer never has to hold a whole track back. Times are shifted so the file starts at
 * zero - a transport stream's clock starts wherever the broadcaster's did. Timed metadata and
 * subtitle tracks are left out; the MP4 muxer refuses them.
 */
internal class MediaMuxerRemuxer : StreamRemuxer {

    private class Track(val extractor: MediaExtractor, val format: MediaFormat) {
        var muxerIndex = -1
        var finished = false
    }

    override fun remux(video: File, audio: File?, output: File) {
        val tracks = mutableListOf<Track>()
        try {
            tracks += openTracks(video, takeVideo = true, takeAudio = audio == null)
            if (audio != null) tracks += openTracks(audio, takeVideo = false, takeAudio = true).take(1)
            if (tracks.none { mimeOf(it.format).startsWith(Download.MIME_VIDEO_PREFIX) }) throw IOException("No picture to remux")
            writeTracks(tracks, output)
        } finally {
            tracks.forEach { runCatching { it.extractor.release() } }
        }
    }

    private fun openTracks(source: File, takeVideo: Boolean, takeAudio: Boolean): List<Track> {
        val probe = MediaExtractor()
        val wanted = try {
            probe.setDataSource(source.path)
            (0 until probe.trackCount).filter { index ->
                val mime = mimeOf(probe.getTrackFormat(index))
                (takeVideo && mime.startsWith(Download.MIME_VIDEO_PREFIX)) || (takeAudio && mime.startsWith(Download.MIME_AUDIO_PREFIX))
            }
        } finally {
            probe.release()
        }
        return wanted.map { index ->
            val extractor = MediaExtractor()
            extractor.setDataSource(source.path)
            extractor.selectTrack(index)
            Track(extractor, extractor.getTrackFormat(index))
        }
    }

    private fun writeTracks(tracks: List<Track>, output: File) {
        output.delete()
        output.parentFile?.mkdirs()
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            tracks.forEach { it.muxerIndex = muxer.addTrack(it.format) }
            tracks.firstNotNullOfOrNull { rotationOf(it.format) }?.let(muxer::setOrientationHint)
            muxer.start()
            started = true

            val startUs = tracks.mapNotNull { it.extractor.sampleTime.takeIf { time -> time >= 0 } }.minOrNull() ?: 0L
            val buffer = ByteBuffer.allocate(tracks.maxOf { maxSampleSize(it.format) })
            val info = MediaCodec.BufferInfo()

            while (true) {
                val next = tracks.filterNot { it.finished }.minByOrNull { it.extractor.sampleTime } ?: break
                buffer.clear()
                val size = next.extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    next.finished = true
                    continue
                }
                val isKeyFrame = next.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(
                    0,
                    size,
                    (next.extractor.sampleTime - startUs).coerceAtLeast(0),
                    if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                )
                muxer.writeSampleData(next.muxerIndex, buffer, info)
                if (!next.extractor.advance()) next.finished = true
            }
            muxer.stop()
            started = false
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun mimeOf(format: MediaFormat): String = format.getString(MediaFormat.KEY_MIME).orEmpty()

    private fun rotationOf(format: MediaFormat): Int? =
        if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else null

    private fun maxSampleSize(format: MediaFormat): Int {
        val stated = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        // A sample bigger than the ceiling makes readSampleData throw, and the stream is kept unremuxed.
        return stated.coerceIn(Download.MIN_SAMPLE_BUFFER_BYTES, Download.MAX_SAMPLE_BUFFER_BYTES)
    }
}
