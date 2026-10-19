package com.markhoor.mediadownloader

import android.Manifest
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.presentation.browser.BrowserViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * What this round of review changed, checked on a real device rather than on a reading of the
 * code: a video whose sound is a second file, and the fallback at the end of `twitter.js`.
 *
 * Needs a connection. Files are left in Download/ so they can be played.
 */
@RunWith(AndroidJUnit4::class)
class SoundAndScriptDeviceCheck {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<BrowserCheckActivity>? = null

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        MediaDownloader.initialize(context)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    // region A video whose sound is a second file

    /**
     * Sound lost to a dropped connection, which is the half of this that a reader actually sees.
     * The whole fetch was wrapped in `runCatching`, so a connection that broke mid-sound was read
     * as sound that is gone for good: the silent picture was published, the download was called
     * complete, and nothing ever asked for the sound again.
     *
     * The two tracks come from a socket on the device's own loopback, which hands over the picture
     * whole and then cuts the sound off mid-body. That is the one part a CDN cannot be asked to do
     * on cue, and everything else is real: OkHttp reads it, the worker runs it, Room holds it, and
     * the file is published to Download/ or it is not.
     *
     * Run against the code before the fix this ends `Completed` within seconds, with a silent
     * file published.
     */
    @Test
    fun aSoundLostToADroppedConnectionIsNotCalledComplete() = runBlocking<Unit> {
        CutOffServer(pictureBytes = 256 * 1024, soundBytes = 1024 * 1024, cutSoundAfter = 64 * 1024).use { server ->
            val id = MediaDownloader.download(
                DownloadRequest(
                    mediaUrl = server.pictureUrl,
                    audioUrl = server.soundUrl,
                    type = MediaType.Video,
                    title = "review dropped sound check",
                    sourceUrl = server.pictureUrl,
                ),
            ).getOrThrow()

            // Long enough that the download would have finished several times over if it were
            // going to: before the fix it published in about five seconds.
            val finished = withTimeoutOrNull(DROPPED_SOUND_WINDOW_MS) {
                MediaDownloader.observeDownload(id).filterNotNull().first { it.state == DownloadState.Completed }
            }
            val now = MediaDownloader.observeDownload(id).filterNotNull().first()
            report("dropped sound: ended as ${now.state}, ${now.downloadedBytes} bytes, error=${now.errorMessage}")
            report("the server served ${server.requests}")

            assertEquals("the download finished with the sound cut off", null, finished)
            assertNotEquals(
                "a sound lost to a dropped connection was published as a silent video",
                DownloadState.Completed,
                now.state,
            )
            MediaDownloader.delete(id, deleteFile = true)
        }
    }

    /**
     * A pause between the picture arriving and the sound doing leaves both where the next run can
     * use them. A guard, not a reproduction: measured on this device, the picture survived the
     * pause before the fix too, because `markFinishing` is a suspend call and throws on the
     * cancelled coroutine before the picture can be moved out of the work dir. What the fix
     * changes here is that the cancellation is no longer swallowed at all, rather than being
     * swallowed and then thrown by the next thing that suspends.
     */
    @Test
    fun aPauseBetweenThePictureAndTheSoundKeepsBoth() = runBlocking {
        val (picture, sound) = twoRealFiles()
        val id = MediaDownloader.download(
            DownloadRequest(
                mediaUrl = picture.url,
                audioUrl = sound.url,
                type = MediaType.Video,
                title = "review two track check",
                sourceUrl = SOUND_LINKS.first(),
                headers = picture.headers,
                expectedSizeBytes = picture.sizeBytes,
            ),
        ).getOrThrow()

        val work = File(context.filesDir, "media_downloader/$id") // Storage.TEMP_FOLDER/<id>
        val videoTrack = File(work, "video.track")
        val soundTrack = File(work, "audio.track")
        val soundParts = File(work, "audio")
        // The sound has started. A file big enough to be split has parts in its own scratch dir; a
        // smaller one is written straight into its track. Either says the picture is done and the
        // second track is on its way, which is the moment the pause has to land in.
        withTimeout(DOWNLOAD_TIMEOUT_MS) {
            while (soundTrack.length() <= 0 &&
                soundParts.listFiles().orEmpty().none { it.name.startsWith("part-") && it.length() > 0 }
            ) {
                delay(25)
            }
        }
        report("sound started; work dir holds ${listing(work)}")
        assertTrue("pause was refused", MediaDownloader.pause(id).isSuccess)
        delay(4_000)

        report("after the pause, work dir holds ${listing(work)}")
        assertEquals(DownloadState.Paused, current(id))
        assertTrue(
            "the picture is not in the work dir, so resuming refetches all of it: ${listing(work)}",
            videoTrack.isFile && videoTrack.length() > 0,
        )

        assertTrue("resume was refused", MediaDownloader.resume(id).isSuccess)
        val done = withTimeout(DOWNLOAD_TIMEOUT_MS) {
            MediaDownloader.observeDownload(id).filterNotNull()
                .first { it.state == DownloadState.Completed || it.state == DownloadState.Failed }
        }
        assertEquals(done.errorMessage, DownloadState.Completed, done.state)
        inspect(File(done.filePath))
    }

