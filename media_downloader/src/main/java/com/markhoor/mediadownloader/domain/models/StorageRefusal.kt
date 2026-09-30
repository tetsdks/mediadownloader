package com.markhoor.mediadownloader.domain.models

/**
 * Why the downloads folder cannot be written. Each one is a different thing for the host to do, so
 * they are told apart rather than reported as one failure.
 */
enum class StorageRefusal {

    /**
     * `WRITE_EXTERNAL_STORAGE` is not granted, and this Android release needs it: ask for it. The
     * only refusal the person using the app can do anything about.
     */
    PermissionNotGranted,

    /**
     * Android 10, the permission **is** granted, and the app is not in legacy storage mode: the
     * host's `<application>` is missing `requestLegacyExternalStorage="true"`. Only the host can
     * set it, in its manifest, and only a new build fixes it. Never turn this into a permission
     * prompt: the user granted the permission already, and being asked again for something they
     * did - and cannot fix - is the most confusing thing this module could do to them. Say
     * downloads are not working and leave it at that; the reason is in the log under
     * `MediaDownloader`.
     */
    LegacyStorageDisabled,

    /** Neither of those - no external storage mounted, or no room on it. Nothing to ask for. */
    StorageUnavailable,
}
