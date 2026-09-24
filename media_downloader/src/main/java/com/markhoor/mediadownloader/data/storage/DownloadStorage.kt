package com.markhoor.mediadownloader.data.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.core.Constants.Storage
import com.markhoor.mediadownloader.core.freeFile
import com.markhoor.mediadownloader.core.looksLikeMarkup
import com.markhoor.mediadownloader.core.mediaExtension
import com.markhoor.mediadownloader.core.moveTo
import com.markhoor.mediadownloader.core.siteFolderName
import com.markhoor.mediadownloader.core.sniffedExtension
import com.markhoor.mediadownloader.core.toFileNameBase
import com.markhoor.mediadownloader.data.download.UnusableMediaException
import com.markhoor.mediadownloader.data.local.DownloadEntity
import com.markhoor.mediadownloader.domain.models.DownloadException
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.domain.models.StorageRefusal
import java.io.File
import java.io.IOException

/**
 * Decides which [StorageRefusal] to report when the download folder cannot be written. Kept apart
 * from the probe so the reasoning can be tested without a file system or a permission.
 */
internal fun storageRefusalFor(
    sdkInt: Int,
    writePermissionGranted: Boolean,
    legacyStorage: Boolean,
): StorageRefusal = when {
    // Up to Android 10 the permission is what grants plain file access to Download/.
    sdkInt <= Build.VERSION_CODES.Q && !writePermissionGranted -> StorageRefusal.PermissionNotGranted
    // Android 10 with the permission, and the app is not in legacy mode: the host's manifest is
    // missing requestLegacyExternalStorage. Read from the system rather than guessed, so a user who
    // did grant the permission is never told to grant it again for something they cannot fix.
    sdkInt == Build.VERSION_CODES.Q && !legacyStorage -> StorageRefusal.LegacyStorageDisabled
    // Android 11+ needs no permission for Download/, so a refusal is the storage itself.
    else -> StorageRefusal.StorageUnavailable
}

/** Where a download's file goes, what it is called, and the scratch space it is built in. */
internal class DownloadStorage(
    private val appContext: Context,
    private val rootFolderName: String,
    /** The public Download folder; a lambda so a test can point this at a folder it owns. */
    private val publicDownloads: () -> File = {
        @Suppress("DEPRECATION") // Plain file access to Download/ is what MediaStore indexes; see the guide.
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    },
) {

    private val publishLock = Any()
    private val probeLock = Any()
    private var probedAtMillis = 0L
    private var lastRefusal: DownloadException.StorageNotWritable? = null

    /**
     * Why the download folder cannot be written, or `null` when it can. Written to rather than
     * asked about: on Android 10 a granted permission with legacy storage off still cannot write
     * there, which no permission check would catch. The answer is reused for
     * [Storage.WRITE_PROBE_TTL_MS], so enqueueing many downloads at once probes once.
     */
    fun writeRefusalOrNull(): DownloadException.StorageNotWritable? = synchronized(probeLock) {
        val now = System.currentTimeMillis()
        if (probedAtMillis != 0L && now - probedAtMillis < Storage.WRITE_PROBE_TTL_MS) return lastRefusal
        val root = runCatching { File(publicDownloads(), rootFolderName) }.getOrNull()
        val refusal = when {
            root == null -> DownloadException.StorageNotWritable("", StorageRefusal.StorageUnavailable)
            isWritable(root) -> null
            else -> DownloadException.StorageNotWritable(
                root.path,
                storageRefusalFor(Build.VERSION.SDK_INT, hasWritePermission(), isLegacyStorage()),
            )
        }
        probedAtMillis = now
        lastRefusal = refusal
        // Said in the log as well: a refusal the user cannot act on is the host's to fix, and it
        // would otherwise only show as downloads that do not start.
        if (refusal != null) Log.e(Download.LOG_TAG, refusal.message.orEmpty())
        return refusal
    }

    /**
     * Whether the app sees external storage the old way. Android 10 only: below it there is no
     * other way, and from Android 11 the download folder needs no legacy mode.
     */
    private fun isLegacyStorage(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Environment.isExternalStorageLegacy()

    /** Whether a file can really be made in [directory], the only answer that settles it. */
    private fun isWritable(directory: File): Boolean = try {
        directory.mkdirs()
        val probe = File(directory, "${Storage.WRITE_PROBE_NAME}${System.nanoTime()}")
        if (probe.createNewFile()) {
            probe.delete()
            true
        } else {
            // Already there from an interrupted probe: the folder took a file, which is the point.
            probe.exists()
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun hasWritePermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

    /** A download's folder and file name: `Download/<root>/Websites/<site>/<title>.<ext>`. */
    fun placeFor(request: DownloadRequest): Pair<File, String> {
        // One folder name from the host, never a path: it cannot leave Websites/.
        val site = request.siteFolder?.toFileNameBase()?.ifEmpty { null } ?: request.sourceUrl.siteFolderName()
        val directory = File(publicDownloads(), "$rootFolderName/${Storage.WEBSITES_FOLDER}/$site")
        val baseName = (request.fileName ?: request.title).toFileNameBase()
            .ifEmpty { "${site.replace(" ", "")}_${System.currentTimeMillis()}" }
        val name = directory.freeFile(baseName, request.mediaUrl.mediaExtension(request.type)).name
        return directory to name
    }

    /** Private scratch space for one download's parts and segments. */
    fun workDirFor(id: Long): File = File(appContext.filesDir, "${Storage.TEMP_FOLDER}/$id")

    /**
     * Where the engine writes the finished file: beside the final one, hidden, and named by id so
     * two downloads of the same title never share it. Publishing it is then a rename.
     */
    fun outputFor(entity: DownloadEntity): File = File(entity.directoryPath, ".${entity.id}.download")

    /**
     * Moves the finished output to its final name, correcting the extension to what the bytes
     * say - a `.png` link can serve a jpeg - and taking the next free name if the planned one was
     * taken meanwhile.
     */
    fun publish(entity: DownloadEntity): File {
        val output = outputFor(entity)
        if (!output.isFile || output.length() == 0L) throw IOException("Nothing was downloaded for ${entity.fileName}")
        val head = ByteArray(Storage.SNIFF_BYTES)
        val read = output.inputStream().use { it.read(head) }
        val planned = entity.fileName
        val bytes = head.copyOf(read.coerceAtLeast(0))
        val sniffed = bytes.sniffedExtension()
        if (entity.type == MediaType.Video && (bytes.looksLikeMarkup() || sniffed in Storage.IMAGE_EXTENSIONS)) {
            // A spent or refused link answered with a page or a placeholder picture - KVS tube
            // sites send a GIF for a used get_file link. Saved, it would be a broken "video".
            output.delete()
            throw UnusableMediaException("The site sent ${if (sniffed == null) "a web page" else "a picture"} instead of the video")
        }
        val extension = sniffed ?: planned.substringAfterLast('.', "mp4")
        // Two downloads of one title can finish together; picking the free name and taking it
        // must be one step, or both take the same name and the second replaces the first.
        synchronized(publishLock) {
            val target = File(entity.directoryPath).freeFile(planned.substringBeforeLast('.'), extension)
            output.moveTo(target)
            return target
        }
    }

    /** Tells the media index a file is there, so galleries and players list it. */
    fun announce(file: File) {
        MediaScannerConnection.scanFile(appContext, arrayOf(file.path), null, null)
    }

    /** Everything a download left behind while unfinished. */
    fun deleteWorkFiles(entity: DownloadEntity) {
        workDirFor(entity.id).deleteRecursively()
        outputFor(entity).delete()
    }
}
