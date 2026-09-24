package com.markhoor.mediadownloader

import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.MediaDownloaderNotInitializedException
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests never initialize the module, so every call here is one made too early. */
class MediaDownloaderNotInitializedTest {

    @Test
    fun `calls that return a result fail with the reason`() = runTest {
        assertFalse(MediaDownloader.isInitialized)
        val request = DownloadRequest("https://cdn.test/a.mp4", MediaType.Video, "t", "https://www.tiktok.com/@a/video/1")
        listOf(
            MediaDownloader.parse("https://www.tiktok.com/@a/video/1"),
            MediaDownloader.download(request),
            MediaDownloader.pause(1),
            MediaDownloader.resume(1),
            MediaDownloader.delete(1),
        ).forEach { result ->
            assertTrue(result.exceptionOrNull() is MediaDownloaderNotInitializedException)
        }
    }

    @Test(expected = MediaDownloaderNotInitializedException::class)
    fun `calls that return a value throw the reason`() {
        MediaDownloader.siteAccess("https://www.tiktok.com/")
    }
}
