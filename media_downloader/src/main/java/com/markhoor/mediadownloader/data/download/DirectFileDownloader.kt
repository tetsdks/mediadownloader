package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.core.Constants.Scratch
import com.markhoor.mediadownloader.core.joinInto
import com.markhoor.mediadownloader.data.network.HttpStatusException
import io.ktor.http.HttpStatusCode
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Downloads a single file: in parallel parts when the server proves it serves ranges and the
 * file is worth splitting, else in one piece. Either way a retry picks up from the bytes already
 * on disk.
 */
internal class DirectFileDownloader(
    private val fetcher: HttpFileFetcher,
    private val maxParallelParts: Int = Download.MAX_PARALLEL_PARTS,
) {

    suspend fun download(task: DownloadTask, meter: ProgressMeter) {
        val remote = fetcher.probe(task.url, task.headers)
        remote.length?.let { meter.totalBytes = it }
        val length = remote.length

        if (!task.singleConnection && remote.servesRanges && length != null && length >= 2 * Download.MIN_PART_BYTES) {
            try {
                downloadInParts(task, length, meter)
                return
            } catch (_: RangeIgnoredException) {
                // The probe was answered with 206 and a part with the whole file: a CDN can answer
                // differently from one edge to the next. Parts are dropped and the file fetched whole.
                partFiles(task.workDir).forEach { it.delete() }
            }
        }
        downloadWhole(task, remote, meter)
    }

    private suspend fun downloadInParts(task: DownloadTask, length: Long, meter: ProgressMeter) {
        // Parts cut for another length are pieces of another file - the server's file changed
        // between runs - and would be joined into a corrupt one.
        val cutFor = File(task.workDir, Scratch.PARTS_LENGTH_NAME)
        if (cutFor.takeIf { it.isFile }?.readText() != length.toString()) {
            partFiles(task.workDir).forEach { it.delete() }
            cutFor.writeText(length.toString())
        }
        val ranges = partRanges(length)
        val files = ranges.indices.map { File(task.workDir, "${Scratch.PART_PREFIX}$it") }
        meter.reset(files.sumOf { it.length() })

        coroutineScope {
            ranges.zip(files).map { (range, file) ->
                async { fetchPart(task, range, file, meter) }
            }.awaitAll()
        }

        ranges.zip(files).forEach { (range, file) ->
            if (file.length() != sizeOf(range)) throw IOException("Part ${file.name} is incomplete")
        }
        files.joinInto(task.output)
        files.forEach { it.delete() }
        cutFor.delete()
    }

    /** Fetches what is still missing of one part; a part already whole is left alone. */
    private suspend fun fetchPart(task: DownloadTask, range: LongRange, file: File, meter: ProgressMeter) {
        // Longer than its range, it can never be right: fetched again rather than failed forever.
        if (file.length() > sizeOf(range)) {
            meter.add(-file.length())
            file.delete()
        }
        val have = file.length()
        if (have == sizeOf(range)) return
        fetcher.fetchInto(file, task.url, task.headers, range = (range.first + have)..range.last, onBytes = meter::add)
    }

    /** Straight into the output, which is resumed from its own length. */
    private suspend fun downloadWhole(task: DownloadTask, remote: RemoteFile, meter: ProgressMeter) {
        val output = task.output
        val length = remote.length
        // Longer than the file is not a head start, it is a different file.
        if (length != null && output.length() > length) output.delete()
        val have = output.length()
        meter.reset(have)

        if (length == null || have < length) {
            if (have > 0 && remote.servesRanges) {
                try {
                    fetcher.fetchInto(output, task.url, task.headers, range = have..Long.MAX_VALUE, onBytes = meter::add)
                } catch (_: RangeIgnoredException) {
                    // Resuming, and the server started again from byte one: start over rather
                    // than put the file's beginning in its middle.
                    meter.reset()
                    fetcher.fetchInto(output, task.url, task.headers, onBytes = meter::add)
                } catch (e: HttpStatusException) {
                    // Nothing past the bytes already here: a file of unknown size that was whole
                    // when the run before was stopped.
                    if (e.status != HttpStatusCode.RequestedRangeNotSatisfiable.value || length != null) throw e
                }
            } else {
                meter.reset()
                fetcher.fetchInto(output, task.url, task.headers, onBytes = meter::add)
            }
        }

        if (length != null && output.length() != length) {
            throw IOException("Expected $length bytes, received ${output.length()}")
        }
        if (output.length() == 0L) throw IOException("The server sent an empty file")
    }

    /** Up to [maxParallelParts] ranges of at least [Download.MIN_PART_BYTES] each. */
    private fun partRanges(length: Long): List<LongRange> {
        val count = (length / Download.MIN_PART_BYTES).coerceIn(1, maxParallelParts.toLong())
        val size = length / count
        return (0 until count).map { index ->
            val start = index * size
            val end = if (index == count - 1) length - 1 else start + size - 1
            start..end
        }
    }

    private fun sizeOf(range: LongRange): Long = range.last - range.first + 1

    private fun partFiles(workDir: File): List<File> =
        workDir.listFiles { file -> file.name.startsWith(Scratch.PART_PREFIX) }?.toList().orEmpty()
}
