package com.markhoor.mediadownloader

import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.presentation.browser.BrowserEvent
import com.markhoor.mediadownloader.presentation.browser.BrowserUiState
import com.markhoor.mediadownloader.presentation.browser.BrowserViewModel
import com.markhoor.mediadownloader.presentation.browser.MediaBrowser
import com.markhoor.mediadownloader.presentation.browser.PageMediaState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The browser on a real device and real pages: a parser page found without a tap, the generic
 * script's buttons drawn and pressed, a blocked site left alone, and a renderer crash survived.
 * Run with `-Pandroid.testInstrumentationRunnerArguments.performanceMode=LowEnd` for the low-end path.
 */
@RunWith(AndroidJUnit4::class)
class BrowserDeviceCheck {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scenario = ActivityScenario.launch(BrowserCheckActivity::class.java)
    private val lowEnd = InstrumentationRegistry.getArguments().getString("performanceMode") == PerformanceMode.LowEnd.name

    private class Screen(val viewModel: BrowserViewModel, val browser: MediaBrowser, val webView: WebView)

    private fun screen(): Screen {
        val ready = CompletableDeferred<Screen>()
        scenario.onActivity { activity ->
            val viewModel = ViewModelProvider(activity, BrowserViewModel.Factory)[BrowserViewModel::class.java]
            ready.complete(Screen(viewModel, viewModel.attach(activity.webView, activity), activity.webView))
        }
        return runBlocking { ready.await() }
    }

    /**
     * The WebViews are left alive on purpose, detached: the renderer is shared, so the crash test
     * that runs last also proves that a detached WebView does not take the app down with it.
     */
    @After
    fun tearDown() = scenario.close()

    /**
     * A parser page is found with no tap at all. Where the generic script puts its own button on
     * the video the host's floating one stays away - one button that says which video it means -
     * and pressing the script's button shows that video.
     */
    @Test
    fun parserPageIsFoundWithoutATapAndOneButtonShows() = runBlocking<Unit> {
        val screen = screen()
        onMain { screen.browser.load("https://www.dailymotion.com/video/x8ixblz") }

        val found = await("dailymotion media") { screen.viewModel.uiState.value.foundMedia(done = true) }
        report("dailymotion: ${found.title.take(50)} - ${found.qualities.map { "${it.label}:${it.sizeBytes}" }}")
        assertTrue(found.qualities.isNotEmpty())

        delay(SCRIPT_SETTLE_MS)
        val scriptButtons = evaluate(screen.webView, VISIBLE_BUTTONS).toIntOrNull() ?: 0
        val hostButton = screen.viewModel.uiState.value.showDownloadButton
        report("dailymotion: script buttons=$scriptButtons, host button=$hostButton")
        assertEquals("exactly one kind of button", scriptButtons == 0, hostButton)

        if (scriptButtons > 0) {
            evaluate(screen.webView, "document.querySelector('.mks-dl-btn').click()")
        } else {
            onMain { screen.browser.onDownloadButtonClick() }
        }
        assertEquals(BrowserEvent.ShowMedia, withTimeout(TIMEOUT_MS) { screen.viewModel.events.first() })
        val shown = await("media after the press") { screen.viewModel.uiState.value.foundMedia(done = true) }
        report("pressed: ${shown.title.take(50)} ${shown.qualities.size} qualities")
        assertTrue(shown.qualities.isNotEmpty())
    }

