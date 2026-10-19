package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.MediaCollectionItem
import com.markhoor.mediadownloader.domain.models.MediaCollectionModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A playlist is queued whole, at once: a row per entry before anything has been read, each into the
 * playlist's own folder under its place in the list. What an entry actually is gets read by the
 * download itself when its turn comes, which is why nothing here goes near the network.
 */
class DownloadCollectionUseCaseTest {

    private val downloads = FakeDownloadRepository()
    private val requests get() = downloads.enqueued

    private fun useCase(scope: TestScope) = DownloadCollectionUseCase(
        enqueue = EnqueueDownloadUseCase(
            CheckSiteAccessUseCase(strictSupportedSitesOnly = false, extraBlockedHosts = emptySet()),
            downloads,
        ),
        scope = scope,
    )

    private fun collection(vararg titles: String) = MediaCollectionModel(
        title = "Road trip songs",
        sourceUrl = "https://videos.test/list/1",
        items = titles.map { MediaCollectionItem(url = "https://videos.test/$it", title = it) },
    )

    @Test
    fun `every entry is queued at once, numbered, into the collection's own folder`() = runTest {
        useCase(this).start(collection("one", "two", "three"), preferredQuality = "720p")
        advanceUntilIdle()

        assertEquals(listOf("01 - one", "02 - two", "03 - three"), requests.map { it.fileName })
        assertEquals("all of them in one folder", listOf("Road trip songs"), requests.map { it.siteFolder }.distinct())
        assertEquals(
            "and all marked as its own, so a screen can show them as one",
            listOf("Road trip songs"),
            requests.map { it.collectionTitle }.distinct(),
        )
    }

    @Test
    fun `an entry is queued with its page, not a file, and the quality it is to aim for`() = runTest {
        useCase(this).start(collection("one"), preferredQuality = "720p")
        advanceUntilIdle()

        val request = requests.single()
        assertEquals("nothing has been read yet", "", request.mediaUrl)
        assertEquals("the page the download will read", "https://videos.test/one", request.sourceUrl)
        assertEquals("the quality being asked for", "720p", request.qualityLabel)
    }

    @Test
    fun `the whole list is there before anything is read`() = runTest {
        val many = (1..12).map { "v$it" }.toTypedArray()

        useCase(this).start(collection(*many), preferredQuality = null)
        advanceUntilIdle()

        assertEquals(12, requests.size)
        assertEquals("01 - v1", requests.first().fileName)
        assertEquals("the numbers are as wide as the list is long", "12 - v12", requests.last().fileName)
    }

    /**
     * `MediaDownloader.downloadCollection` promises that starting another collection replaces the
     * one being added. The job was launched and dropped, so nothing replaced anything: both loops
     * ran, both counted into the same progress, and whichever finished first said the adding was
     * over while the other was still inserting rows - a host reading `collectionProgress()` was
     * shown "added 37 of 3", and a finished collection that was not finished.
     */
    @Test
    fun `starting another collection replaces the one being added`() = runTest {
        val subject = useCase(this)

        subject.start(collection(*Array(50) { "song$it" }), preferredQuality = null)
        subject.start(collection("a", "b", "c"), preferredQuality = null)
        advanceUntilIdle()

        assertEquals("only the second collection's entries", 3, requests.size)
        assertEquals(listOf("01 - a", "02 - b", "03 - c"), requests.map { it.fileName })
        val progress = subject.progress.value
        assertEquals(3, progress.total)
        assertEquals(3, progress.added)
        assertFalse(progress.isAdding)
    }

    /**
     * And the one it replaced must not have the last word. Its `finally` runs when the cancel
     * reaches it, which is after the new collection's progress is already in place - so a naive
     * cancel has the old loop announce that the adding is over while the new one is still going.
     */
    @Test
    fun `the collection that was replaced does not end the new one's progress`() = runTest {
        downloads.enqueueTakes = 10
        val subject = useCase(this)

        subject.start(collection(*Array(50) { "song$it" }), preferredQuality = null)
        advanceTimeBy(25)
        subject.start(collection("a", "b", "c"), preferredQuality = null)
        // Long enough for the cancelled loop to reach its `finally`, not long enough for the new
        // one to have queued everything.
        advanceTimeBy(15)

        assertTrue("the new collection is still being added", subject.progress.value.isAdding)
        advanceUntilIdle()
        assertFalse(subject.progress.value.isAdding)
        assertEquals(3, subject.progress.value.added)
    }

    @Test
    fun `progress counts what was queued`() = runTest {
        val subject = useCase(this)

        subject.start(collection("one", "two"), preferredQuality = null)
        advanceUntilIdle()

        val progress = subject.progress.value
        assertEquals("Road trip songs", progress.title)
        assertEquals(2, progress.total)
        assertEquals(2, progress.added)
        assertEquals(0, progress.failed)
        assertFalse(progress.isAdding)
    }

    @Test
    fun `a download that names only its page is accepted, one that names nothing is not`() = runTest {
        val enqueue = EnqueueDownloadUseCase(
            CheckSiteAccessUseCase(strictSupportedSitesOnly = false, extraBlockedHosts = emptySet()),
            downloads,
        )
        val page = DownloadRequest(
            mediaUrl = "",
            type = MediaType.Video,
            title = "one",
            sourceUrl = "https://videos.test/one",
        )

        assertTrue(enqueue(page).isSuccess)
        assertTrue("neither a file nor a page", enqueue(page.copy(sourceUrl = "not a link")).isFailure)
    }

    @Test
    fun `the closest quality is chosen by height, and the best when nothing is asked for`() {
        val ladder = listOf("1080p60", "720p", "480p", "144p").map {
            MediaQualityModel(url = "https://cdn.test/$it", label = it, type = MediaType.Video)
        }

        assertEquals("1080p60", ladder.closestTo(null)?.label)
        assertEquals("720p", ladder.closestTo("720p")?.label)
        assertEquals("the tallest that is not taller", "720p", ladder.closestTo("900p")?.label)
        assertEquals("nothing smaller exists", "144p", ladder.closestTo("100p")?.label)
        assertEquals(
            "labels with no height at all",
            "HD",
            listOf(MediaQualityModel(url = "https://cdn.test/hd", label = "HD", type = MediaType.Video))
                .closestTo("720p")?.label,
        )
    }
}
