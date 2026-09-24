package com.markhoor.mediadownloader.presentation.downloads

import com.markhoor.mediadownloader.domain.models.DownloadModel

/** The downloads screen's state. */
sealed interface DownloadsUiState {

    data object Loading : DownloadsUiState

    /**
     * @param inProgress everything not yet completed - running, waiting, paused or failed - newest first.
     * @param completed finished downloads, newest first.
     */
    data class Content(
        val inProgress: List<DownloadModel>,
        val completed: List<DownloadModel>,
    ) : DownloadsUiState {
        val isEmpty: Boolean get() = inProgress.isEmpty() && completed.isEmpty()
    }
}

/** One-shot outcomes of the actions, for a toast or a snackbar. */
sealed interface DownloadsEvent {

    data class Started(val id: Long) : DownloadsEvent

    /** Refused or failed to start; usually a [com.markhoor.mediadownloader.domain.models.DownloadException]. */
    data class NotStarted(val error: Throwable) : DownloadsEvent

    /** A pause, resume or delete that did not happen, and why. */
    data class ActionFailed(val id: Long, val error: Throwable) : DownloadsEvent
}
