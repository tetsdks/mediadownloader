package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.core.Constants.Scratch
import com.markhoor.mediadownloader.core.isPlaylistEntryUrl
import com.markhoor.mediadownloader.core.isSubtitleUrl
import com.markhoor.mediadownloader.core.joinInto
import com.markhoor.mediadownloader.core.moveTo
import com.markhoor.mediadownloader.data.hls.HlsKeyDto
import com.markhoor.mediadownloader.data.hls.HlsMediaPlaylistDto
import com.markhoor.mediadownloader.data.hls.HlsPlaylistParser
import com.markhoor.mediadownloader.data.hls.HlsSegmentDto
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.network.HttpStatusException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Downloads an HLS stream into one file.
 *
 * A master playlist is narrowed to its best encode; a playlist of playlists is followed down. The
 * pieces are fetched in parallel, each finished piece kept under its index so a retry fetches only
 * what is missing, then joined behind the `#EXT-X-MAP` init piece when there is one. Where the
 * stream keeps its sound apart, the sound is fetched the same way. Finally picture and sound are
 * remuxed into a plain MP4; if that fails the joined picture is kept as it is - it plays, it is
 * only less tidy.
 *
 * AES-128 pieces are decrypted as they arrive. A track's pieces are deleted once joined, so a
 * stream needs about twice its size free at most, not three times.
 */
