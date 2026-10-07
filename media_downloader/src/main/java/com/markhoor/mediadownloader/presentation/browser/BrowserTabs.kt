package com.markhoor.mediadownloader.presentation.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.normalizedHost
import com.markhoor.mediadownloader.domain.repo.MediaDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the browser of a tab tells the tab it belongs to. One of these per tab. */
internal interface BrowserTabHost {

    /** The page asked for a new window: the WebView of the tab opened for it, or `null` to refuse. */
    fun openWindow(): WebView?

    /** The page closed itself. */
    fun closeWindow()

    fun onIcon(icon: Bitmap?)
}

/**
 * Several pages open at once, one of them on screen: tabs, as a phone browser has them. Obtained
 * from [BrowserViewModel.tabs], which also puts them on the screen that shows them.
 *
 * Every tab finds its own media, in a detection session of its own, so a tab left loading keeps
 * looking and is ready the moment it is selected. Only the tab on screen reaches
 * [BrowserViewModel.uiState] and [BrowserViewModel.events]: a background tab never opens the host's
 * download sheet over the page being read.
 *
 * There is always a tab once there has been one: closing the last leaves one on the host's home
 * page in its place, so a host never has to draw a browser with no page in it. Before the first
 * [open] there are none, which is what lets the host decide when the browser starts.
 *
 * A tab does not always hold a WebView. Past [Browser.LIVE_TABS] live pages the least recently
 * shown tab saves its page and gives its WebView up; selecting it builds one again and restores it.
 * That is the reason the module holds the tabs rather than the host: a renderer is tens of
 * megabytes, and a phone cannot keep sixteen. A picture of the page is taken before that happens,
 * and whenever a tab leaves the screen, so a tab switcher has a card to draw for every tab that
 * has been drawn once.
 *
 * Every method is for the main thread.
 */
