package com.markhoor.mediadownloader.data.browser

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.markhoor.mediadownloader.IncognitoPrivacy
import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.data.network.CookieSource

/**
 * The one WebView profile every incognito page in the process browses in.
 *
 * One rather than one each, because a profile cannot be thrown away: `deleteProfile` refuses a
 * profile that has been loaded in this process at all, and loading it is the only way to reach its
 * cookie jar. So the profile is kept and **emptied** instead, which is what the reader is promised
 * anyway - nothing of an incognito session is left to read afterwards.
 *
 * Who wants it is counted, the way a download's lock counts its runs: a tab holds its claim for as
 * long as it is open, across losing and regaining its WebView to the live-page budget, and the jar
 * is emptied when the count reaches zero. Emptying on the first close would wipe the session of
 * every other incognito tab still on screen.
 *
 * Every method is for the main thread, which is where tabs are opened and closed.
 */
internal class IncognitoProfile(
    private val isSupported: () -> Boolean = ::isMultiProfileSupported,
    /** What emptying the jar does; a parameter so the counting can be read in a plain test. */
    private val emptyJar: () -> Unit = ::emptyIncognitoJar,
) {

    /** What an incognito page can be given here; decided by the WebView, not by the SDK level. */
    val privacy: IncognitoPrivacy by lazy {
        if (isSupported()) IncognitoPrivacy.Separate else IncognitoPrivacy.SharedJar
    }

    private var wanted = 0

    /** How many incognito pages are open, so a test can watch the jar being emptied. */
    val claims: Int get() = wanted

    /** The name to browse under, or `null` where there is no profile to be had. */
    private val name: String? get() = Browser.INCOGNITO_PROFILE.takeIf { privacy == IncognitoPrivacy.Separate }

    /**
     * Takes a claim on the profile for one incognito page. The caller must [release] exactly once,
     * whatever happens to its WebView in between.
     */
    fun claim() {
        wanted++
    }

    /** Gives a claim up. The last one out empties the jar. */
    fun release() {
        if (wanted == 0) return
        if (--wanted > 0) return
        emptyJar()
    }

    /**
     * Puts [webView] in the incognito profile. It has to be the first thing done to it: the
     * platform refuses a WebView that has already navigated, already had a script run in it, or
     * already been given a profile. `false` means it is browsing in the ordinary profile after
     * all - which is what a host's own, already-used WebView will do.
     */
    fun applyTo(webView: WebView): Boolean {
        if (name == null || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return false
        return runCatching { WebViewCompat.setProfile(webView, Browser.INCOGNITO_PROFILE) }
            .onFailure { error -> Log.w(Download.LOG_TAG, "This WebView cannot be made incognito", error) }
            .isSuccess
    }

    /**
     * The cookies of incognito browsing: the profile's own jar where there is one, and never
     * written to disk either way. [CookieSource.flush] is what keeps a signed-in session across a
     * restart, and an incognito session is the one that must not survive one - so here it does
     * nothing, which is also what stops the browser's Instagram sign-in being written down.
     */
    fun cookies(): CookieSource = object : CookieSource {
        override fun cookiesFor(url: String): String? =
            jar()?.let { manager -> runCatching { manager.getCookie(url) }.getOrNull() }
                ?.takeIf { it.isNotBlank() }

        override fun flush() = Unit
    }

    private fun jar(): CookieManager? {
        if (name == null || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return null
        return runCatching {
            ProfileStore.getInstance().getOrCreateProfile(Browser.INCOGNITO_PROFILE).cookieManager
        }.getOrNull()
    }
}

/**
 * Whether this WebView can keep a profile other than the default.
 *
 * Guarded, because this one can be asked before any WebView exists - a host deciding whether to
 * draw an incognito button - and a device with no WebView at all answers by throwing. Every call
 * that actually touches a profile asks the platform again, in its own body: that is what
 * `@RequiresFeature` asks for, and lint follows no further than the method it is in.
 */
private fun isMultiProfileSupported(): Boolean =
    runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

/**
 * Empties the incognito jar: its cookies and its web storage. Nothing of the session is left to
 * read, which is as close to throwing the profile away as the platform allows - `deleteProfile`
 * refuses one that has been loaded in this process, and reaching its cookies loads it.
 *
 * Does nothing where there is no second profile to be had; there is then nothing of its own in it.
 */
private fun emptyIncognitoJar() {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
    runCatching {
        val profile = ProfileStore.getInstance().getOrCreateProfile(Browser.INCOGNITO_PROFILE)
        profile.cookieManager.removeAllCookies(null)
        profile.cookieManager.flush()
        profile.webStorage.deleteAllData()
    }.onFailure { error -> Log.w(Download.LOG_TAG, "The incognito session could not be emptied", error) }
}
