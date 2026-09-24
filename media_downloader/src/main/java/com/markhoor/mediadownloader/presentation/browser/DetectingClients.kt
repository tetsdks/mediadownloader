package com.markhoor.mediadownloader.presentation.browser

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.isStaticAssetUrl
import com.markhoor.mediadownloader.domain.models.PageSignal
import com.markhoor.mediadownloader.domain.repo.MediaDetector

/**
 * Reports what the WebView does to the detector, and passes every callback on to the host's own
 * [delegate] when it gave one. The callbacks run on the main thread and Chromium's network thread:
 * each does no more than post a signal, so neither thread waits on detection.
 *
 * With no [detector] - once the browser is detached - it only forwards, and still survives a
 * renderer crash: every WebView alive in the app shares one renderer, and a single one that does
 * not handle its death takes the whole app down.
 */
internal class DetectingWebViewClient(
    private val detector: MediaDetector?,
    private val delegate: WebViewClient?,
) : WebViewClient() {

    private var lastCommittedUrl = ""

    /** The host decides when it gave a client; otherwise web links stay in the page and app links do not open. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        delegate?.shouldOverrideUrlLoading(view, request) ?: !request.url.toString().isHttpUrl()

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        delegate?.onPageStarted(view, url, favicon)
        detector?.onSignal(PageSignal.PageStarted(bounded(url)))
    }

    override fun onLoadResource(view: WebView, url: String?) {
        delegate?.onLoadResource(view, url)
        detector?.onSignal(PageSignal.ResourceLoaded(bounded(url), bounded(view.url)))
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        delegate?.doUpdateVisitedHistory(view, url, isReload)
        val committed = bounded(url)
        // "Reload" is the same address again, however it happened: nothing new to look at.
        val sameAgain = committed == lastCommittedUrl
        lastCommittedUrl = committed
        detector?.onSignal(
            PageSignal.PageCommitted(committed, view.title.orEmpty(), sameAgain, view.canGoBack(), view.canGoForward()),
        )
    }

    override fun onPageFinished(view: WebView, url: String?) {
        delegate?.onPageFinished(view, url)
        detector?.onSignal(PageSignal.PageFinished(bounded(url)))
    }

    /**
     * Every request the page makes. Style sheets, fonts, scripts and anything longer than a real
     * link are dropped here, before a signal is made: a page makes hundreds of these.
     */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (detector != null && url.length <= Browser.MAX_SNIFFED_URL_LENGTH && url.isHttpUrl() && !url.isStaticAssetUrl()) {
            detector.onSignal(PageSignal.RequestSeen(url, request.requestHeaders.orEmpty()))
        }
        return delegate?.shouldInterceptRequest(view, request)
    }

    /**
     * The system killed the renderer - on a device short of memory it does. Unhandled, that kills
     * the app too - and every other WebView sharing the renderer. Handled: the host is told, and
     * the WebView is not touched again.
     */
    @RequiresApi(Build.VERSION_CODES.O) // Only ever called from Android 8, where it was added.
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
        delegate?.onRenderProcessGone(view, detail)
        detector?.onSignal(PageSignal.RendererGone)
        return true
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        delegate?.onReceivedError(view, request, error) ?: super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        delegate?.onReceivedHttpError(view, request, errorResponse) ?: super.onReceivedHttpError(view, request, errorResponse)
    }

    /** The platform's answer (cancel) unless the host decides otherwise. */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        delegate?.onReceivedSslError(view, handler, error) ?: super.onReceivedSslError(view, handler, error)
    }

    private fun bounded(url: String?): String = url.orEmpty().take(Browser.MAX_SNIFFED_URL_LENGTH)
}

/** Reports loading progress and titles, and passes the rest - full screen video, file pickers - to [delegate]. */
internal class DetectingChromeClient(
    private val detector: MediaDetector,
    private val delegate: WebChromeClient?,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        delegate?.onProgressChanged(view, newProgress)
        detector.onSignal(PageSignal.ProgressChanged(newProgress))
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        delegate?.onReceivedTitle(view, title)
        detector.onSignal(PageSignal.TitleChanged(title.orEmpty()))
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        delegate?.onReceivedIcon(view, icon)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        delegate?.onShowCustomView(view, callback) ?: super.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        delegate?.onHideCustomView() ?: super.onHideCustomView()
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean = delegate?.onShowFileChooser(webView, filePathCallback, fileChooserParams)
        ?: super.onShowFileChooser(webView, filePathCallback, fileChooserParams)

    override fun onPermissionRequest(request: PermissionRequest?) {
        delegate?.onPermissionRequest(request) ?: super.onPermissionRequest(request)
    }

    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean =
        delegate?.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
            ?: super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)

    override fun onCloseWindow(window: WebView?) {
        delegate?.onCloseWindow(window) ?: super.onCloseWindow(window)
    }
}
