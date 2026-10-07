package com.markhoor.mediadownloader.presentation.browser

import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.markhoor.mediadownloader.MediaDownloader
import com.markhoor.mediadownloader.domain.models.DetectionEvent
import com.markhoor.mediadownloader.domain.models.DetectionState
import com.markhoor.mediadownloader.domain.repo.MediaDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * One detection session and the scope it runs in: a tab's, or the one browser's. Closing it ends
 * the session and everything it has running, without touching the others.
 */
internal class PageSession(val detector: MediaDetector, val scope: CoroutineScope) {
    fun close() = scope.cancel()
}

/**
 * The browser screen: the page, the media found on it, and the moments the host's download sheet
 * should open or close. Obtain it with `by viewModels { BrowserViewModel.Factory }`, then give it a
 * WebView with [attach], let it make one with [createBrowser], or ask it for [tabs] and let it hold
 * several pages at once.
 *
 * [uiState] and [events] always describe the page being shown - the one browser's, or the tab that
 * is selected. A tab loading in the background is found out about all the same, and its findings
 * wait in its own session until it is selected; what it reports never reaches the screen, so it
 * cannot open a sheet over the page being read.
 *
 * What was found survives a configuration change; the WebViews do not belong to the ViewModel and
 * are let go of when the lifecycle owner they were made for is destroyed.
 */
class BrowserViewModel internal constructor(
    private val detectorFactory: (CoroutineScope) -> MediaDetector,
    private val isLowEndDevice: () -> Boolean = { false },
) : ViewModel() {

    /** The session whose page the screen shows. */
    private val shown = MutableStateFlow<MediaDetector?>(null)

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _events = Channel<BrowserEvent>(Channel.BUFFERED)

    /** Collect once, from the screen. */
    val events: Flow<BrowserEvent> = _events.receiveAsFlow()

    /** The session of a host that drives one browser itself, rather than asking for [tabs]. */
    private val single = newSession()

    private var browser: MediaBrowser? = null
    private var openTabs: BrowserTabs? = null

    init {
        show(single.detector)
    }

    /**
     * Drives the host's own [webView]. Its callbacks are passed on to [webViewClient] and
     * [webChromeClient] when given; set them here rather than on the WebView, which this replaces.
     * A browser attached before is detached first.
     */
    @MainThread
    fun attach(
        webView: WebView,
        lifecycleOwner: LifecycleOwner,
        webViewClient: WebViewClient? = null,
        webChromeClient: WebChromeClient? = null,
    ): MediaBrowser = bind(webView, lifecycleOwner, ownsWebView = false, webViewClient, webChromeClient)

    /**
     * A WebView made by the module; add [MediaBrowser.webView] to a layout. It is destroyed when
     * [lifecycleOwner] is, or on [MediaBrowser.detach].
     */
    @MainThread
    fun createBrowser(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        webViewClient: WebViewClient? = null,
        webChromeClient: WebChromeClient? = null,
    ): MediaBrowser = bind(WebView(context), lifecycleOwner, ownsWebView = true, webViewClient, webChromeClient)

    /**
     * Several pages open at once, with one on screen - what a phone browser calls tabs. The tabs
     * live as long as this ViewModel; their WebViews are built with [context], belong to
     * [lifecycleOwner] and are given up when it is destroyed, each tab keeping its page as saved
     * state so the next screen puts it back. Call this once per screen - the same tabs come back.
     *
     * [homeUrl] is the page a tab opens on when nothing else says what it should show -
     * [BrowserTabs.open] with no address, and the tab left behind when the last one is closed.
     *
     * The module makes every tab's WebView itself, so this is the [createBrowser] way of working
     * and not the [attach] one; the clients given here are forwarded to by every tab, as they are
     * there. Use one or the other: a host that drives its own browser as well would have two pages
     * claiming one screen's state.
     */
    @MainThread
    fun tabs(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        homeUrl: String? = null,
        webViewClient: WebViewClient? = null,
        webChromeClient: WebChromeClient? = null,
    ): BrowserTabs {
        val current = openTabs
            ?: BrowserTabs(::newSession, ::show, isLowEndDevice).also { openTabs = it }
        current.hostOn(context, lifecycleOwner, homeUrl, webViewClient, webChromeClient)
        return current
    }

    /** The host's own download button was pressed, on whichever page is being shown. */
    fun onDownloadButtonClick() {
        (openTabs?.activeBrowser?.value ?: browser)?.onDownloadButtonClick()
    }

    override fun onCleared() {
        browser?.detach()
        browser = null
        openTabs?.release()
        openTabs = null
    }

    private fun bind(
        webView: WebView,
        lifecycleOwner: LifecycleOwner,
        ownsWebView: Boolean,
        webViewClient: WebViewClient?,
        webChromeClient: WebChromeClient?,
    ): MediaBrowser {
        browser?.detach()
        show(single.detector)
        return MediaBrowser(webView, single.detector, lifecycleOwner, ownsWebView, webViewClient, webChromeClient)
            .also { browser = it }
    }

    /**
     * A session of its own, with the screen listening to it only while it is the one being shown.
     * Both streams are collected here and then, so a background tab's moments are taken and
     * dropped rather than left to arrive late - selecting a tab must not pop the sheet of a page
     * that finished loading minutes ago.
     */
    private fun newSession(): PageSession {
        val scope = CoroutineScope(
            viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]),
        )
        val detector = detectorFactory(scope)
        scope.launch {
            detector.state.collect { if (shown.value === detector) _uiState.value = toUiState(it) }
        }
        scope.launch {
            detector.events.collect { if (shown.value === detector) _events.send(toUiEvent(it)) }
        }
        return PageSession(detector, scope)
    }

    /** With no tab open there is nothing to show, which is the one browser's state: empty. */
    private fun show(detector: MediaDetector?) {
        val next = detector ?: single.detector
        shown.value = next
        _uiState.value = toUiState(next.state.value)
    }

    private fun toUiState(state: DetectionState) = BrowserUiState(
        url = state.pageUrl,
        title = state.pageTitle,
        progress = state.progress,
        canGoBack = state.canGoBack,
        canGoForward = state.canGoForward,
        siteAccess = state.siteAccess,
        media = when {
            state.media != null -> PageMediaState.Found(state.media, state.isDescribingMedia)
            state.isSearching -> PageMediaState.Searching
            else -> PageMediaState.None
        },
        showDownloadButton = state.showDownloadButton,
        isPageVisible = state.isPageVisible,
    )

    private fun toUiEvent(event: DetectionEvent): BrowserEvent = when (event) {
        DetectionEvent.ShowMedia -> BrowserEvent.ShowMedia
        DetectionEvent.HideMedia -> BrowserEvent.HideMedia
        DetectionEvent.NothingFound -> BrowserEvent.NothingFound
        DetectionEvent.RendererGone -> BrowserEvent.BrowserCrashed
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val component = MediaDownloader.requireComponent()
                BrowserViewModel(component::newMediaDetector) { component.deviceProfile.isLowEnd }
            }
        }
    }
}
