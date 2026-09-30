package com.markhoor.mediadownloader.presentation.browser

import com.markhoor.mediadownloader.domain.models.DetectionEvent
import com.markhoor.mediadownloader.domain.models.DetectionState
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.domain.models.PageCommand
import com.markhoor.mediadownloader.domain.models.PageSignal
import com.markhoor.mediadownloader.domain.models.ScriptMessage
import com.markhoor.mediadownloader.domain.models.ScriptSource
import com.markhoor.mediadownloader.domain.repo.MediaDetector
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every signal; its state, events and commands are driven by the test. */
internal class RecordingDetector : MediaDetector {
    val signals = mutableListOf<PageSignal>()
    override val state = MutableStateFlow(DetectionState())
    val eventChannel = Channel<DetectionEvent>(Channel.UNLIMITED)
    override val events: Flow<DetectionEvent> = eventChannel.receiveAsFlow()
    override val commands: Flow<PageCommand> = Channel<PageCommand>().receiveAsFlow()
    override fun onSignal(signal: PageSignal) {
        signals += signal
    }

    val messages: List<ScriptMessage> get() = signals.filterIsInstance<PageSignal.Script>().map { it.message }
}

class PageScriptBridgeTest {

    private val detector = RecordingDetector()
    private val bridge = PageScriptBridge(detector)
    private val url = "https://cdn.test/v.mp4"

    @Test
    fun `page text is cut to a title's length and an oversized url is dropped, not cut`() {
        val hugeText = "t".repeat(3_000_000)
        val hugeUrl = "https://cdn.test/" + "a".repeat(3_000_000)
        bridge.genericMediaRequested(hugeUrl, hugeText, hugeUrl, hugeUrl, playing = true, isImage = false)
        bridge.processVideo(hugeUrl, hugeText)

        val requested = detector.messages.filterIsInstance<ScriptMessage.MediaRequested>().single()
        assertEquals(1_000, requested.title?.length)
        assertEquals(null, requested.mediaUrl)
        assertEquals(null, requested.cardPageUrl)
        assertEquals(null, requested.thumbnail)
        val found = detector.messages.filterIsInstance<ScriptMessage.MediaFound>().single()
        assertEquals(null, found.mediaUrl)
        assertEquals(1_000, found.title?.length)
    }

    @Test
    fun `the running time a player reports is passed on, and nonsense is not`() {
        // In order: a real length, a player with nothing loaded, a live stream, a broken one, and
        // a script written before this argument existed.
        bridge.genericMediaRequested(url, null, null, url, playing = true, isImage = false, seconds = 92.5)
        bridge.genericMediaRequested(url, null, null, url, playing = true, isImage = false, seconds = 0.0)
        bridge.genericMediaRequested(
            url, null, null, url, playing = true, isImage = false, seconds = Double.POSITIVE_INFINITY,
        )
        bridge.genericMediaRequested(url, null, null, url, playing = true, isImage = false, seconds = Double.NaN)
        bridge.genericMediaRequested(url, null, null, url, playing = true, isImage = false)

        val times = detector.messages.filterIsInstance<ScriptMessage.MediaRequested>().map { it.durationMillis }
        assertEquals(listOf(92_500L, null, null, null, null), times)
    }

    @Test
    fun `a facebook video with an id is read as its reel too, one without is not`() {
        bridge.fbVideoFound("https://video.fbcdn.net/v.mp4", "123456")
        bridge.fbVideoFound("https://video.fbcdn.net/w.mp4", null)
        bridge.fbVideoFound("", "null")
        val found = detector.messages.filterIsInstance<ScriptMessage.MediaFound>()
        assertEquals("https://www.facebook.com/reel/123456", found[0].postUrl)
        assertEquals(null, found[1].postUrl)
        assertEquals(null, found[2].postUrl)
    }

    @Test
    fun `a tweet is its status url, and a tweet's picture is the picture`() {
        bridge.drinkTwitter("https://x.com/someone/status/1585341984679469056/photo/1")
        bridge.runITwitter("https://pbs.twimg.com/media/abc.jpg?name=large")
        val (tweet, picture) = detector.messages.filterIsInstance<ScriptMessage.MediaFound>()
        assertEquals("https://x.com/someone/status/1585341984679469056", tweet.postUrl)
        assertEquals(MediaType.Image, picture.type)
        assertEquals("https://pbs.twimg.com/media/abc.jpg?name=large", picture.mediaUrl)
    }

    @Test
    fun `threads hands over a real file when it has one, else the post`() {
        bridge.threadsMediaFound("blob:https://www.threads.com/x", "https://www.threads.com/@someone/post/abc", "", "")
        bridge.threadsMediaFound("https://scontent.cdninstagram.com/v/p.jpg", "https://www.threads.com/@a/post/b", "A caption", "")
        bridge.threadsMediaFound(null, null, null, null)
        val found = detector.messages.filterIsInstance<ScriptMessage.MediaFound>()
        assertEquals(2, found.size)
        assertEquals(null, found[0].mediaUrl)
        assertEquals("someone", found[0].title)
        assertEquals(MediaType.Image, found[1].type)
        assertEquals("A caption", found[1].title)
        assertTrue(found.all { it.source == ScriptSource.Threads })
    }

    @Test
    fun `the generic script's calls arrive whole, whichever overload it used`() {
        bridge.feedCardChanged("Title", "https://i.test/t.jpg")
        bridge.feedCardChanged("Title", "https://i.test/t.jpg", "https://v.test/v.mp4")
        bridge.genericButtonsDrawn(3)
        bridge.genericMediaRequested("https://site.test/p", "Name", null, null, true, false)
        assertEquals(
            listOf(
                ScriptMessage.CardChanged("Title", "https://i.test/t.jpg", null, false),
                ScriptMessage.CardChanged("Title", "https://i.test/t.jpg", "https://v.test/v.mp4", false),
                ScriptMessage.ButtonsDrawn(3),
                ScriptMessage.MediaRequested("https://site.test/p", "Name", null, null, isPlaying = true, isImage = false),
            ),
            detector.messages,
        )
    }

    @Test
    fun `blank post text is no title`() {
        bridge.processImage("https://scontent.test/p.jpg", "   ")
        assertEquals(null, (detector.messages.single() as ScriptMessage.MediaFound).title)
    }
}
