package com.markhoor.mediadownloader.domain.models

/**
 * A call reached the module before `MediaDownloader.initialize(context)`. Calls that return a
 * `Result` fail with this; the others throw it.
 */
class MediaDownloaderNotInitializedException : IllegalStateException(
    "MediaDownloader.initialize(context) must be called first, usually from Application.onCreate().",
)
