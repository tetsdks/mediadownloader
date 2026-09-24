package com.markhoor.mediadownloader.presentation.browser

import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.SiteAccess

/**
 * The browser screen's state.
 *
 * @param siteAccess whether downloads may be offered on this page's site at all.
 * @param media what the page offers to download.
 * @param showDownloadButton the host's own floating download button belongs on the page now: there
 *   is media, the site allows it, and no page script is showing its own button on the media.
 */
data class BrowserUiState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val siteAccess: SiteAccess = SiteAccess.Unsupported,
    val media: PageMediaState = PageMediaState.None,
    val showDownloadButton: Boolean = false,
) {
    val isLoading: Boolean get() = progress in 1..99
}

/** The media found on the page. */
sealed interface PageMediaState {

    /** Nothing found, and nothing asked for. */
    data object None : PageMediaState

    /** The user asked for the page's media and it is still being looked for. */
    data object Searching : PageMediaState

    /**
     * @param isDescribing qualities, sizes or the name are still being read; what is shown can
     *   already be downloaded.
     */
    data class Found(val media: MediaModel, val isDescribing: Boolean) : PageMediaState
}

/** One-shot moments for the browser screen. */
sealed interface BrowserEvent {

    /** Show the page's media - open the download sheet; [BrowserUiState.media] fills it. */
    data object ShowMedia : BrowserEvent

    /** Close the download sheet: what was being looked for could not be read. */
    data object HideMedia : BrowserEvent

    /** Nothing downloadable turned up on the page in time: say so and close the sheet. */
    data object NothingFound : BrowserEvent

    /**
     * The system killed the WebView's renderer, usually for memory. The WebView cannot be used again:
     * remove it, and attach or create a new one to carry on.
     */
    data object BrowserCrashed : BrowserEvent
}
