package com.markhoor.mediadownloader.data.browser

import com.markhoor.mediadownloader.data.device.DeviceProfile
import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.download.sampleBytes
import com.markhoor.mediadownloader.data.hls.HlsQualityReader
import com.markhoor.mediadownloader.data.network.CookieSource
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.network.MediaSizeProbe
import com.markhoor.mediadownloader.domain.models.DetectionEvent
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.domain.models.PageCommand
import com.markhoor.mediadownloader.domain.models.PageSignal
import com.markhoor.mediadownloader.domain.models.ScriptMessage
import com.markhoor.mediadownloader.domain.models.ScriptSource
import com.markhoor.mediadownloader.domain.repo.MediaParserRepository
import com.markhoor.mediadownloader.domain.usecase.CheckSiteAccessUseCase
import com.markhoor.mediadownloader.domain.usecase.ParseLinkUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session lives in `backgroundScope`, as it lives in a ViewModel's scope in the app, so its work
 * is run with `runCurrent()` and `advanceTimeBy()`: `runCurrent()` does not run background work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaDetectionSessionTest {

    private val video = sampleBytes(300_000)
    private val videoUrl = "https://hugh.cdn.rumble.cloud/video/a.mp4"
    private val rumblePage = "https://rumble.com/v7exqzu-sorry-i-annoyed-you.html"

    private data class Harness(val session: MediaDetectionSession, val commands: MutableList<PageCommand>, val events: MutableList<DetectionEvent>)

    private fun TestScope.harness(
        files: Map<String, ByteArray> = mapOf(videoUrl to video),
        parse: suspend (String) -> Result<MediaModel> = { Result.failure(IllegalStateException("no parser")) },
        // Real network waits (a size probe's timeout) need real time; virtual time would expire them at once.
        realIo: Boolean = false,
        cookies: CookieSource = CookieSource { null },
    ): Harness {
        val server = FakeMediaServer(files)
        val fetcher = HttpFetcher(server.client, HttpClientFactory.json)
        val probe = MediaSizeProbe(server.client)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parseLink = ParseLinkUseCase(
            CheckSiteAccessUseCase(strictSupportedSitesOnly = true, extraBlockedHosts = emptySet()),
            object : MediaParserRepository {
                override suspend fun parse(url: String) = parse(url)
            },
        )
        val session = MediaDetectionSession(
            policyProvider = lazyOf(testPolicy()),
            scriptsProvider = lazyOf(ScriptLibrary({ path -> "/* $path */" }, DeviceProfile(isLowEnd = false), dispatcher)),
            locatorProvider = lazyOf(StreamLocator(fetcher, playlistProbesAtOnce = 4, blocksHostOf = testAccess()::blocksHostOf)),
            describerProvider = lazyOf(MediaDescriber(parseLink, HlsQualityReader(fetcher, probe), probe, sizeProbesAtOnce = 2)),
            parseLinkProvider = lazyOf(parseLink),
            cookies = cookies,
            injectIntervalProvider = lazyOf(500L),
            scope = backgroundScope,
            serialDispatcher = dispatcher,
            ioDispatcher = if (realIo) Dispatchers.IO else dispatcher,
            clock = { testScheduler.currentTime },
        )
        val commands = mutableListOf<PageCommand>()
        val events = mutableListOf<DetectionEvent>()
        backgroundScope.launch(dispatcher) { session.commands.collect { commands += it } }
        backgroundScope.launch(dispatcher) { session.events.collect { events += it } }
        return Harness(session, commands, events)
    }

    private fun MediaDetectionSession.commit(url: String, title: String = "") =
        onSignal(PageSignal.PageCommitted(url, title, isReload = false, canGoBack = false, canGoForward = false))

    @Test
    fun `a video the page requests is offered, named by its slug, then sized`() = runTest {
        val (session) = harness(realIo = true)
        session.onSignal(PageSignal.Attached("TestAgent"))
        session.commit(rumblePage)
        session.onSignal(PageSignal.RequestSeen(videoUrl, mapOf("Referer" to rumblePage)))

        val described = session.state.first { it.media?.qualities?.firstOrNull()?.sizeBytes != null }.media
        assertEquals("sorry i annoyed you", described?.title)
        assertEquals(video.size.toLong(), described?.qualities?.single()?.sizeBytes)
        assertEquals("TestAgent", described?.qualities?.single()?.headers?.get("User-Agent"))
        assertEquals(rumblePage, described?.sourceUrl)
    }

    @Test
    fun `the host's button waits for the generic script's answer and gives way to its buttons`() = runTest {
        val (session) = harness()
        session.commit(rumblePage)
        session.onSignal(PageSignal.RequestSeen(videoUrl, emptyMap()))
        session.state.first { it.media != null }
        assertFalse("before the script answers", session.state.value.showDownloadButton)

        session.onSignal(PageSignal.Script(ScriptMessage.ButtonsDrawn(0)))
        assertTrue(session.state.first { it.scriptAnswered }.showDownloadButton)

        session.onSignal(PageSignal.Script(ScriptMessage.ButtonsDrawn(2)))
        assertFalse(session.state.first { it.hasScriptButtons }.showDownloadButton)
    }

    @Test
    fun `background work that throws is logged, not raised into the screen's scope`() = runTest {
        // The WebView's cookie store throws while the WebView package is being updated.
        val (session) = harness(cookies = CookieSource { throw IllegalStateException("WebView is updating") })
        session.onSignal(PageSignal.PageFinished("https://www.instagram.com/reel/abc/"))
        runCurrent()

        // runTest fails on an exception left uncaught in its scopes; detection also carries on.
        session.commit(rumblePage)
        runCurrent()
        assertEquals(rumblePage, session.state.value.pageUrl)
    }

    @Test
    fun `making a session builds none of its collaborators, its first signals do`() = runTest {
        val builtOn = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        fun <T> tracked(value: () -> T) = lazy { builtOn += "built"; value() }
        val server = FakeMediaServer(emptyMap())
        val fetcher = HttpFetcher(server.client, HttpClientFactory.json)
        val probe = MediaSizeProbe(server.client)
        val parseLink = ParseLinkUseCase(
            CheckSiteAccessUseCase(strictSupportedSitesOnly = true, extraBlockedHosts = emptySet()),
            object : MediaParserRepository {
                override suspend fun parse(url: String) = Result.failure<MediaModel>(IllegalStateException("no parser"))
            },
        )
        val session = MediaDetectionSession(
            policyProvider = tracked { testPolicy() },
            scriptsProvider = tracked { ScriptLibrary({ "" }, DeviceProfile(isLowEnd = false), dispatcher) },
            locatorProvider = tracked { StreamLocator(fetcher, playlistProbesAtOnce = 4, blocksHostOf = testAccess()::blocksHostOf) },
            describerProvider = tracked { MediaDescriber(parseLink, HlsQualityReader(fetcher, probe), probe, sizeProbesAtOnce = 2) },
            parseLinkProvider = tracked { parseLink },
            cookies = CookieSource { null },
            injectIntervalProvider = tracked { 500L },
            scope = backgroundScope,
            serialDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            clock = { testScheduler.currentTime },
        )
        assertTrue("built while being made: $builtOn", builtOn.isEmpty())

        session.commit(rumblePage)
        session.onSignal(PageSignal.RequestSeen(videoUrl, emptyMap()))
        runCurrent()
        assertTrue("the page was looked at", builtOn.isNotEmpty())
    }

    @Test
    fun `a flood of requests is capped and never holds back a page change`() = runTest {
        val (session) = harness()
        session.commit("https://rumble.com/first.html")
        repeat(20_000) { session.onSignal(PageSignal.RequestSeen("https://cdn.test/piece$it.ts", emptyMap())) }
        session.commit(rumblePage)
        runCurrent()

        assertEquals(rumblePage, session.state.value.pageUrl)
    }

    @Test
    fun `a blocked page is neither sniffed nor given a script`() = runTest {
        val harness = harness(mapOf("https://cdn.test/v.mp4" to video))
        val page = "https://www.pornhub.com/view_video.php?viewkey=1"
        harness.session.commit(page)
        harness.session.onSignal(PageSignal.RequestSeen("https://cdn.test/v.mp4", emptyMap()))
        harness.session.onSignal(PageSignal.ResourceLoaded("https://cdn.test/a.jpg", page))
        runCurrent()

        assertNull(harness.session.state.value.media)
        assertTrue(harness.commands.isEmpty())
        assertFalse(harness.session.state.value.showDownloadButton)
    }

    @Test
    fun `a burst of resources injects once, then once more after it`() = runTest {
        val harness = harness()
        val page = "https://www.reddit.com/r/videos/"
        harness.session.commit(page)
        repeat(20) { harness.session.onSignal(PageSignal.ResourceLoaded("https://i.redd.it/$it.jpg", page)) }
        runCurrent()
        assertEquals(1, harness.commands.size)

        advanceTimeBy(600)
        runCurrent()
        assertEquals(2, harness.commands.size)
        val script = (harness.commands.first() as PageCommand.RunScript).script
        assertTrue(script.startsWith("window.mksLowEnd=false;window.mksScanScale=1.0;window.mksSingle=false;window.mksParserSite=false;"))
        assertTrue(script.contains("media_downloader/generic.js"))
    }

    @Test
    fun `a post a site script points at is read by the parser`() = runTest {
        val post = "https://x.com/someone/status/1585341984679469056"
        val parsed = MediaModel(
            title = "A tweet",
            thumbnailUrl = null,
            qualities = listOf(MediaQualityModel(videoUrl, "720p", MediaType.Video, sizeBytes = 5_000_000)),
            sourceUrl = post,
        )
        val harness = harness(parse = { url -> if (url == post) Result.success(parsed) else Result.failure(IllegalStateException()) })
        harness.session.commit("https://x.com/home")
        harness.session.onSignal(
            PageSignal.Script(ScriptMessage.MediaFound(ScriptSource.Twitter, MediaType.Video, mediaUrl = null, postUrl = post)),
        )

        val media = harness.session.state.first { it.media != null && !it.isDescribingMedia }.media
        assertEquals("A tweet", media?.title)
        assertEquals("https://x.com/home", media?.sourceUrl)
        assertEquals(DetectionEvent.ShowMedia, harness.events.first())
    }

    @Test
    fun `an advert handed over by a script closes the sheet instead`() = runTest {
        val harness = harness()
        harness.session.commit("https://www.reddit.com/")
        harness.session.onSignal(
            PageSignal.Script(
                ScriptMessage.MediaFound(ScriptSource.Generic, MediaType.Image, "https://tpc.googlesyndication.com/banner.jpg"),
            ),
        )
        runCurrent()
        assertEquals(listOf(DetectionEvent.HideMedia), harness.events)
        assertNull(harness.session.state.value.media)
    }

    @Test
    fun `the running time the pressed player reported is offered with its video`() = runTest {
        // Nothing on this page states a length: the player on it is the only thing that knows.
        val (session) = harness()
        session.commit(rumblePage)
        session.onSignal(PageSignal.RequestSeen(videoUrl, emptyMap()))
        session.state.first { it.media != null }

        session.onSignal(
            PageSignal.Script(
                ScriptMessage.MediaRequested(
                    null, "A clip", null, null, isPlaying = true, isImage = false, durationMillis = 92_500,
                ),
            ),
        )

        assertEquals(92_500L, session.state.first { it.media?.durationMillis != null }.media?.durationMillis)
    }

    @Test
    fun `a picture is offered with no running time, whatever the press carried`() = runTest {
        val (session) = harness()
        session.commit(rumblePage)

        session.onSignal(
            PageSignal.Script(
                ScriptMessage.MediaRequested(
                    null, "A photo", null, "https://cdn.test/a-photo.jpg",
                    isPlaying = false, isImage = true, durationMillis = 92_500,
                ),
            ),
        )

        val offered = session.state.first { it.media != null }.media
        assertEquals(MediaType.Image, offered?.qualities?.single()?.type)
        assertNull(offered?.durationMillis)
    }

    @Test
    fun `a tap on a blob player takes the page's one stream, and a looping clip heard next does not replace it`() = runTest {
        val harness = harness()
        val page = "https://www.reddit.com/r/videos/comments/abc/a_post/"
        val master = "https://v.redd.it/abc/HLSPlaylist.m3u8?a=1"
        harness.session.commit(page)
        // The player loads its stream with the page, before any press.
        harness.session.onSignal(PageSignal.RequestSeen(master, emptyMap()))
        harness.session.onSignal(PageSignal.RequestSeen("https://v.redd.it/abc/HLS_720.m3u8", emptyMap()))
        runCurrent()

        harness.session.onSignal(
            PageSignal.Script(ScriptMessage.MediaRequested(null, "A post", null, null, isPlaying = true, isImage = false)),
        )
        runCurrent()
        // The offer checks the playlist over the (fake) network, which answers on its own threads.
        val offered = harness.session.state.first { it.media != null }.media
        assertEquals(master, offered?.qualities?.single()?.url)

        harness.session.onSignal(PageSignal.RequestSeen("https://cdn.test/banner_loop.mp4", emptyMap()))
        runCurrent()
        assertEquals("the page's stream stands", master, harness.session.state.value.media?.qualities?.single()?.url)
    }

    @Test
    fun `a tap while a pre-roll plays takes the page's stream, not the advert`() = runTest {
        val harness = harness()
        val master = "https://v.redd.it/abc/HLSPlaylist.m3u8?a=1"
        harness.session.commit("https://www.reddit.com/r/videos/comments/abc/a_post/")
        harness.session.onSignal(PageSignal.RequestSeen(master, emptyMap()))
        runCurrent()

        // The player's element is showing the advert, so that is the file the script hands over.
        val preRoll = "https://s0.2mdn.net/creative/preroll.mp4"
        harness.session.onSignal(
            PageSignal.Script(ScriptMessage.MediaRequested(null, "A post", null, preRoll, isPlaying = true, isImage = false)),
        )
        runCurrent()
        val offered = harness.session.state.first { it.media != null }.media
        assertEquals(master, offered?.qualities?.single()?.url)
    }

    @Test
    fun `a press during an advert's stream takes the page's master, every quality kept`() = runTest {
        val folder = "https://video.cdn.test/v/abc"
        val master = "$folder/_TPL_.m3u8"
        val advert = "https://edge2.cdn.test/hls/640x360.m3u8"
        val playlist = { segment: String -> "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\n$segment\n#EXT-X-ENDLIST\n" }
        val files = mapOf(
            master to (
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=256x144\n144p.m3u8\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\n720p.m3u8\n"
                ).toByteArray(),
            "$folder/144p.m3u8" to playlist("$folder/144p-1.ts").toByteArray(),
            "$folder/720p.m3u8" to playlist("$folder/720p-1.ts").toByteArray(),
            "$folder/144p-1.ts" to sampleBytes(1_000),
            "$folder/720p-1.ts" to sampleBytes(10_000),
            advert to playlist("https://edge2.cdn.test/hls/1.ts").toByteArray(),
            "https://edge2.cdn.test/hls/1.ts" to sampleBytes(500),
        )
        val (session) = harness(files = files, realIo = true)
        session.commit("https://www.reddit.com/r/videos/comments/abc/a_post/")
        session.onSignal(PageSignal.RequestSeen(master, emptyMap()))
        session.state.first { it.media?.qualities?.size == 2 && !it.isDescribingMedia }
        // The player starts on its lowest variant, then a pre-roll streams from another host.
        session.onSignal(PageSignal.RequestSeen("$folder/144p.m3u8", emptyMap()))
        session.onSignal(PageSignal.RequestSeen(advert, emptyMap()))
        runCurrent()

        session.onSignal(PageSignal.Script(ScriptMessage.MediaRequested(null, "A post", null, null, isPlaying = true, isImage = false)))
        runCurrent()
        val offered = session.state.first { it.media != null && !it.isDescribingMedia }.media
        assertEquals("both of the master's qualities", 2, offered?.qualities?.size)
    }

    @Test
    fun `a page with two streams is not guessed at when a blob player is pressed`() = runTest {
        val harness = harness()
        harness.session.commit("https://www.reddit.com/r/videos/")
        harness.session.onSignal(PageSignal.RequestSeen("https://v.redd.it/one/HLSPlaylist.m3u8", emptyMap()))
        harness.session.onSignal(PageSignal.RequestSeen("https://v.redd.it/two/HLSPlaylist.m3u8", emptyMap()))
        runCurrent()

        harness.session.onSignal(
            PageSignal.Script(ScriptMessage.MediaRequested(null, "A post", null, null, isPlaying = true, isImage = false)),
        )
        runCurrent()
        assertNull("no stream is picked for the press", harness.session.state.value.media)
    }

    @Test
    fun `the file a press falls back to stands against a widget streaming beside the player`() = runTest {
        val harness = harness()
        val video = "https://cdn.test/v/14758576.mp4"
        harness.session.commit("https://www.reddit.com/r/videos/comments/abc/a_post/")
        harness.session.onSignal(PageSignal.RequestSeen(video, emptyMap()))
        runCurrent()

        harness.session.onSignal(
            PageSignal.Script(ScriptMessage.MediaRequested(null, "A post", null, null, isPlaying = false, isImage = false)),
        )
        advanceTimeBy(3_501)
        runCurrent()
        assertEquals(video, harness.session.state.value.media?.qualities?.firstOrNull()?.url)

        // A live-cam widget on the page goes on fetching pieces named like video files.
        harness.session.onSignal(PageSignal.RequestSeen("https://live.cdn.test/b-hls-16/279_240p_h264_983_Hz.mp4", emptyMap()))
        runCurrent()
        assertEquals("the press's answer stands", video, harness.session.state.value.media?.qualities?.firstOrNull()?.url)
    }

    @Test
    fun `a press on a frame player never reloads the page it is on, and takes what the frame streamed`() = runTest {
        val harness = harness()
        val page = "https://www.bbc.com/news/videos/c1w2x3y4z5o"
        val framed = "https://cdn.test/embed/clip.mp4"
        harness.session.commit(page)
        harness.session.onSignal(PageSignal.RequestSeen(framed, emptyMap()))
        runCurrent()

        // The script hands over the page itself for a frame it cannot see into.
        harness.session.onSignal(
            PageSignal.Script(ScriptMessage.MediaRequested(page, "A post", null, null, isPlaying = false, isImage = false)),
        )
        runCurrent()
        // Reading the page finds nothing (over the fake network, on its own threads), then the last
        // stream heard is taken.
        val offered = harness.session.state.first { it.media != null }.media
        assertTrue("the page is not reloaded", harness.commands.none { it == PageCommand.Load(page) })
        assertEquals(framed, offered?.qualities?.firstOrNull()?.url)
    }

    @Test
    fun `a listing's own slug does not name a video playing inside it`() = runTest {
        val harness = harness()
        // A page that holds many videos: its address names the listing, not any of them.
        val page = "https://www.bbc.com/news/videos/fall-tv-watch-guide"
        val video = "https://cdn.test/v/trailer.mp4"
        harness.session.commit(page)
        harness.session.onSignal(PageSignal.RequestSeen(video, emptyMap()))
        runCurrent()

        // The script hands over the page itself, with the heading it read beside the player.
        harness.session.onSignal(
            PageSignal.Script(ScriptMessage.MediaRequested(page, "What to watch on streaming", null, null, isPlaying = true, isImage = false)),
        )
        val offered = harness.session.state.first { it.media?.title == "What to watch on streaming" }.media
        assertEquals("What to watch on streaming", offered?.title)
    }

    @Test
    fun `a tap that finds nothing says so in time`() = runTest {
        val harness = harness()
        harness.session.commit("https://www.reddit.com/")
        harness.session.onSignal(
            PageSignal.Script(ScriptMessage.MediaRequested(null, "A post", null, null, isPlaying = true, isImage = false)),
        )
        runCurrent()
        assertTrue(harness.session.state.value.isSearching)

        advanceTimeBy(25_001)
        runCurrent()
        assertEquals(listOf(DetectionEvent.ShowMedia, DetectionEvent.NothingFound), harness.events)
        assertFalse(harness.session.state.value.isSearching)
    }

    @Test
    fun `a search on the page left behind never reports nothing found on the next one`() = runTest {
        val harness = harness()
        harness.session.commit("https://www.reddit.com/")
        harness.session.onSignal(
            PageSignal.Script(ScriptMessage.MediaRequested(null, "A post", null, null, isPlaying = true, isImage = false)),
        )
        runCurrent()
        advanceTimeBy(10_000)
        harness.session.commit("https://www.reddit.com/r/videos/")
        runCurrent()
        assertFalse(harness.session.state.value.isSearching)

        advanceTimeBy(20_000)
        runCurrent()
        assertFalse(DetectionEvent.NothingFound in harness.events)
    }

    @Test
    fun `script buttons drawn on a generic page do not hide the host's button on a tiktok video`() = runTest {
        val harness = harness()
        harness.session.commit("https://www.reddit.com/")
        harness.session.onSignal(PageSignal.Script(ScriptMessage.ButtonsDrawn(2)))
        runCurrent()
        assertTrue(harness.session.state.value.hasScriptButtons)

        harness.session.commit("https://www.tiktok.com/@someone/video/7300000000000000000")
        runCurrent()
        assertFalse(harness.session.state.value.hasScriptButtons)
    }

    @Test
    fun `an answer for a page already left is dropped`() = runTest {
        val answer = CompletableDeferred<Result<MediaModel>>()
        val harness = harness(parse = { answer.await() })
        harness.session.commit("https://www.dailymotion.com/video/x8ixblz")
        runCurrent()
        harness.session.commit("https://www.reddit.com/")
        runCurrent()

        answer.complete(Result.success(MediaModel("old", null, listOf(MediaQualityModel(videoUrl, "HD", MediaType.Video)), "s")))
        runCurrent()
        assertNull(harness.session.state.value.media)
        assertEquals("https://www.reddit.com/", harness.session.state.value.pageUrl)
    }

    @Test
    fun `the renderer dying is reported and clears the page's media`() = runTest {
        val harness = harness()
        harness.session.commit(rumblePage)
        harness.session.onSignal(PageSignal.RequestSeen(videoUrl, emptyMap()))
        harness.session.state.first { it.media != null }
        harness.session.onSignal(PageSignal.RendererGone)
        runCurrent()

        assertNull(harness.session.state.value.media)
        assertTrue(harness.session.state.value.isRendererGone)
        assertEquals(DetectionEvent.RendererGone, harness.events.last())
    }
}
