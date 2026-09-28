package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.MediaCollectionItem
import com.markhoor.mediadownloader.domain.models.MediaCollectionModel
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaParseException
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.domain.repo.MediaParserRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A playlist is queued one entry at a time, in order, each into the playlist's own folder under a
 * name that starts with its place in it - the order has to survive in the folder, because the
 * downloads will not finish in it.
 */
class DownloadCollectionUseCaseTest {

    private val downloads = FakeDownloadRepository()
    private val requests get() = downloads.enqueued

    private fun media(title: String, vararg labels: String) = MediaModel(
        title = title,
        thumbnailUrl = null,
        qualities = labels.map {
            MediaQualityModel(url = "https://cdn.test/$title-$it.mp4", label = it, type = MediaType.Video)
        },
        sourceUrl = "https://videos.test/$title",
    )

    private fun useCase(
        scope: TestScope,
        parse: suspend (String) -> Result<MediaModel>,
    ): DownloadCollectionUseCase {
        val parser = object : MediaParserRepository {
            override suspend fun parse(url: String) = parse(url)
        }
        val access = CheckSiteAccessUseCase(
            strictSupportedSitesOnly = false,
            extraBlockedHosts = emptySet(),
        )
        return DownloadCollectionUseCase(
            parseLink = ParseLinkUseCase(access, parser),
            enqueue = EnqueueDownloadUseCase(access, downloads),
            scope = scope,
        )
    }

    private fun collection(vararg titles: String) = MediaCollectionModel(
        title = "Road trip songs",
        sourceUrl = "https://videos.test/list/1",
        items = titles.map { MediaCollectionItem(url = "https://videos.test/$it", title = it) },
    )

    @Test
    fun `every entry is queued in order, numbered, into the collection's own folder`() = runTest {
        val subject = useCase(this) { url ->
            Result.success(media(url.substringAfterLast('/'), "1080p", "720p", "360p"))
        }

        subject.start(collection("one", "two", "three"), preferredQuality = "720p")
        advanceUntilIdle()

        assertEquals(listOf("01 - one", "02 - two", "03 - three"), requests.map { it.fileName })
        assertEquals("all of them in one folder", listOf("Road trip songs"), requests.map { it.siteFolder }.distinct())
        assertEquals("the quality that was asked for", listOf("720p", "720p", "720p"), requests.map { it.qualityLabel })
    }

    @Test
    fun `an entry that does not offer the quality asked for gets the closest it has`() = runTest {
        val subject = useCase(this) { url ->
            val name = url.substringAfterLast('/')
            Result.success(if (name == "two") media(name, "480p", "360p") else media(name, "1080p", "720p"))
        }

        subject.start(collection("one", "two"), preferredQuality = "1080p")
        advanceUntilIdle()

        assertEquals(listOf("1080p", "480p"), requests.map { it.qualityLabel })
    }

    @Test
    fun `an entry that cannot be read is counted and the rest still go`() = runTest {
        val subject = useCase(this) { url ->
            if (url.endsWith("two")) Result.failure(MediaParseException.MediaNotFound(url, null))
            else Result.success(media(url.substringAfterLast('/'), "720p"))
        }

        subject.start(collection("one", "two", "three"), preferredQuality = null)
        advanceUntilIdle()

        assertEquals(listOf("01 - one", "03 - three"), requests.map { it.fileName })
        val progress = subject.progress.value
        assertEquals(2, progress.added)
        assertEquals(1, progress.failed)
        assertEquals(3, progress.total)
        assertFalse("finished", progress.isAdding)
    }

    @Test
    fun `the numbers are as wide as the collection is long`() = runTest {
        val many = (1..12).map { "v$it" }.toTypedArray()
        val subject = useCase(this) { url -> Result.success(media(url.substringAfterLast('/'), "720p")) }

        subject.start(collection(*many), preferredQuality = null)
        advanceUntilIdle()

        assertEquals("01 - v1", requests.first().fileName)
        assertEquals("12 - v12", requests.last().fileName)
    }

    @Test
    fun `starting another collection replaces the one being added`() = runTest {
        val slow = StandardTestDispatcher(testScheduler)
        val subject = useCase(this) { url ->
            kotlinx.coroutines.withContext(slow) { Result.success(media(url.substringAfterLast('/'), "720p")) }
        }

        subject.start(collection("one", "two", "three"), preferredQuality = null)
        subject.start(collection("later"), preferredQuality = null)
        advanceUntilIdle()

        assertTrue("only the second one's entry", requests.map { it.fileName }.contains("01 - later"))
        assertEquals("Road trip songs", subject.progress.value.title)
        assertEquals(1, subject.progress.value.total)
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
        assertEquals("labels with no height at all", "HD", listOf(
            MediaQualityModel(url = "https://cdn.test/hd", label = "HD", type = MediaType.Video),
        ).closestTo("720p")?.label)
    }
}
