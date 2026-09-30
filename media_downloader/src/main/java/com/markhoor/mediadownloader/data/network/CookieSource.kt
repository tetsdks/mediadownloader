package com.markhoor.mediadownloader.data.network

import android.webkit.CookieManager

/** The cookies a signed-in browser holds for a url, so a scraper can see what the user sees. */
internal fun interface CookieSource {
    fun cookiesFor(url: String): String?

    /** Writes the cookies held in memory to disk, so a signed-in session survives the process. */
    fun flush() {}
}

/** The WebView's own cookie store. Throws nothing: a device without WebView simply has no cookies. */
internal object WebViewCookieSource : CookieSource {
    override fun cookiesFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun flush() {
        runCatching { CookieManager.getInstance().flush() }
    }
}