    /**
     * Two real files, the largest the readers actually answer with today. Measured on the device,
     * nothing these links offer reaches the 4 MB a file needs to be split into parallel parts - the
     * biggest is TikTok's 3.4 MB watermarked copy - so the part names the two tracks once shared
     * are not what this proves; `SoundTrackResumeTest` proves that on the JVM, where the sizes can
     * be chosen. Where a link offers only one such file it stands as both tracks.
     */
    private suspend fun twoRealFiles(): Pair<MediaQualityModel, MediaQualityModel> {
        val problems = mutableListOf<String>()
        for (link in SOUND_LINKS) {
            val media = MediaDownloader.parse(link).getOrElse { problems += "$link: ${it.message}"; continue }
            val big = media.qualities
                .filter { it.type == MediaType.Video && !it.url.contains(".m3u8") && (it.sizeBytes ?: 0) >= LEAST_BYTES }
                .sortedByDescending { it.sizeBytes ?: 0 }
            if (big.isEmpty()) {
                problems += "$link: no plain file over $LEAST_BYTES bytes in ${media.qualities.map { it.label to it.sizeBytes }}"
                continue
            }
            // The larger one is the sound, so the pause has the wider window to land in.
            val sound = big.first()
            val picture = big.getOrNull(1) ?: big.first()
            report("two-track check uses $link: picture ${picture.label}/${picture.sizeBytes}, sound ${sound.label}/${sound.sizeBytes}")
            return picture to sound
        }
        error("no link answered with a file big enough:\n" + problems.joinToString("\n"))
    }

    private suspend fun current(id: Long): DownloadState =
        MediaDownloader.observeDownload(id).filterNotNull().first().state

    private fun listing(dir: File): String =
        dir.walkTopDown().filter { it != dir }.joinToString { it.relativeTo(dir).path + "=" + it.length() }

