package com.markhoor.mediadownloader

import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.presentation.browser.BrowserTabs
import com.markhoor.mediadownloader.presentation.browser.BrowserViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.resume

/**
 * Incognito browsing on a real device, in the device's own WebView: whether this phone can keep a
 * second cookie jar at all, whether an incognito page's cookies really are kept out of the ordinary
 * jar and the ordinary ones out of its, whether the session is thrown away when the last incognito
 * page closes, and whether an incognito tab stays out of the tabs kept between runs.
 *
 * Also the one thing about [MediaDownloader.reconfigure] that only the real thing can show: that a
 * setting changed after start is honoured by the use case the whole module is already holding.
 *
 * Needs a connection: cookies need a real origin, and `https://example.com/` is the smallest real
 * page there is.
 */
@RunWith(AndroidJUnit4::class)
class IncognitoDeviceCheck {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<BrowserCheckActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    // region What this device can actually give

    @Test
    fun sayWhatThisDeviceCanGive() {
        MediaDownloader.initialize(context)
        val privacy = MediaDownloader.incognitoPrivacy
        report("incognitoPrivacy on this device: $privacy")
        // Not an assertion about which: the answer is the WebView's, and both are legitimate. What
        // matters is that it is answered at all rather than guessed, and that the rest of this
        // check reads the same answer.
        assertTrue(privacy == IncognitoPrivacy.Separate || privacy == IncognitoPrivacy.SharedJar)
    }

    // endregion

    // region Cookies

    /**
     * A cookie set in an incognito page is not in the ordinary jar, and one set in an ordinary page
     * is not in the incognito jar. This is the whole promise, and the only place it can be checked
     * is a device whose WebView can keep two jars - where it cannot, the check says so and stops
     * rather than passing on a promise that was never made.
     */
    @Test
    fun anIncognitoPageAndAnOrdinaryOneDoNotShareCookies() = runBlocking {
        MediaDownloader.initialize(context)
        if (MediaDownloader.incognitoPrivacy != IncognitoPrivacy.Separate) {
            report("this WebView keeps one jar only; nothing to isolate. SharedJar is the answer given.")
            return@runBlocking
        }
        val tabs = tabs()

        val ordinary = onMain { tabs.open(PAGE) }
        awaitPage(tabs, PAGE)
        evaluate(tabs, "document.cookie='mdOrdinary=1;path=/';document.cookie")
        assertTrue("the ordinary page did not keep its own cookie", cookies(tabs).contains("mdOrdinary"))

        onMain { tabs.openIncognito(PAGE) }
        awaitPage(tabs, PAGE)
        val inIncognito = cookies(tabs)
        report("incognito page sees: $inIncognito")
        assertFalse("the ordinary jar leaked into incognito: $inIncognito", inIncognito.contains("mdOrdinary"))

        evaluate(tabs, "document.cookie='mdIncognito=1;path=/';document.cookie")
        assertTrue("the incognito page did not keep its own cookie", cookies(tabs).contains("mdIncognito"))

        onMain { tabs.select(ordinary!!) }
        awaitPage(tabs, PAGE)
        val backInOrdinary = cookies(tabs)
        report("ordinary page sees: $backInOrdinary")
        assertFalse("incognito leaked into the ordinary jar: $backInOrdinary", backInOrdinary.contains("mdIncognito"))
        assertTrue("the ordinary page lost its own cookie", backInOrdinary.contains("mdOrdinary"))
    }

    /** Closing the last incognito tab empties the jar: the next incognito page starts clean. */
    @Test
    fun theLastIncognitoTabOutEmptiesTheJar() = runBlocking {
        MediaDownloader.initialize(context)
        if (MediaDownloader.incognitoPrivacy != IncognitoPrivacy.Separate) {
            report("this WebView keeps one jar only; there is no incognito jar to empty.")
            return@runBlocking
        }
        val tabs = tabs()
        onMain { tabs.open(PAGE) } // One ordinary tab, so closing the incognito one leaves a tab behind.
        awaitPage(tabs, PAGE)

        val first = onMain { tabs.openIncognito(PAGE) }
        awaitPage(tabs, PAGE)
        evaluate(tabs, "document.cookie='mdGone=1;path=/';document.cookie")
        assertTrue(cookies(tabs).contains("mdGone"))

        // A second incognito tab holds the session while the first closes: it must not be emptied
        // under a page that is still browsing in it.
        val second = onMain { tabs.openIncognito(PAGE) }
        awaitPage(tabs, PAGE)
        onMain { tabs.close(first!!) }
        delay(1_500)
        assertTrue("the jar was emptied while a second incognito tab was still open", cookies(tabs).contains("mdGone"))

        onMain { tabs.close(second!!) }
        delay(2_500)

        onMain { tabs.openIncognito(PAGE) }
        awaitPage(tabs, PAGE)
        val afterwards = cookies(tabs)
        report("a fresh incognito page sees: $afterwards")
        assertFalse("the closed incognito session was still there: $afterwards", afterwards.contains("mdGone"))
    }

    // endregion

    // region What is written down

