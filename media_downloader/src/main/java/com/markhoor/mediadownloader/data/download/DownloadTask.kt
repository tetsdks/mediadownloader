package com.markhoor.mediadownloader.data.download

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * One run of a download, as the engine sees it.
 *
 * @param isStream [url] is an HLS playlist.
 * @param singleConnection the site breaks when a file is fetched in parallel parts.
 * @param workDir private scratch space for parts and segments; kept between runs so a retry
 *   resumes, and deleted once the download completes.
 * @param output where the finished file is written. It sits beside the final file, under a
 *   hidden name, so publishing it is a rename and never a copy.
 */
internal data class DownloadTask(
    val url: String,
    val audioUrl: String?,
    val headers: Map<String, String>,
    val isStream: Boolean,
    val singleConnection: Boolean,
    val workDir: File,
    val output: File,
)

/**
 * How far a download has got. Written by every part or segment as it reads, read once a second
 * by whoever reports it - so reading costs the download nothing.
 *
 * It starts from what the download last reported, so a resumed run does not read 0 while it is
 * still asking the server for the file; the engine sets it from what is on disk once it knows.
 */
internal class ProgressMeter(startBytes: Long = 0, startTotal: Long? = null) {
    private val downloaded = AtomicLong(startBytes)

    @Volatile
    var totalBytes: Long? = startTotal

    val downloadedBytes: Long get() = downloaded.get()

    fun add(bytes: Long) {
        downloaded.addAndGet(bytes)
    }

    fun reset(bytes: Long = 0) {
        downloaded.set(bytes)
    }
}