internal class HlsStreamDownloader(
    private val fetcher: HttpFileFetcher,
    private val playlistFetcher: HttpFetcher,
    private val remuxer: StreamRemuxer,
    private val maxParallelSegments: Int = Download.MAX_PARALLEL_SEGMENTS,
) {

    suspend fun download(task: DownloadTask, meter: ProgressMeter) {
        val (video, audioUrl) = resolveMediaPlaylist(task.url, task.audioUrl, task.headers)
        // Sound that is gone for good - refused by its server, or unusable - leaves the picture a
        // download of its own. Sound that could not be fetched this time, a dropped connection,
        // fails the run so it is tried again, rather than finished silent and called complete.
        val audio = audioUrl?.let { url ->
            unlessGone { resolveMediaPlaylist(url, audioUrl = null, task.headers).first }
        }

        val progress = StreamProgress(video, audio, meter)
        val videoFile = downloadTrack(video, File(task.workDir, Scratch.VIDEO_DIR), task.headers, progress)
        val audioFile = audio?.let {
            unlessGone { downloadTrack(it, File(task.workDir, Scratch.AUDIO_DIR), task.headers, progress) }
        }

        val remuxed = catching { remuxer.remux(videoFile, audioFile, task.output) } != null &&
            task.output.length() > 0
        if (!remuxed) videoFile.moveTo(task.output)
    }

    /**
     * The media playlist behind [url], with the sound playlist that goes with it. A master is
     * narrowed to its highest bandwidth encode, whose sound is used unless one was given.
     */
    private suspend fun resolveMediaPlaylist(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
    ): Pair<HlsMediaPlaylistDto, String?> {
        var playlistUrl = url
        var soundUrl = audioUrl
        repeat(Download.MAX_PLAYLIST_HOPS + 1) {
            val text = playlistFetcher.getText(playlistUrl, headers, requireSuccess = true).getOrThrow()
            if (HlsPlaylistParser.isMaster(text)) {
                val best = HlsPlaylistParser.parseMaster(text, playlistUrl)
                    .maxByOrNull { it.averageBandwidth ?: it.bandwidth ?: 0L }
                    ?: throw UnusableMediaException("The master playlist lists no encodes")
                playlistUrl = best.url
                soundUrl = soundUrl ?: best.audioUrl
                return@repeat
            }
            val playlist = HlsPlaylistParser.parseMedia(text, playlistUrl)
            playlist.unsupportedEncryption?.let { method ->
                throw UnusableMediaException("This stream is protected ($method) and cannot be saved")
            }
            val segments = playlist.segments.filterNot { it.url.isSubtitleUrl() }
            if (segments.isEmpty()) throw UnusableMediaException("No media in this playlist")
            // Every entry another playlist: the encodes are listed smallest first.
            if (segments.all { it.byteRange == null && it.url.isPlaylistEntryUrl() }) {
                playlistUrl = segments.last().url
                return@repeat
            }
            return playlist.copy(segments = segments) to soundUrl
        }
        throw UnusableMediaException("Playlists nest deeper than ${Download.MAX_PLAYLIST_HOPS} levels")
    }

    /** Fetches one encode's pieces into [dir] and joins them into a single file there. */
    private suspend fun downloadTrack(
        playlist: HlsMediaPlaylistDto,
        dir: File,
        headers: Map<String, String>,
        progress: StreamProgress,
    ): File {
        dir.mkdirs()
        val joined = File(dir, Scratch.JOINED_NAME)
        val joinedMarker = File(dir, Scratch.JOINED_MARKER)
        if (joinedMarker.exists() && joined.length() > 0) {
            // Joined on an earlier run, its pieces already deleted.
            progress.trackAlreadyJoined(joined.length(), playlist.segments.size)
            return joined
        }
        val keys = StreamKeys(dir, headers)
        val init = playlist.initSegment?.let {
            fetchSegment(it, File(dir, Scratch.INIT_NAME), headers, progress, keys, isInit = true)
        }
        val segments = playlist.segments
        // A fixed set of workers taking the next piece, not a coroutine per piece: a stream of
        // hours has thousands of them, and each waiting coroutine is memory.
        val pieces = arrayOfNulls<File>(segments.size)
        val nextIndex = AtomicInteger(0)
        coroutineScope {
            repeat(minOf(maxParallelSegments, segments.size)) {
                launch {
                    while (true) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= segments.size) break
                        pieces[index] = fetchSegment(segments[index], File(dir, "$index${Scratch.DONE_SUFFIX}"), headers, progress, keys)
                    }
                }
            }
        }
        val fetched = pieces.filterNotNull()
        if (fetched.size != segments.size) throw IOException("${segments.size - fetched.size} stream pieces are missing")
        val parts = listOfNotNull(init) + fetched
        parts.joinInto(joined)
        joinedMarker.createNewFile()
        parts.forEach { it.delete() }
        keys.deleteFiles()
        return joined
    }

    /**
     * One piece, into [done]. A piece already there from an earlier run is counted and kept. It is
     * written under a temporary name first, so a half-written piece is never taken for a whole one.
     * A piece that fails is tried again a couple of times before the download fails with it.
     */
    private suspend fun fetchSegment(
        segment: HlsSegmentDto,
        done: File,
        headers: Map<String, String>,
        progress: StreamProgress,
        keys: StreamKeys,
        isInit: Boolean = false,
    ): File {
        if (done.length() > 0) {
            progress.pieceDone(done.length(), alreadyCounted = false, isInit = isInit)
            return done
        }
        val partial = File(done.parentFile, done.name + Scratch.PARTIAL_SUFFIX)
        var attempt = 1
        while (true) {
            partial.delete()
            var written = 0L
            try {
                fetcher.fetchInto(partial, segment.url, headers, range = segment.byteRange) { bytes ->
                    written += bytes
                    progress.meter.add(bytes)
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.meter.add(-written)
                if (attempt >= Download.SEGMENT_ATTEMPTS) throw e
                delay(Download.SEGMENT_RETRY_DELAY_MS * attempt)
                attempt++
            }
        }
        val key = segment.key
        if (key == null) {
            partial.moveTo(done)
        } else {
            val decrypted = File(done.parentFile, done.name + Scratch.DECRYPTED_SUFFIX)
            decryptAes128(partial, decrypted, keys.bytesOf(key.url), ivOf(key, segment.sequence))
            partial.delete()
            decrypted.moveTo(done)
        }
        progress.pieceDone(done.length(), alreadyCounted = true, isInit = isInit)
        return done
    }

    /**
     * The keys of one track, each fetched once however many pieces share it. Pieces are fetched by
     * several workers at once, so the first to need a key fetches it and the others wait for it.
     */
    private inner class StreamKeys(private val dir: File, private val headers: Map<String, String>) {
        private val lock = Mutex()
        private val fetched = HashMap<String, ByteArray>()
        private val files = mutableListOf<File>()

        suspend fun bytesOf(url: String): ByteArray = lock.withLock {
            fetched[url] ?: run {
                val file = File(dir, Scratch.KEY_PREFIX + fetched.size)
                files += file
                fetcher.fetchInto(file, url, headers) {}
                val bytes = file.readBytes()
                if (bytes.size != Download.AES_KEY_BYTES) {
                    throw UnusableMediaException("The stream's key is ${bytes.size} bytes, not ${Download.AES_KEY_BYTES}")
                }
                bytes.also { fetched[url] = it }
            }
        }

        fun deleteFiles() {
            files.forEach { it.delete() }
        }
    }

    /** The key's own IV, else the piece's sequence number as a 16-byte big-endian number. */
    private fun ivOf(key: HlsKeyDto, sequence: Long): ByteArray {
        val hex = key.ivHex?.removePrefix("0x")?.removePrefix("0X")
        if (hex != null && hex.length <= 2 * Download.AES_KEY_BYTES && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            val padded = hex.padStart(2 * Download.AES_KEY_BYTES, '0')
            return ByteArray(Download.AES_KEY_BYTES) { index -> padded.substring(2 * index, 2 * index + 2).toInt(16).toByte() }
        }
        return ByteBuffer.allocate(Download.AES_KEY_BYTES).putLong(Download.AES_KEY_BYTES / 2, sequence).array()
    }

    private fun decryptAes128(source: File, target: File, key: ByteArray, iv: ByteArray) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        source.inputStream().buffered(Download.BUFFER_BYTES).use { input ->
            CipherInputStream(input, cipher).use { decrypted ->
                target.outputStream().use { output -> decrypted.copyTo(output, Download.BUFFER_BYTES) }
            }
        }
    }

    /** Runs [block], turning any failure but cancellation into `null`. */
    private suspend fun <T> catching(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** Runs [block]; `null` when what it reads is gone for good, any other failure passed on. */
    private suspend fun <T> unlessGone(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: UnusableMediaException) {
            null
        } catch (e: HttpStatusException) {
            if (e.status in 400..499) null else throw e
        }

    /**
     * A stream states its total only when every piece's size is written in the playlist. Otherwise
     * it starts from the size the user was shown, and once pieces finish, the average finished
     * piece stands for the rest.
     *
     * Only finished pieces count, and the init piece is not one: it is a header of a kilobyte or
     * two, and counting it as the first of twelve pieces put a 1.4 kB header down as a 16 kB
     * stream while eight real pieces were still arriving - "801 kB of 16 kB" on a dailymotion
     * video. The estimate never falls below what is on disk, so progress never reads past complete.
     */
    private class StreamProgress(video: HlsMediaPlaylistDto, audio: HlsMediaPlaylistDto?, val meter: ProgressMeter) {
        private val totalSegments = video.segments.size + (audio?.segments?.size ?: 0)
        private val statedTotal: Long? = if (audio == null) {
            video.statedBytes
        } else {
            video.statedBytes?.let { videoBytes -> audio.statedBytes?.let { videoBytes + it } }
        }
        private val expectedTotal: Long? = statedTotal ?: meter.totalBytes

        // Guarded by this object's monitor: pieces finish on several threads at once.
        private var doneSegments = 0
        private var doneSegmentBytes = 0L
        private var initBytes = 0L

        init {
            meter.reset()
            meter.totalBytes = expectedTotal
        }

        /** A track joined on an earlier run: its [bytes] and [count] pieces are all done. */
        @Synchronized
        fun trackAlreadyJoined(bytes: Long, count: Int) {
            meter.add(bytes)
            doneSegments += count
            doneSegmentBytes += bytes
            refreshEstimate()
        }

        @Synchronized
        fun pieceDone(bytes: Long, alreadyCounted: Boolean, isInit: Boolean) {
            if (!alreadyCounted) meter.add(bytes)
            if (isInit) {
                initBytes += bytes
            } else {
                doneSegments++
                doneSegmentBytes += bytes
            }
            refreshEstimate()
        }

        private fun refreshEstimate() {
            if (statedTotal != null) return
            val estimate = if (doneSegments > 0) {
                initBytes + doneSegmentBytes / doneSegments * totalSegments
            } else {
                expectedTotal
            }
            meter.totalBytes = estimate?.let { maxOf(it, meter.downloadedBytes) }
        }
    }
}