    private fun inspect(file: File) {
        check(file.isFile && file.length() > 0) { "no file at ${file.path}" }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            report("${file.path} ${file.length()} bytes duration=${duration}ms video=$hasVideo audio=$hasAudio")
            check(duration > 0) { "no running time in ${file.path}" }
            check(hasVideo == "yes") { "no picture in ${file.path}" }
            check(hasAudio == "yes") { "no sound in ${file.path}" }
        } finally {
            retriever.release()
        }
    }

    /**
     * Two files on the device's own loopback: `/picture` is served whole, and `/sound` states its
     * length and then stops short and closes - which is what a connection dropping mid-file looks
     * like to the client, as against a server saying the file is gone.
     *
     * Ranges are not offered, so each track is fetched in one piece: the shape a file under
     * `MIN_PART_BYTES` is fetched in anyway.
     */
    private class CutOffServer(
        private val pictureBytes: Int,
        private val soundBytes: Int,
        private val cutSoundAfter: Int,
    ) : Closeable {

        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val seen = mutableListOf<String>()

        val pictureUrl: String get() = "http://127.0.0.1:${socket.localPort}/picture.mp4"
        val soundUrl: String get() = "http://127.0.0.1:${socket.localPort}/sound.m4a"
        val requests: List<String> get() = synchronized(seen) { seen.toList() }

        init {
            thread(isDaemon = true, name = "cut-off-server") {
                while (!socket.isClosed) {
                    runCatching { socket.accept() }.getOrNull()?.let { client ->
                        thread(isDaemon = true) { runCatching { serve(client) } }
                    }
                }
            }
        }

        private fun serve(client: Socket) = client.use {
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine().orEmpty()
            // The headers, to the blank line: unread, the client's write can block on a full buffer.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            synchronized(seen) { seen += requestLine }
            val sound = "/sound" in requestLine
            val length = if (sound) soundBytes else pictureBytes
            val send = if (sound) cutSoundAfter else pictureBytes
            val output = client.getOutputStream()
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: ${if (sound) "audio/mp4" else "video/mp4"}\r\n" +
                        "Content-Length: $length\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            val block = ByteArray(8 * 1024) { (it % 251).toByte() }
            var written = 0
            while (written < send) {
                val next = minOf(block.size, send - written)
                output.write(block, 0, next)
                written += next
            }
            output.flush()
            // Stated more than was sent, then closed: the body ends before the client expects it to.
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    // endregion

    // region The X video fallback

    /**
     * The last sweep in `twitter.js`, in the device's own Chromium, over the two shapes a `<video>`
     * on X comes in: one in an ordinary timeline, which sits in no `videoComponent` at all, and one
     * in the immersive player, which does. Both lookups were read straight off, so the first video
     * threw a TypeError that nothing caught - taking the rest of the sweep, and the perfectly good
     * video behind it, with it.
     *
     * Measured on this device before the fix: `threw TypeError: Cannot read properties of null
     * (reading 'querySelector')`, and no buttons at all.
     */
    @Test
    fun theXVideoFallbackSkipsAVideoItCannotReadAndGoesOn() = runBlocking {
        val webView = browserWebView()
        load(webView, TIMELINE_AND_IMMERSIVE_PAGE)
        val script = context.assets.open("media_downloader/twitter.js").bufferedReader().use { it.readText() }

        // Wrapped so a throw is an answer rather than a silence; the script's own `var`s are
        // function-scoped here exactly as they are inside an injection.
        val outcome = evaluate(webView, "(function(){try{$script\nreturn 'ran to the end';}catch(e){return 'threw '+e.name+': '+e.message;}})()")
        val buttons = evaluate(webView, "document.querySelectorAll('i.downbtn').length")
        report("X fallback on a real WebView: $outcome, buttons=$buttons")

        assertEquals("the sweep did not finish", "\"ran to the end\"", outcome)
        assertEquals("the immersive video should get the one button", "1", buttons)
    }

    /**
     * No article and no picture, so the article and image passes draw nothing and the video
     * fallback is the one that runs - which is the only way to reach it.
     */
    private val TIMELINE_AND_IMMERSIVE_PAGE = """
        <!doctype html><html><body>
          <div><video style="width:200px;height:120px"></video></div>
          <div data-testid="videoComponent">
            <div data-testid="immersive-video-controls-1234567890123456789"></div>
            <div><video style="width:200px;height:120px"></video></div>
          </div>
        </body></html>
    """.trimIndent()

    private fun browserWebView(): WebView {
        val launched = ActivityScenario.launch(BrowserCheckActivity::class.java).also { scenario = it }
        val ready = CompletableDeferred<WebView>()
        launched.onActivity { activity ->
            // Attached, so the page has the real bridge in it and the script's calls resolve.
            ViewModelProvider(activity, BrowserViewModel.Factory)[BrowserViewModel::class.java]
                .attach(activity.webView, activity)
            ready.complete(activity.webView)
        }
        return runBlocking { ready.await() }
    }

    private suspend fun load(webView: WebView, html: String) {
        onMain { webView.loadDataWithBaseURL("https://x.com/", html, "text/html", "utf-8", null) }
        withTimeout(PAGE_TIMEOUT_MS) {
            while (evaluate(webView, "document.querySelectorAll('video').length") != "2") delay(100)
        }
    }

    private suspend fun evaluate(webView: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            onMain { webView.evaluateJavascript(script) { value -> continuation.resume(value.orEmpty()) } }
        }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    // endregion

    private fun report(line: String) {
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "MDCheck"
        const val DOWNLOAD_TIMEOUT_MS = 8 * 60_000L
        const val PAGE_TIMEOUT_MS = 60_000L
        const val DROPPED_SOUND_WINDOW_MS = 45_000L

        /** Small enough that today's links clear it, big enough that a pause can land mid-sound. */
        const val LEAST_BYTES = 1_500_000L

        /** Read for a plain file big enough to pause inside; the first that answers with one is used. */
        val SOUND_LINKS = listOf(
            "https://www.tiktok.com/@2brother4100/video/7682479112115588360",
            "https://www.pinterest.com/pin/62628251062820323/",
            "https://www.pinterest.com/pin/211174979574445/",
        )
    }
}
