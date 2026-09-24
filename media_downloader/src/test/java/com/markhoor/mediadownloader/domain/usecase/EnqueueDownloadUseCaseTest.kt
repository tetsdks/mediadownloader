package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.domain.models.DownloadException
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnqueueDownloadUseCaseTest {

    private val repository = FakeDownloadRepository()
    private val enqueue = EnqueueDownloadUseCase(CheckSiteAccessUseCase(strictSupportedSitesOnly = true, emptySet()), repository)

    private fun request(mediaUrl: String, sourceUrl: String = "https://www.pinterest.com/pin/1/", audioUrl: String? = null) =
        DownloadRequest(mediaUrl = mediaUrl, type = MediaType.Video, title = "t", sourceUrl = sourceUrl, audioUrl = audioUrl)

    @Test
    fun `media on a cdn no list names is downloaded, trimmed`() = runTest {
        val result = enqueue(request("  https://v1.pinimg.com/videos/a.mp4 "))
        assertEquals(1L, result.getOrNull())
        assertEquals("https://v1.pinimg.com/videos/a.mp4", repository.enqueued.single().mediaUrl)
    }

    @Test
    fun `a blocked page, file, or sound is refused`() = runTest {
        listOf(
            request("https://cdn.test/a.mp4", sourceUrl = "https://www.pornhub.com/view_video.php?v=1"),
            request("https://cv.phncdn.com/a.mp4"),
            request("https://cdn.test/a.m3u8", audioUrl = "https://xhamster19.com/audio.m3u8"),
            request("https://cdn.test/a.mp4", sourceUrl = "https://www.youtube.com/watch?v=1"),
        ).forEach { request ->
            assertTrue(request.toString(), enqueue(request).exceptionOrNull() is DownloadException.SiteBlocked)
        }
        assertTrue(repository.enqueued.isEmpty())
    }

    @Test
    fun `only an http url of sane length is a media url`() = runTest {
        listOf("blob:https://x.com/1", "data:video/mp4;base64,AAAA", "not a url", "https://a.com/" + "x".repeat(20_000))
            .forEach { url ->
                assertTrue(url.take(40), enqueue(request(url)).exceptionOrNull() is DownloadException.InvalidMediaUrl)
            }
        assertTrue(repository.enqueued.isEmpty())
    }
}
