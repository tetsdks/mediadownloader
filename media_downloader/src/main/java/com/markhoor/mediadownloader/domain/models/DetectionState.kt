package com.markhoor.mediadownloader.domain.models

/**
 * The page in the browser and the media found on it.
 *
 * @param media what can be downloaded from the page right now; `null` until something is found.
 * @param isDescribingMedia [media] is shown and its qualities, sizes or name are still being read.
 * @param isSearching the user asked for the page's media and it has not been found yet.
 * @param showDownloadButton the host's own floating button belongs on this page now.
 */
internal data class DetectionState(
    val pageUrl: String = "",
    val pageTitle: String = "",
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val siteAccess: SiteAccess = SiteAccess.Unsupported,
    val media: MediaModel? = null,
    val isDescribingMedia: Boolean = false,
    val isSearching: Boolean = false,
    val hasScriptButtons: Boolean = false,
    val scriptAnswered: Boolean = false,
    val showDownloadButton: Boolean = false,
    val isRendererGone: Boolean = false,
)

/** One-shot outcomes for the host's screen. */
internal enum class DetectionEvent { ShowMedia, HideMedia, NothingFound, RendererGone }

/** Something the attached WebView must do, on its own thread. */
internal sealed interface PageCommand {
    data class RunScript(val script: String) : PageCommand
    data class Load(val url: String) : PageCommand
}