class BrowserTabs internal constructor(
    private val newSession: () -> PageSession,
    private val showing: (MediaDetector?) -> Unit,
    private val isLowEnd: () -> Boolean,
    maxTabs: Int = Browser.MAX_TABS,
) {

    private val roster = TabRoster(maxTabs)
    private val tabs = mutableMapOf<String, Tab>()
    private val closed = ArrayDeque<ClosedTab>()
    private var host: Screen? = null
    private var opened = 0

    /** The page a tab opens on when nothing else says what it should show; see [hostOn]. */
    private var homeUrl: String = ""

    /**
     * How big the page on screen is. A tab that is not on screen is in no layout, so nothing
     * measures its WebView, and at no size at all Chromium resolves `vh` to zero and the page lays
     * itself out as though it had no room. Every WebView is given this size as it is built; the
     * host's own layout replaces it the moment the tab is shown.
     */
    private var pageSize: Pair<Int, Int>? = null

    /**
     * The tab in a callback of its own right now: the page that is opening a window. Destroying
     * its WebView from inside that callback would take the app with it, so the budget spares it.
     */
    private var busy: String? = null

    private val _state = MutableStateFlow(BrowserTabsUiState())
    val state: StateFlow<BrowserTabsUiState> = _state.asStateFlow()

    private val _activeBrowser = MutableStateFlow<MediaBrowser?>(null)

    /**
     * The browser of the tab on screen; `null` before the first tab is opened, or when the screen
     * that held them has been destroyed. Add its [MediaBrowser.webView] to the layout, and swap it for the
     * new one whenever this changes.
     */
    val activeBrowser: StateFlow<MediaBrowser?> = _activeBrowser.asStateFlow()

    /**
     * Takes a fresh picture of every tab that still has a page, for their cards. Call it as a tab
     * switcher opens: that is the one moment the pictures are about to be looked at, and drawing a
     * page costs enough that it is not worth doing on the way past. `false` when nothing was drawn.
     */
    @MainThread
    fun capturePreview(): Boolean {
        val drawn = tabs.values.count { capture(it) }
        if (drawn > 0) publish()
        return drawn > 0
    }

    /**
     * Opens a tab and shows it. With no address it opens the home page the host named; with none
     * of those either, an empty tab for the host's own new-tab screen. The id, or `null` when
     * [Browser.MAX_TABS] are already open.
     */
    @MainThread
    fun open(url: String = ""): String? = openTab(url.ifBlank { homeUrl }, select = true)

    /** Opens a tab beside the one on screen without leaving it - "open in a new tab". */
    @MainThread
    fun openInBackground(url: String): String? =
        openTab(url, select = false, openerId = roster.activeId)

    @MainThread
    fun select(id: String): Boolean {
        if (id == roster.activeId || !roster.isOpen(id)) return false
        roster.select(id)
        giveItAPage(id)
        onSelectionChanged()
        trimToBudget()
        publish()
        return true
    }

    /**
     * Closes a tab; what is shown next is the tab that opened it, else a neighbour. Closing the
     * last one leaves a tab on the home page in its place - a browser in use always has a tab, and
     * a tab with nothing in it is no better than no tab at all.
     */
    @MainThread
    fun close(id: String): Boolean {
        val tab = tabs[id] ?: return false
        if (!roster.close(id)) return false
        tabs.remove(id)
        keepPage(tab)
        remember(tab)
        tab.browser?.detach()
        tab.browser = null
        tab.session.close()
        if (roster.size == 0) {
            openTab(homeUrl, select = true)
        } else {
            roster.activeId?.let { giveItAPage(it) }
            onSelectionChanged()
            publish()
        }
        return true
    }

    /** Closes every tab but one, which is then the tab on screen. */
    @MainThread
    fun closeOthers(id: String) {
        roster.ids.filter { it != id }.forEach(::close)
        select(id)
    }

    /** Closes every tab. One on the home page is left behind, as closing the last one always leaves. */
    @MainThread
    fun closeAll() {
        roster.ids.forEach(::close)
    }

    /**
     * Brings the last closed tab back, with the page it was on, and shows it. The id, or `null`
     * when none was closed recently or there is no room for it.
     */
    @MainThread
    fun restoreLastClosed(): String? {
        val gone = closed.removeLastOrNull() ?: return null
        val id = openTab(gone.url, select = true, saved = gone.saved, preview = gone.preview)
        if (id == null) closed.addLast(gone)
        return id
    }

    /**
     * Back a page in the tab on screen. A tab with nowhere to go back to that a link in another
     * tab opened is closed instead, which puts the page that opened it back on screen. `false`
     * means here what it means for one browser: there is nothing left to go back to, so the host
     * closes the browser.
     */
    @MainThread
    fun goBack(): Boolean {
        val id = roster.activeId ?: return false
        if (tabs[id]?.browser?.goBack() == true) return true
        return roster.openerOf(id) != null && close(id)
    }

    /**
     * Puts the tabs on a screen: their WebViews are built with [context], belong to
     * [lifecycleOwner] and are given up when it is destroyed, while the tabs themselves and the
     * pages they saved live on. The clients are the host's own, as [BrowserViewModel.attach] takes
     * them, and every tab's browser forwards to them.
     *
     * [homeUrl] is the page a tab opens on with nothing else to show: [open] with no address, and
     * the tab left behind when the last one is closed. Left out, those tabs are empty and the host
     * draws its own new-tab screen over them.
     */
    @MainThread
    internal fun hostOn(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        homeUrl: String?,
        webViewClient: WebViewClient?,
        webChromeClient: WebChromeClient?,
    ) {
        this.homeUrl = homeUrl?.trim().orEmpty()
        if (host?.lifecycleOwner === lifecycleOwner) return
        letGoOfPages()
        host = Screen(context, lifecycleOwner, webViewClient, webChromeClient)
        // Until a tab has been on screen, the screen itself is the best guess at how big a page is.
        if (pageSize == null) {
            val metrics = context.resources.displayMetrics
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                pageSize = metrics.widthPixels to metrics.heightPixels
            }
        }
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (host?.lifecycleOwner !== owner) return
                letGoOfPages()
                host = null
            }
        })
        roster.activeId?.let { giveItAPage(it) }
        onSelectionChanged()
        publish()
    }

    /** The ViewModel is gone: every page is given up and every session ends. */
    @MainThread
    internal fun release() {
        letGoOfPages()
        host = null
        tabs.values.forEach {
            it.session.close()
            it.preview = null
        }
        tabs.clear()
        closed.clear()
    }

    private fun openTab(
        url: String,
        select: Boolean,
        openerId: String? = null,
        saved: Bundle? = null,
        preview: Bitmap? = null,
    ): String? {
        val id = "tab-${++opened}"
        if (!roster.open(id, select, openerId)) return null
        val tab = Tab(id, newSession())
        tab.saved = saved
        tab.preview = preview
        tab.pending = url.trim()
        tab.url = url.trim()
        tabs[id] = tab
        watch(tab)
        giveItAPage(id)
        onSelectionChanged()
        trimToBudget()
        publish()
        return id
    }

    /**
     * Builds the tab's WebView and puts its page back: the state it saved, or else the address it
     * was last on. Does nothing without a screen to build it for - the tabs outlive the screen,
     * and the next one brings them back.
     */
    private fun giveItAPage(id: String) {
        val tab = tabs[id] ?: return
        if (tab.browser?.isAttached == true) return
        val screen = host ?: return
        val browser = MediaBrowser(
            webView = WebView(screen.context),
            detector = tab.session.detector,
            lifecycleOwner = screen.lifecycleOwner,
            ownsWebView = true,
            clientDelegate = screen.webViewClient,
            chromeDelegate = screen.webChromeClient,
            tabHost = windowsOf(id),
        )
        tab.browser = browser
        // Only one that nothing else will measure. The tab being shown is about to be put in the
        // host's own layout, and a WebView laid out here first is briefly a blank rectangle the
        // size of the screen, over the screen - which is the flash a new tab used to open on.
        if (id != roster.activeId) {
            pageSize?.let { (width, height) -> browser.webView.layOutTo(width, height) }
        }
        val restored = tab.saved?.let { browser.webView.restoreState(it) }
        tab.saved = null
        if (restored == null) browser.load(tab.pending.ifBlank { tab.url })
        tab.pending = ""
    }

    /**
     * Keeps every tab's address, name, icon and progress up to date from its own session - a strip
     * names tabs that are not on screen - and answers a renderer the system killed.
     */
    private fun watch(tab: Tab) {
        tab.session.scope.launch {
            tab.session.detector.state.collect { state ->
                val before = tab.toUiState(isActive = false)
                if (state.pageUrl.isNotBlank()) {
                    // A page with no icon of its own never reports one, so without this the site
                    // left behind would go on naming the tab it is no longer on.
                    if (state.pageUrl.normalizedHost() != tab.url.normalizedHost()) tab.icon = null
                    tab.url = state.pageUrl
                }
                if (state.pageTitle.isNotBlank()) tab.title = state.pageTitle
                tab.progress = state.progress
                if (!state.isRendererGone) {
                    tab.crashed = false
                } else if (!tab.crashed) {
                    tab.crashed = true
                    afterCrash(tab)
                }
                if (before != tab.toUiState(isActive = false)) publish()
            }
        }
    }

    /**
     * The system killed the renderer, which every WebView in the app shares, so every live tab
     * hears this at once. Only the tab on screen is built again: the others keep the address they
     * were on and are rebuilt when they are selected, which is also the kindest thing to do to a
     * device that has just run out of memory.
     */
    private fun afterCrash(tab: Tab) {
        tab.pending = tab.url
        tab.saved = null
        tab.browser?.detach()
        tab.browser = null
        if (tab.id == roster.activeId) giveItAPage(tab.id)
        onSelectionChanged()
        publish()
    }

    private fun liveBudget(): Int = if (isLowEnd()) Browser.LIVE_TABS_LOW_END else Browser.LIVE_TABS

    private fun previewWidth(): Int =
        if (isLowEnd()) Browser.PREVIEW_WIDTH_LOW_END else Browser.PREVIEW_WIDTH

    /** The size a WebView in no layout is given, so its page has a window to lay itself out in. */
    private fun View.layOutTo(width: Int, height: Int) {
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, width, height)
    }

    private fun trimToBudget() {
        val live = tabs.values.filter { it.browser?.isAttached == true }.map { it.id }
        roster.overBudget(live, liveBudget(), keep = busy).forEach { id ->
            val tab = tabs[id] ?: return@forEach
            keepPage(tab)
            tab.browser?.detach()
            tab.browser = null
        }
    }

    /**
     * A picture of the page as it stands, for the tab's card. Only a WebView that has been through
     * a layout can be drawn, which is every tab that has been on screen - a tab opened in the
     * background and never shown is laid out to [pageSize] when it is built, so it has one too.
     *
     * A page is drawn by hand onto a software canvas: what a hardware surface is showing - a video
     * frame, a protected stream - comes out black, exactly as it does in a browser's own switcher.
     * It is slow enough to be worth noticing - Chromium renders the page again in software - so it
     * is only ever done where a wait is expected: as a switcher opens, and as a page is given up.
     */
    private fun capture(tab: Tab): Boolean {
        val webView = tab.browser?.takeIf { it.isAttached }?.webView ?: return false
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0) return false
        pageSize = width to height
        val scale = minOf(previewWidth().toFloat() / width, 1f)
        // A picture is worth nothing beside the browser it is of: a page too big to draw is skipped.
        val picture = runCatching {
            Bitmap.createBitmap(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.RGB_565,
            ).also { bitmap ->
                Canvas(bitmap).apply {
                    scale(scale, scale)
                    webView.draw(this)
                }
            }
        }.getOrNull() ?: return false
        tab.preview = picture
        return true
    }

    /** Saves the tab's page so it can be restored; its address is the fallback when it cannot be. */
    private fun keepPage(tab: Tab) {
        capture(tab)
        val browser = tab.browser?.takeIf { it.isAttached } ?: return
        val bundle = Bundle()
        tab.saved = bundle.takeIf { browser.webView.saveState(it) != null }
        if (tab.saved == null) tab.pending = tab.url
    }

    private fun remember(tab: Tab) {
        closed.addLast(ClosedTab(tab.url, tab.title, tab.saved, tab.preview))
        while (closed.size > Browser.CLOSED_TABS_KEPT) closed.removeFirst()
    }

    private fun letGoOfPages() {
        tabs.values.forEach { tab ->
            keepPage(tab)
            tab.browser?.detach()
            tab.browser = null
        }
        _activeBrowser.value = null
    }

    private fun onSelectionChanged() {
        val active = roster.activeId?.let(tabs::get)
        _activeBrowser.value = active?.browser
        showing(active?.session?.detector)
    }

    /**
     * A link with a target of its own, or a page opening a window on a tap, becomes a tab beside
     * the one that opened it. The WebView is handed over before anything is loaded in it, which is
     * why the tab is opened with no address: the page itself puts one there a moment later.
     */
    private fun windowsOf(id: String): BrowserTabHost = object : BrowserTabHost {

        override fun openWindow(): WebView? {
            busy = id
            try {
                return openTab(url = "", select = true, openerId = id)
                    ?.let { tabs[it]?.browser?.webView }
            } finally {
                busy = null
            }
        }

        /** Never from inside the callback: the WebView being closed is the one reporting it. */
        override fun closeWindow() {
            Handler(Looper.getMainLooper()).post { close(id) }
        }

        override fun onIcon(icon: Bitmap?) {
            val tab = tabs[id] ?: return
            tab.icon = icon
            publish()
        }
    }

    private fun publish() {
        _state.value = BrowserTabsUiState(
            tabs = roster.ids.mapNotNull { id -> tabs[id]?.toUiState(isActive = id == roster.activeId) },
            activeId = roster.activeId,
            canOpenMore = roster.canOpenMore,
            canRestoreClosed = closed.isNotEmpty(),
        )
    }

    private fun Tab.toUiState(isActive: Boolean) = BrowserTabUiState(
        id = id,
        url = url,
        title = title,
        progress = progress,
        favicon = icon,
        preview = preview,
        isActive = isActive,
        hasLivePage = browser?.isAttached == true,
    )

    /** One tab: a session for as long as it is open, and a page only while it is worth one. */
    private class Tab(val id: String, val session: PageSession) {
        var browser: MediaBrowser? = null
        var saved: Bundle? = null

        /** An address to open once the tab has a WebView to open it in. */
        var pending: String = ""
        var url: String = ""
        var title: String = ""
        var progress: Int = 0
        var icon: Bitmap? = null

        /** The last picture taken of its page, for a card in a tab switcher. */
        var preview: Bitmap? = null

        /** Its renderer died and has not been answered yet; the state says so until a page lands. */
        var crashed: Boolean = false
    }

    private class ClosedTab(
        val url: String,
        val title: String,
        val saved: Bundle?,
        val preview: Bitmap?,
    )

    /** The screen the tabs are drawn on, and what their WebViews are built from. */
    private class Screen(
        val context: Context,
        val lifecycleOwner: LifecycleOwner,
        val webViewClient: WebViewClient?,
        val webChromeClient: WebChromeClient?,
    )
}
