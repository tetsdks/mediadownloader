package com.markhoor.mediadownloader.domain.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The time left a host shows, and the four ways there is nothing honest to show. */
class RemainingTimeTest {

    private fun downloading(
        downloadedBytes: Long,
        totalBytes: Long?,
        bytesPerSecond: Long?,
        state: DownloadState = DownloadState.Downloading,
    ) = DownloadModel(
        id = 1,
        title = "A clip",
        fileName = "a-clip.mp4",
        filePath = "/sdcard/Download/a-clip.mp4",
        sourceUrl = "https://example.com/p/1",
        thumbnailUrl = null,
        type = MediaType.Video,
        qualityLabel = "720p",
        state = state,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        errorMessage = null,
        createdAtMillis = 0,
        bytesPerSecond = bytesPerSecond,
    )

    @Test
    fun `what is left divided by how fast it is going`() {
        val model = downloading(downloadedBytes = 10_000_000, totalBytes = 50_000_000, bytesPerSecond = 2_000_000)

        assertEquals(20_000L, model.remainingMillis)
        assertEquals(20, model.progressPercent)
    }

    /**
     * After the last byte comes the join, the stitch and the copy into the public folder, none of
     * it counted in bytes and on a long video a real wait. A countdown that has run out would read
     * as a download that has hung, so there is none.
     */
    @Test
    fun `finishing has no time left, because what is left is not bytes`() {
        val model = downloading(
            downloadedBytes = 50_000_000,
            totalBytes = 50_000_000,
            bytesPerSecond = 2_000_000,
            state = DownloadState.Finishing,
        )

        assertNull(model.remainingMillis)
        assertEquals(100, model.progressPercent)
    }

    @Test
    fun `nothing is said for a download that is not running`() {
        listOf(DownloadState.Queued, DownloadState.Paused, DownloadState.WaitingForNetwork, DownloadState.Completed)
            .forEach { state ->
                val model = downloading(1_000_000, 50_000_000, bytesPerSecond = 2_000_000, state = state)
                assertNull(state.name, model.remainingMillis)
            }
    }

    @Test
    fun `nothing is said without a size, a speed, or anything left to fetch`() {
        assertNull("no size yet", downloading(1_000_000, null, 2_000_000).remainingMillis)
        assertNull("no speed yet", downloading(1_000_000, 50_000_000, null).remainingMillis)
        assertNull("standing still", downloading(1_000_000, 50_000_000, 0).remainingMillis)
        assertNull("nothing left", downloading(50_000_000, 50_000_000, 2_000_000).remainingMillis)
    }

    /** The last moments belong to the finishing that follows them; a countdown there is noise. */
    @Test
    fun `the last moment is not counted down`() {
        assertNull(downloading(49_000_000, 50_000_000, bytesPerSecond = 2_000_000).remainingMillis)
        assertEquals(2_000L, downloading(46_000_000, 50_000_000, bytesPerSecond = 2_000_000).remainingMillis)
    }

    @Test
    fun `a speed is only what was measured, and is nothing by default`() {
        val model = downloading(1_000_000, 50_000_000, bytesPerSecond = null)

        assertNull(model.bytesPerSecond)
        assertNull(model.remainingMillis)
    }
}
