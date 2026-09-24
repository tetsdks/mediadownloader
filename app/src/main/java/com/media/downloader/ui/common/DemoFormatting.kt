package com.media.downloader.ui.common

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.markhoor.mediadownloader.domain.models.DownloadException
import com.markhoor.mediadownloader.domain.models.MediaParseException
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.StorageRefusal
import java.util.Locale

/**
 * A thumbnail the module handed over. It is usually an https url, but it can also be a small
 * `data:image/...` url from the page itself, which an image loader will not fetch - hence the branch.
 */
@Composable
fun MediaThumbnail(url: String?, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val inlineImage = remember(url) {
        url?.takeIf { it.startsWith("data:") }?.substringAfter("base64,", "")?.takeIf { it.isNotBlank() }
            ?.let { encoded ->
                runCatching {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
    }
    val box = modifier.size(size).background(placeholder)
    when {
        inlineImage != null -> Image(
            bitmap = inlineImage.asImageBitmap(),
            contentDescription = null,
            modifier = box,
            contentScale = ContentScale.Crop,
        )

        !url.isNullOrBlank() && !url.startsWith("data:") -> AsyncImage(
            model = url,
            contentDescription = null,
            modifier = box,
            contentScale = ContentScale.Crop,
        )

        else -> Box(box)
    }
}

/** KB/MB/GB. The module reports bytes and leaves the wording to the host. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toInt()} B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** A stream's size is an estimate that firms up while downloading, so it is shown with a `~`. */
fun qualitySizeLabel(quality: MediaQualityModel): String {
    val size = quality.sizeBytes ?: return "Size unknown"
    val isEstimate = quality.audioUrl != null || quality.url.contains(".m3u8", ignoreCase = true)
    return (if (isEstimate) "~" else "") + formatBytes(size)
}

/** Every parse failure is one of four cases, and each one deserves its own sentence. */
fun parseFailureMessage(error: MediaParseException): String = when (error) {
    is MediaParseException.SiteBlocked -> "Downloads aren't allowed from this site"
    is MediaParseException.SiteUnsupported -> "This site isn't on the supported list"
    is MediaParseException.LinkNotRecognised -> "That link isn't a post - a feed or a profile has nothing to read"
    is MediaParseException.MediaNotFound -> "Couldn't find any media behind that link"
}

/** The browser can still find media on a page the parser could not read; a blocked site cannot. */
fun canTryInBrowser(error: MediaParseException): Boolean = error !is MediaParseException.SiteBlocked

/**
 * A download that was refused or failed. `StorageNotWritable.message` is written for whoever
 * integrates the library, never for the person using it, so it is turned into a sentence here.
 */
fun downloadFailureMessage(error: Throwable): String = when (error) {
    is DownloadException.SiteBlocked -> "Downloads aren't allowed from this site"
    is DownloadException.InvalidMediaUrl -> "That media link can't be downloaded"
    is DownloadException.NotFound -> "That download is gone"
    is DownloadException.InvalidState -> "Can't do that while it is ${error.state}"
    is DownloadException.StorageNotWritable -> storageRefusalMessage(error.refusal)
    else -> "Something went wrong"
}

/**
 * Only [StorageRefusal.PermissionNotGranted] is the user's to act on, and the gate asks for that
 * one instead of showing a message. The other two are not their doing and must never become a
 * permission prompt.
 */
fun storageRefusalMessage(refusal: StorageRefusal): String = when (refusal) {
    StorageRefusal.PermissionNotGranted -> "Downloads need permission to save into your Downloads folder"
    StorageRefusal.LegacyStorageDisabled -> "This build can't save downloads - see the log"
    StorageRefusal.StorageUnavailable -> "Storage isn't available right now"
}