    @Test
    fun genericScriptDrawsButtonsAndItsTapBecomesADownload() = runBlocking<Unit> {
        val screen = screen()
        onMain { screen.browser.load("https://www.w3schools.com/html/html5_video.asp") }

        val buttons = await("generic buttons") {
            evaluate(screen.webView, "document.querySelectorAll('.mks-dl-btn').length").toIntOrNull()?.takeIf { it > 0 }
        }
        val scanScale = evaluate(screen.webView, "window.mksScanScale")
        val lowEndFlag = evaluate(screen.webView, "window.mksLowEnd")
        report("w3schools: $buttons buttons, mksScanScale=$scanScale mksLowEnd=$lowEndFlag")
        assertEquals(if (lowEnd) "2.5" else "1", scanScale)
        assertEquals(lowEnd.toString(), lowEndFlag)
        assertFalse(screen.viewModel.uiState.value.showDownloadButton)

        evaluate(screen.webView, "document.querySelector('.mks-dl-btn').click()")
        val media = await("tapped media") { screen.viewModel.uiState.value.foundMedia(done = true) }
        val quality = media.qualities.first()
        report("tapped: ${quality.url} size=${quality.sizeBytes} headers=${quality.headers.keys}")
        assertTrue(quality.url.contains(".mp4"))

        // The page's own file, asked for the way the page asks: w3schools refuses a bare request.
        val id = MediaDownloader.download(media, quality).getOrThrow()
        val done = withTimeout(TIMEOUT_MS) {
            MediaDownloader.observeDownload(id).filterNotNull()
                .first { it.state == DownloadState.Completed || it.state == DownloadState.Failed }
        }
        report("downloaded: ${done.state} ${done.filePath} ${File(done.filePath).length()} bytes ${done.errorMessage.orEmpty()}")
        assertEquals(done.errorMessage, DownloadState.Completed, done.state)
        File(done.filePath).delete()
        MediaDownloader.delete(id)
    }

    @Test
    fun blockedSiteGetsNoScriptAndNoMedia() = runBlocking<Unit> {
        val screen = screen()
        onMain { screen.browser.load("https://www.xvideos.com/") }
        await("blocked page committed") { screen.viewModel.uiState.value.takeIf { it.url.contains("xvideos") } }
        delay(8_000)

        val state = screen.viewModel.uiState.value
        val injected = evaluate(screen.webView, "typeof window.mksGenInit")
        report("blocked: access=${state.siteAccess} media=${state.media} button=${state.showDownloadButton} script=$injected")
        assertEquals(SiteAccess.Blocked, state.siteAccess)
        assertEquals(PageMediaState.None, state.media)
        assertFalse(state.showDownloadButton)
        assertEquals("\"undefined\"", injected)
    }

    @Test
    fun aRendererCrashIsReportedAndTheAppSurvives() = runBlocking<Unit> {
        val screen = screen()
        onMain { screen.webView.loadUrl("chrome://crash") }
        assertEquals(BrowserEvent.BrowserCrashed, withTimeout(TIMEOUT_MS) { screen.viewModel.events.first() })
        report("renderer crash reported; process alive")
        // What a host does next: take the dead WebView out and let it go.
        scenario.onActivity {
            (screen.webView.parent as? ViewGroup)?.removeView(screen.webView)
            screen.browser.detach()
            screen.webView.destroy()
        }
    }

    private fun BrowserUiState.foundMedia(done: Boolean) =
        (media as? PageMediaState.Found)?.takeIf { !done || !it.isDescribing }?.media

    private suspend fun <T : Any> await(what: String, probe: suspend () -> T?): T = withTimeout(TIMEOUT_MS) {
        var value: T? = probe()
        while (value == null) {
            delay(POLL_MS)
            value = probe()
        }
        value
    }.also { report("got $what") }

    private suspend fun evaluate(webView: WebView, script: String): String {
        val answer = CompletableDeferred<String>()
        instrumentation.runOnMainSync { webView.evaluateJavascript(script) { answer.complete(it.orEmpty()) } }
        return answer.await()
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun report(line: String) {
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "MDCheck"
        const val TIMEOUT_MS = 90_000L
        const val POLL_MS = 500L
        const val SCRIPT_SETTLE_MS = 5_000L
        const val VISIBLE_BUTTONS =
            "Array.from(document.querySelectorAll('.mks-dl-btn')).filter(function(b){return getComputedStyle(b).display!=='none'}).length"
    }
}
