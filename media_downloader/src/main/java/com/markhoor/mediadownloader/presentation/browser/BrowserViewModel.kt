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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The browser screen: the page, the media found on it, and the moments the host's download sheet
 * should open or close. Obtain it with `by viewModels { BrowserViewModel.Factory }`, then give it a
 * WebView with [attach] or let it make one with [createBrowser].
 *
 * What was found survives a configuration change; the WebView does not belong to the ViewModel and
 * is let go of when the lifecycle owner it was attached with is destroyed.
 */
class BrowserViewModel internal constructor(
    detectorFactory: (CoroutineScope) -> MediaDetector,
) : ViewModel() {

    private val detector = detectorFactory(viewModelScope)

    val uiState: StateFlow<BrowserUiState> = detector.state
        .map(::toUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, BrowserUiState())

    /** Collect once, from the screen. */
    val events: Flow<BrowserEvent> = detector.events.map(::toUiEvent)

    private var browser: MediaBrowser? = null

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

    /** The host's own download button was pressed. */
    fun onDownloadButtonClick() {
        browser?.onDownloadButtonClick()
    }

    override fun onCleared() {
        browser?.detach()
        browser = null
    }

    private fun bind(
        webView: WebView,
        lifecycleOwner: LifecycleOwner,
        ownsWebView: Boolean,
        webViewClient: WebViewClient?,
        webChromeClient: WebChromeClient?,
    ): MediaBrowser {
        browser?.detach()
        return MediaBrowser(webView, detector, lifecycleOwner, ownsWebView, webViewClient, webChromeClient)
            .also { browser = it }
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
    )

    private fun toUiEvent(event: DetectionEvent): BrowserEvent = when (event) {
        DetectionEvent.ShowMedia -> BrowserEvent.ShowMedia
        DetectionEvent.HideMedia -> BrowserEvent.HideMedia
        DetectionEvent.NothingFound -> BrowserEvent.NothingFound
        DetectionEvent.RendererGone -> BrowserEvent.BrowserCrashed
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { BrowserViewModel(MediaDownloader.requireComponent()::newMediaDetector) }
        }
    }
}
