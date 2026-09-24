package com.markhoor.mediadownloader.presentation.browser

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
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
 * [BrowserViewModel.attach] (the host's own WebView) or [BrowserViewModel.createBrowser] (one the
 * module makes). It lets go of the WebView by itself when [lifecycleOwner] is destroyed.
 *
 * Every method is for the main thread. After [detach] the actions do nothing and return `false`.
 */
class MediaBrowser internal constructor(
    /** The WebView this browser drives; add it to a layout when the module created it. */
    val webView: WebView,
    private val detector: MediaDetector,
    lifecycleOwner: LifecycleOwner,
    private val ownsWebView: Boolean,
    private val clientDelegate: WebViewClient?,
    private val chromeDelegate: WebChromeClient?,
) {

    private val bridge = PageScriptBridge(detector)
    private var commandsJob: Job? = null

    /** Whether this browser still drives [webView]. */
    var isAttached: Boolean = true
        private set

    init {
        configure()
        commandsJob = lifecycleOwner.lifecycleScope.launch {
            detector.commands.collect(::run)
        }
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = detach()
        })
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
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configure() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        if (ownsWebView) {
            webView.settings.mediaPlaybackRequiresUserGesture = false
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
        }
        if (ownsWebView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        }
        webView.webViewClient = DetectingWebViewClient(detector, clientDelegate)
        webView.webChromeClient = DetectingChromeClient(detector, chromeDelegate)
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