    /** An incognito tab is never in the tabs kept between runs; an ordinary one is. */
    @Test
    fun anIncognitoTabIsNotKeptBetweenRuns() = runBlocking {
        MediaDownloader.initialize(context)
        val tabs = tabs()
        // An address of its own, because the store is the module's own and already carries the
        // ordinary tabs the checks above opened - "example.com is in the file" would prove nothing.
        onMain {
            tabs.open(ORDINARY_MARKER)
            tabs.openIncognito(INCOGNITO_MARKER)
        }
        awaitPage(tabs, INCOGNITO_MARKER)
        // The file is written off the main thread when what is in it would change.
        delay(2_000)

        val kept = File(context.filesDir, "media_downloader/tabs.json").takeIf { it.isFile }?.readText().orEmpty()
        report("tabs.json: $kept")
        assertTrue("the ordinary tab was not kept", kept.contains("md-ordinary"))
        assertFalse("an incognito tab was written down: $kept", kept.contains("md-incognito"))

        val state = tabs.state.value
        assertTrue("no tab is marked incognito", state.tabs.any { it.isIncognito })
        assertTrue("an ordinary tab was marked incognito", state.tabs.any { !it.isIncognito })
        assertTrue(tabs.hasIncognitoTabs)

        val closed = onMain { tabs.closeIncognito() }
        report("closeIncognito() closed $closed")
        assertEquals(1, closed)
        assertFalse(tabs.hasIncognitoTabs)
    }

    // endregion

    // region Reconfiguring after start

    /**
     * A setting changed after start reaches the use case the module is already holding - the one
     * the browser screens and the downloads in flight ask - and a setting that cannot change is
     * named instead of being silently dropped.
     */
    @Test
    fun aSettingChangedAfterStartIsHonouredAndAFixedOneIsNamed() {
        MediaDownloader.initialize(context)
        val adult = "https://www.pornhub.com/view_video.php?viewkey=1"
        val unlisted = "https://www.example.com/watch/1"
        val before = MediaDownloader.config

        try {
            assertEquals(SiteAccess.Blocked, MediaDownloader.siteAccess(adult))
            assertEquals(SiteAccess.Unsupported, MediaDownloader.siteAccess(unlisted))

            val fixed = MediaDownloader.reconfigure {
                it.copy(
                    allowAdultSites = true,
                    strictSupportedSitesOnly = false,
                    performanceMode = if (it.performanceMode == PerformanceMode.LowEnd) {
                        PerformanceMode.Standard
                    } else {
                        PerformanceMode.LowEnd
                    },
                )
            }
            report("reconfigure could not change: $fixed")

            assertEquals(setOf(FixedSetting.PerformanceMode), fixed)
            assertEquals("the lifted block was not honoured", SiteAccess.Allowed, MediaDownloader.siteAccess(adult))
            assertEquals("strict mode was not left", SiteAccess.Allowed, MediaDownloader.siteAccess(unlisted))
            assertTrue(MediaDownloader.config.allowAdultSites)
            assertEquals("a fixed setting was changed anyway", before.performanceMode, MediaDownloader.config.performanceMode)
        } finally {
            MediaDownloader.reconfigure(before)
        }
        assertEquals(SiteAccess.Blocked, MediaDownloader.siteAccess(adult))
    }

    // endregion

    private fun tabs(): BrowserTabs {
        val launched = ActivityScenario.launch(BrowserCheckActivity::class.java).also { scenario = it }
        val ready = CompletableDeferred<BrowserTabs>()
        launched.onActivity { activity ->
            val viewModel = ViewModelProvider(activity, BrowserViewModel.Factory)[BrowserViewModel::class.java]
            ready.complete(viewModel.tabs(activity, activity))
        }
        return runBlocking { ready.await() }
    }

    /** The WebView of the tab on screen, once it has one. */
    private fun shownWebView(tabs: BrowserTabs): WebView? = tabs.activeBrowser.value?.webView

    /**
     * Waits for the tab on screen to be showing [url], loaded. Waiting only for `readyState` is not
     * enough: for a moment after a tab is opened the tab before it is still the one on screen, and
     * that one is already complete - so the wait came back at once and the cookie was set on a page
     * with no origin, where it is simply dropped.
     */
    private suspend fun awaitPage(tabs: BrowserTabs, url: String) {
        val expected = url.substringAfter("://").substringBefore('/')
        withTimeout(PAGE_TIMEOUT_MS) {
            while (true) {
                val webView = shownWebView(tabs)
                if (webView != null &&
                    evaluateOn(webView, "document.readyState") == "\"complete\"" &&
                    evaluateOn(webView, "location.host").contains(expected)
                ) {
                    return@withTimeout
                }
                delay(150)
            }
        }
    }

    private suspend fun cookies(tabs: BrowserTabs): String = evaluate(tabs, "document.cookie")

    private suspend fun evaluate(tabs: BrowserTabs, script: String): String =
        shownWebView(tabs)?.let { evaluateOn(it, script) } ?: ""

    private suspend fun evaluateOn(webView: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(script) { value -> continuation.resume(value.orEmpty()) }
            }
        }

    private fun <T> onMain(action: () -> T): T {
        var answer: Result<T> = Result.failure(IllegalStateException("not run"))
        instrumentation.runOnMainSync { answer = runCatching(action) }
        return answer.getOrThrow()
    }

    private fun report(line: String) {
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "MDCheck"
        const val PAGE_TIMEOUT_MS = 60_000L

        /** The smallest real page there is, and a real origin, which cookies need. */
        const val PAGE = "https://example.com/"

        /** The same page under addresses no other check uses, so the saved tabs can be read. */
        const val ORDINARY_MARKER = "https://example.com/?md-ordinary"
        const val INCOGNITO_MARKER = "https://example.com/?md-incognito"
    }
}
