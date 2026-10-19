package com.markhoor.mediadownloader.data.network

import android.webkit.CookieManager
import com.markhoor.mediadownloader.core.Constants.Instagram
import com.markhoor.mediadownloader.core.cookieValue
import com.markhoor.mediadownloader.core.isSiteOf

/** The cookies a signed-in browser holds for a url, so a scraper can see what the user sees. */
internal fun interface CookieSource {
    fun cookiesFor(url: String): String?

    /** Writes the cookies held in memory to disk, so a signed-in session survives the process. */
    fun flush() {}
}

/**
 * The browser's own cookies, and when the browser holds no Instagram session, the one the host app
 * supplied in `MediaDownloaderConfig.instagramCookie`.
 *
 * Instagram gives a signed-out request a page with no media in it at all, so without a session
 * every reader of a post comes back empty. The user's own session is the right one to use and is
 * tried first; a host that has arranged a session of its own can keep links working for a user who
 * has not signed in.
 *
 * Only Instagram's own urls are given the supplied cookie: it is one site's session and means
 * nothing to another, and Threads' session is not Instagram's even though one account opens both.
 */
internal class FallbackCookieSource(
    private val browser: CookieSource,
    /** Asked each time: a session expires, and a host app may hand over a fresh one. */
    private val supplied: () -> String?,
) : CookieSource {

    /** The fixed cookie a test or a pure caller gives. */
    constructor(browser: CookieSource, supplied: String?) : this(browser, { supplied })

    private val usable: String? get() = supplied()?.trim()?.takeIf { it.isNotBlank() }

    override fun cookiesFor(url: String): String? {
        val own = browser.cookiesFor(url)
        if (own?.cookieValue(Instagram.SESSION_COOKIE) != null) return own
        return usable?.takeIf { url.isSiteOf("instagram.com") } ?: own
    }

    override fun flush() = browser.flush()
}

/** The WebView's own cookie store. Throws nothing: a device without WebView simply has no cookies. */
internal object WebViewCookieSource : CookieSource {
    override fun cookiesFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun flush() {
        runCatching { CookieManager.getInstance().flush() }
    }
}
