package com.markhoor.mediadownloader.domain.models

/** Why a download could not be started or changed. */
sealed class DownloadException(message: String) : Exception(message) {

    /** The page or the media is on YouTube or an adult site the configuration does not allow, or on a host the app blocked. */
    class SiteBlocked(val url: String) : DownloadException("Downloads are not allowed from this site")

    /** Not an http(s) url, or one far longer than any a site hands out. */
    class InvalidMediaUrl(val url: String) : DownloadException("This is not a downloadable media url")

    /** No download has this id - it was never made, or it was deleted. */
    class NotFound(val id: Long) : DownloadException("No download with id $id")

    /** The action does not apply in the download's current state, e.g. resuming a completed one. */
    class InvalidState(val id: Long, val state: DownloadState, action: String) :
        DownloadException("Cannot $action download $id while it is $state")

    /**
     * The download folder cannot be written, so the download is refused before it is made rather
     * than failing later with whatever the file system said. [refusal] says what the host can do.
     *
     * The message is written for whoever integrates the module, not for the person using the app -
     * only [StorageRefusal.PermissionNotGranted] is theirs to act on. Show your own wording.
     */
    class StorageNotWritable(val path: String, val refusal: StorageRefusal) : DownloadException(
        when (refusal) {
            StorageRefusal.PermissionNotGranted ->
                "Cannot write to $path: WRITE_EXTERNAL_STORAGE is not granted - ask the user for it"
            StorageRefusal.LegacyStorageDisabled ->
                "Cannot write to $path: the permission is granted but the app is not in legacy " +
                    "storage mode - add requestLegacyExternalStorage=\"true\" to the host's " +
                    "<application>. The user cannot fix this, and has already given the permission: " +
                    "asking them again would be asking for something they did."
            StorageRefusal.StorageUnavailable ->
                "Cannot write to $path: no external storage available, or no room on it"
        },
    )
}
