package com.markhoor.mediadownloader.presentation.browser

import android.annotation.SuppressLint
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.domain.models.PageCommand
import com.markhoor.mediadownloader.domain.models.PageSignal
import com.markhoor.mediadownloader.domain.repo.MediaDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One WebView bound to media detection, and the actions the host drives it with. Obtained from
 * [BrowserViewModel.attach] (the host's own WebView), [BrowserViewModel.createBrowser] (one the
 * module makes) or [BrowserTabs.activeBrowser] (the tab on screen). It lets go of the WebView by
 * itself when [lifecycleOwner] is destroyed.
 *
 * Every method is for the main thread. After [detach] the actions do nothing and return `false`.
 *
 * @param tabHost set when this browser is a tab's, which is what lets its pages open tabs of their
 *   own; `null` for a browser the host drives on its own.
 */
class MediaBrowser internal constructor(
    /** The WebView this browser drives; add it to a layout when the module created it. */
    val webView: WebView,
    private val detector: MediaDetector,
    lifecycleOwner: LifecycleOwner,
    private val ownsWebView: Boolean,
    private val clientDelegate: WebViewClient?,
    private val chromeDelegate: WebChromeClient?,
    private val tabHost: BrowserTabHost? = null,
) {

    private val bridge = PageScriptBridge(detector)
    private var commandsJob: Job? = null

    /**
     * Let go of on [detach] as well as on destroy. A screen builds one browser per tab and another
     * every time a tab's page comes back, and an observer is only ever removed by hand: left
     * registered, every browser the screen ever had would be held - with its WebView - until the
     * screen itself went away.
     */
    private var watched: Lifecycle? = null
    private val onOwnerDestroyed = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = detach()
    }

    /** Whether this browser still drives [webView]. */
    var isAttached: Boolean = true
        private set

    init {
        configure()
        commandsJob = lifecycleOwner.lifecycleScope.launch {
            detector.commands.collect(::run)
        }
        watched = lifecycleOwner.lifecycle.also { it.addObserver(onOwnerDestroyed) }
    }

    val canGoBack: Boolean get() = isAttached && webView.canGoBack()
    val canGoForward: Boolean get() = isAttached && webView.canGoForward()

    /** Opens [url]; `false` when it is not an http(s) link or the browser is detached. */
    @MainThread
    fun load(url: String): Boolean {
        val link = url.trim()
        if (!isAttached || !link.isHttpUrl()) return false
        webView.loadUrl(link)
        return true
    }

    @MainThread
    fun reload(): Boolean = act { webView.reload() }

    @MainThread
    fun stopLoading(): Boolean = act { webView.stopLoading() }

    /** Goes back a page; `false` when there is none, so the host can close the browser instead. */
    @MainThread
    fun goBack(): Boolean = canGoBack && act { webView.goBack() }

    @MainThread
    fun goForward(): Boolean = canGoForward && act { webView.goForward() }

    /** The host's own download button was pressed: the page's media is shown when there is any. */
    @MainThread
    fun onDownloadButtonClick() {
        if (isAttached) detector.onSignal(PageSignal.DownloadButtonPressed)
    }

    /**
     * Lets go of the WebView: its callbacks go back to the host's own clients, the script bridge is
     * removed, and a WebView the module created is destroyed. A WebView that lives on keeps a
     * client that survives a renderer crash, since one that does not would crash the app. Safe to
     * call more than once.
     */
    @MainThread
    fun detach() {
        if (!isAttached) return
        isAttached = false
        commandsJob?.cancel()
        commandsJob = null
        watched?.removeObserver(onOwnerDestroyed)
        watched = null
        runCatching { webView.removeJavascriptInterface(Browser.BRIDGE_NAME) }
        webView.webViewClient = DetectingWebViewClient(detector = null, delegate = clientDelegate)
        webView.webChromeClient = chromeDelegate
        if (ownsWebView) {
            runCatching { webView.stopLoading() }
            webView.destroy()
        }
    }

    /**
     * Page scripts need JavaScript and DOM storage, so both are switched on. On Android 8+ the
     * renderer is marked as one the system may reclaim while the app is out of sight - on a device
     * short of memory it would be killed anyway, and that is handled.
     *
     * A WebView the module made is also allowed to start media without a tap. Detection has nothing
     * to find until a player asks for its file, and many only do that on play: with the WebView's
     * default a video page sits on its poster, so no button appears and the page looks unsupported.
     * A host that brings its own WebView keeps whatever it set - this is not changed underneath it.
     *
     * A tab's WebView is the only one allowed several windows, so that a link that asks for one
     * reaches `onCreateWindow` and becomes a tab instead of replacing the page. Pages are still
     * not allowed to open one without a tap: that setting left off is the popup blocker.
     *
     * It is also given a size that does not depend on its content. A WebView left at wrap content -
     * which is what a view with no layout parameters becomes when a Compose `AndroidView` adds it -
     * is measured "at most this tall", and Chromium then resolves `vh` units to **zero** so that a
     * page cannot size the view that is sizing it. Sites that lay their player out in `vh` (tiktok
     * is one) collapse to nothing: the video plays, correctly, in a box zero pixels high, so the
     * page looks broken while the download button it produced works perfectly.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configure() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        if (ownsWebView) {
            webView.settings.mediaPlaybackRequiresUserGesture = false
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            webView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        if (ownsWebView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        }
        if (ownsWebView && tabHost != null) {
            webView.settings.setSupportMultipleWindows(true)
        }
        webView.webViewClient = DetectingWebViewClient(detector, clientDelegate)
        webView.webChromeClient = DetectingChromeClient(detector, chromeDelegate, tabHost)
        webView.addJavascriptInterface(bridge, Browser.BRIDGE_NAME)
        detector.onSignal(PageSignal.Attached(webView.settings.userAgentString.orEmpty()))
    }

    private fun run(command: PageCommand) {
        if (!isAttached) return
        when (command) {
            is PageCommand.RunScript -> runCatching { webView.evaluateJavascript(command.script, null) }
            is PageCommand.Load -> load(command.url)
        }
    }

    private inline fun act(action: () -> Unit): Boolean {
        if (!isAttached) return false
        action()
        return true
    }
}
