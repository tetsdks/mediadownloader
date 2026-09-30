package com.markhoor.mediadownloader.data.download

import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HlsStreamDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val base = "https://cdn.test/v/"

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",URI="audio.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360,AUDIO="aud"
        low.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2",AUDIO="aud"
        high.m3u8
    """.trimIndent()

    private val high = """
        #EXTM3U
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:4.0,
        seg0.m4s
        #EXTINF:4.0,
        seg1.m4s
        #EXTINF:4.0,
        subtitles.vtt
        #EXT-X-ENDLIST
    """.trimIndent()

    private val audio = """
        #EXTM3U
        #EXTINF:4.0,
        a0.aac
        #EXTINF:4.0,
        a1.aac
        #EXT-X-ENDLIST
    """.trimIndent()

    private val init = sampleBytes(700)
    private val seg0 = sampleBytes(40_000)
    private val seg1 = sampleBytes(35_000).reversedArray()
    private val a0 = sampleBytes(5_000)
    private val a1 = sampleBytes(4_000).reversedArray()

    private fun files(vararg extra: Pair<String, ByteArray>) = mapOf(
        base + "master.m3u8" to master.toByteArray(),
        base + "high.m3u8" to high.toByteArray(),
        base + "audio.m3u8" to audio.toByteArray(),
        base + "init.mp4" to init,
        base + "seg0.m4s" to seg0,
        base + "seg1.m4s" to seg1,
        base + "a0.aac" to a0,
        base + "a1.aac" to a1,
    ) + extra

    private fun task(url: String, audioUrl: String? = null) = DownloadTask(
        url = url,
        audioUrl = audioUrl,
        headers = emptyMap(),
        isStream = true,
        singleConnection = false,
        workDir = File(temp.root, "work"),
        output = File(temp.root, "out/.1.download"),
    )

    private fun downloader(server: FakeMediaServer, remuxer: StreamRemuxer) =
        HlsStreamDownloader(HttpFileFetcher(server.client), HttpFetcher(server.client, HttpClientFactory.json), remuxer)

    /** Records what it was handed and writes picture then sound, so the test can see both. */
    private class RecordingRemuxer : StreamRemuxer {
        var video: ByteArray? = null
        var audio: ByteArray? = null
        override fun remux(video: File, audio: File?, output: File) {
            this.video = video.readBytes()
            this.audio = audio?.readBytes()
            output.parentFile?.mkdirs()
            output.writeBytes(this.video!! + (this.audio ?: ByteArray(0)))
        }
    }

    @Test
    fun `a master is narrowed to its best encode, joined behind its init, with its sound`() = runTest {
        val server = FakeMediaServer(files())
        val remuxer = RecordingRemuxer()
        val meter = ProgressMeter()

        downloader(server, remuxer).download(task(base + "master.m3u8"), meter)

        assertArrayEquals(init + seg0 + seg1, remuxer.video)
        assertArrayEquals(a0 + a1, remuxer.audio)
        assertTrue(server.requests.none { it.contains("low.m3u8") })
        assertTrue("subtitles are not the video", server.requests.none { it.contains(".vtt") })
        val fetched = (init.size + seg0.size + seg1.size + a0.size + a1.size).toLong()
        assertEquals(fetched, meter.downloadedBytes)
    }

    @Test
    fun `when remuxing fails the joined stream is kept`() = runTest {
        val server = FakeMediaServer(files())

        downloader(server) { _, _, _ -> error("no muxer on this device") }
            .download(task(base + "high.m3u8"), ProgressMeter())

        assertArrayEquals(init + seg0 + seg1, task(base).output.readBytes())
    }

    @Test
    fun `a sound playlist that will not load leaves the picture`() = runTest {
        val server = FakeMediaServer(files())
        val remuxer = RecordingRemuxer()

        downloader(server, remuxer).download(task(base + "high.m3u8", audioUrl = base + "missing.m3u8"), ProgressMeter())

        assertArrayEquals(init + seg0 + seg1, remuxer.video)
        assertEquals(null, remuxer.audio)
    }

    @Test
    fun `byte range pieces are asked for by range`() = runTest {
        val archive = sampleBytes(1_000)
        val playlist = """
            #EXTM3U
            #EXTINF:2.0,
            #EXT-X-BYTERANGE:300@0
            all.ts
            #EXTINF:2.0,
            #EXT-X-BYTERANGE:200
            all.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val server = FakeMediaServer(files(base + "ranged.m3u8" to playlist.toByteArray(), base + "all.ts" to archive))
        val remuxer = RecordingRemuxer()
        val meter = ProgressMeter()

        downloader(server, remuxer).download(task(base + "ranged.m3u8"), meter)

        assertArrayEquals(archive.copyOf(500), remuxer.video)
        assertTrue(server.requests.contains(base + "all.ts bytes=0-299"))
        assertTrue(server.requests.contains(base + "all.ts bytes=300-499"))
        assertEquals("stated sizes are the total", 500L, meter.totalBytes)
    }

    @Test
    fun `a retry keeps the pieces already fetched`() = runTest {
        File(temp.root, "work/video").mkdirs()
        File(temp.root, "work/video/0.seg").writeBytes(seg0)
        val server = FakeMediaServer(files() - (base + "seg0.m4s"))
        val remuxer = RecordingRemuxer()

        downloader(server, remuxer).download(task(base + "high.m3u8"), ProgressMeter())

        assertArrayEquals(init + seg0 + seg1, remuxer.video)
        assertFalse(server.requests.any { it.contains("seg0.m4s") })
    }

    @Test
    fun `a playlist of playlists is followed to its last encode`() = runTest {
        val rendition = """
            #EXTM3U
            #EXTINF:10,
            small.m3u8
            #EXTINF:10,
            high.m3u8
            #EXT-X-ENDLIST
        """.trimIndent()
        val server = FakeMediaServer(files(base + "rendition.m3u8" to rendition.toByteArray()))
        val remuxer = RecordingRemuxer()

        downloader(server, remuxer).download(task(base + "rendition.m3u8"), ProgressMeter())

        assertArrayEquals(init + seg0 + seg1, remuxer.video)
    }

    private fun encrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            .doFinal(plain)

    @Test
    fun `aes-128 pieces are decrypted, by the playlist's iv or the piece's sequence, with one key fetch`() = runTest {
        val key = ByteArray(16) { (it * 7 + 3).toByte() }
        val sequenceIv = ByteBuffer.allocate(16).putLong(8, 7L).array()
        val statedIv = ByteArray(16) { it.toByte() }
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:4.0,
            e0.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:4.0,
            e1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val server = FakeMediaServer(
            files(
                base + "enc.m3u8" to playlist.toByteArray(),
                base + "key.bin" to key,
                base + "e0.ts" to encrypt(seg0, key, sequenceIv),
                base + "e1.ts" to encrypt(seg1, key, statedIv),
            ),
        )
        val remuxer = RecordingRemuxer()

        downloader(server, remuxer).download(task(base + "enc.m3u8"), ProgressMeter())

        assertArrayEquals(seg0 + seg1, remuxer.video)
        assertEquals(1, server.requests.count { it.contains("key.bin") })
    }

    @Test
    fun `an encryption that cannot be undone fails at once, and says why`() = runTest {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://drm"
            #EXTINF:4.0,
            seg0.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val server = FakeMediaServer(files(base + "drm.m3u8" to playlist.toByteArray()))

        val error = runCatching { downloader(server, RecordingRemuxer()).download(task(base + "drm.m3u8"), ProgressMeter()) }
            .exceptionOrNull()

        assertTrue("was $error", error is UnusableMediaException && error.message.orEmpty().contains("SAMPLE-AES"))
        assertFalse(server.requests.any { it.contains("seg0.m4s") })
    }

    @Test
    fun `sound cut off by the connection fails the run, rather than finishing silent`() = runTest {
        val served = files()
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            if (url.endsWith("a1.aac")) throw IOException("connection reset")
            val body = served[url] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        })
        val remuxer = RecordingRemuxer()
        val downloader = HlsStreamDownloader(HttpFileFetcher(client), HttpFetcher(client, HttpClientFactory.json), remuxer)

        val error = runCatching { downloader.download(task(base + "master.m3u8"), ProgressMeter()) }.exceptionOrNull()

        assertTrue("was $error", error is IOException)
        assertEquals("nothing was finished", null, remuxer.video)
    }

    @Test
    fun `a track's pieces are deleted once joined, and a rerun takes the joined file`() = runTest {
        downloader(FakeMediaServer(files()), RecordingRemuxer()).download(task(base + "high.m3u8"), ProgressMeter())
        val videoDir = File(temp.root, "work/video")
        assertTrue(File(videoDir, "joined.done").exists())
        assertTrue(videoDir.listFiles().orEmpty().none { it.name.endsWith(".seg") })

        val empty = FakeMediaServer(mapOf(base + "high.m3u8" to high.toByteArray()))
        val again = RecordingRemuxer()
        downloader(empty, again).download(task(base + "high.m3u8"), ProgressMeter())

        assertArrayEquals(init + seg0 + seg1, again.video)
        assertEquals(listOf(base + "high.m3u8 -"), empty.requests)
    }

    @Test
    fun `a direct link that turns out to be a playlist is downloaded as the stream`() = runTest {
        val server = FakeMediaServer(files(base + "watch" to high.toByteArray()))
        val remuxer = RecordingRemuxer()
        val engine = DownloadEngine(
            direct = DirectFileDownloader(HttpFileFetcher(server.client)),
            hls = downloader(server, remuxer),
            remuxer = remuxer,
            ioDispatcher = Dispatchers.Unconfined,
        )

        engine.download(task(base + "watch").copy(isStream = false), ProgressMeter())

        assertArrayEquals(init + seg0 + seg1, remuxer.video)
    }

    @Test
    fun `while pieces arrive the total is the size the user was shown, not the init piece times the pieces`() = runTest {
        val served = files()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val initFetched = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            // The video pieces wait until the test has looked at the total.
            if (url.endsWith(".m4s")) release.await()
            val body = served[url] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
            if (url.endsWith("init.mp4")) initFetched.complete(Unit)
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        })
        val shownSize = 5_000_000L
        val meter = ProgressMeter(startTotal = shownSize)
        val downloader = HlsStreamDownloader(HttpFileFetcher(client), HttpFetcher(client, HttpClientFactory.json), RecordingRemuxer())

        val job = launch(Dispatchers.IO) { downloader.download(task(base + "high.m3u8"), meter) }
        initFetched.await()
        // The init piece's own bookkeeping runs just after its request answers.
        kotlinx.coroutines.withContext(Dispatchers.IO) { Thread.sleep(300) }
        assertEquals("still the shown size with only the init piece done", shownSize, meter.totalBytes)

        release.complete(Unit)
        job.join()
        assertEquals((init.size + seg0.size + seg1.size).toLong(), meter.downloadedBytes)
        assertTrue("the total ends at least at what arrived", (meter.totalBytes ?: 0) >= meter.downloadedBytes)
    }
}
