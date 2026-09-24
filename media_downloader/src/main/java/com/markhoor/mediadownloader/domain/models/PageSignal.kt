package com.markhoor.mediadownloader.domain.models

/** Something the browser reported about the page. Posted from any thread, handled in order. */
internal sealed interface PageSignal {

    /** A WebView was attached; its user agent is what media found on its pages is asked for with. */
    data class Attached(val userAgent: String) : PageSignal

    /** A navigation began: the earliest the new page's url is known. */
    data class PageStarted(val url: String) : PageSignal

    /** The new page is the current one. [isReload] when it is the same url again. */
    data class PageCommitted(
        val url: String,
        val title: String,
        val isReload: Boolean,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
    ) : PageSignal

    data class PageFinished(val url: String) : PageSignal

    data class TitleChanged(val title: String) : PageSignal

    data class ProgressChanged(val progress: Int) : PageSignal

    /** The page loaded a resource; [livePageUrl] is the document it landed in right now. */
    data class ResourceLoaded(val resourceUrl: String, val livePageUrl: String) : PageSignal

    /** The page made a network request. */
    data class RequestSeen(val url: String, val requestHeaders: Map<String, String>) : PageSignal

    /** A page script reported something. */
    data class Script(val message: ScriptMessage) : PageSignal

    /** The user pressed the host's own download button. */
    data object DownloadButtonPressed : PageSignal

    /** The system killed the WebView's renderer; the WebView cannot be used again. */
    data object RendererGone : PageSignal
}

/** Which page script a report came from. */
internal enum class ScriptSource { Facebook, Instagram, Twitter, Threads, Generic }

/** What a page script reports, as the bridge hands it over. */
internal sealed interface ScriptMessage {

    /** How many of the script's own buttons are on the page now. */
    data class ButtonsDrawn(val count: Int) : ScriptMessage

    /** The card on screen changed; passive, it only keeps its name and artwork ready. */
    data class CardChanged(
        val title: String?,
        val thumbnail: String?,
        val mediaUrl: String?,
        val isImage: Boolean,
    ) : ScriptMessage

    /** A per-media button was pressed on a site with no parser. */
    data class MediaRequested(
        val cardPageUrl: String?,
        val title: String?,
        val thumbnail: String?,
        val mediaUrl: String?,
        val isPlaying: Boolean,
        val isImage: Boolean,
    ) : ScriptMessage

    /**
     * A site script found media: a file ([mediaUrl]), a post the parser can read ([postUrl]), or both.
     */
    data class MediaFound(
        val source: ScriptSource,
        val type: MediaType,
        val mediaUrl: String?,
        val postUrl: String? = null,
        val thumbnail: String? = null,
        val title: String? = null,
    ) : ScriptMessage
}
