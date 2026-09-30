package com.markhoor.mediadownloader.presentation.downloads

import com.markhoor.mediadownloader.domain.models.DownloadException
import com.markhoor.mediadownloader.domain.models.DownloadModel
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.domain.usecase.CheckSiteAccessUseCase
import com.markhoor.mediadownloader.domain.usecase.EnqueueDownloadUseCase
import com.markhoor.mediadownloader.domain.usecase.FakeDownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeDownloadRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = DownloadsViewModel(
        lazyOf(EnqueueDownloadUseCase(CheckSiteAccessUseCase(strictSupportedSitesOnly = true, emptySet()), repository)),
        lazyOf(repository),
        dispatcher,
    )

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun download(id: Long, state: DownloadState) = DownloadModel(
        id = id, title = "t$id", fileName = "t$id.mp4", filePath = "/d/t$id.mp4", sourceUrl = "s", thumbnailUrl = null,
        type = MediaType.Video, qualityLabel = "720p", state = state, downloadedBytes = 50, totalBytes = 200,
        errorMessage = null, createdAtMillis = id,
    )

    @Test
    fun `loading, then downloads split into in progress and completed`() = test {
        val viewModel = viewModel()
        assertEquals(DownloadsUiState.Loading, viewModel.uiState.value)

        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        repository.downloads.value = listOf(
            download(3, DownloadState.Downloading),
            download(2, DownloadState.Completed),
            download(1, DownloadState.Failed),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value as DownloadsUiState.Content
        assertEquals(listOf(3L, 1L), state.inProgress.map { it.id })
        assertEquals(listOf(2L), state.completed.map { it.id })
        assertEquals(25, state.inProgress.first().progressPercent)
        collector.cancel()
    }

    @Test
    fun `no downloads is an empty content, not loading`() = test {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertTrue((viewModel.uiState.value as DownloadsUiState.Content).isEmpty)
    }

    @Test
    fun `starting a download reports its id, and a refusal its reason`() = test {
        val viewModel = viewModel()

        viewModel.download(DownloadRequest("https://cdn.test/a.mp4", MediaType.Video, "t", "https://www.tiktok.com/@a/video/1"))
        advanceUntilIdle()
        assertEquals(DownloadsEvent.Started(1), viewModel.events.first())

        viewModel.download(DownloadRequest("https://cdn.test/a.mp4", MediaType.Video, "t", "https://www.xvideos.com/v/1"))
        advanceUntilIdle()
        val refused = viewModel.events.first() as DownloadsEvent.NotStarted
        assertTrue(refused.error is DownloadException.SiteBlocked)
    }

    @Test
    fun `actions reach the repository`() = test {
        val viewModel = viewModel()
        viewModel.pause(4)
        viewModel.resume(4)
        viewModel.delete(4, deleteFile = true)
        advanceUntilIdle()
        assertEquals(listOf("pause 4", "resume 4", "delete 4 true"), repository.actions)
    }

    @Test
    fun `an action that did not happen says why`() = test {
        val viewModel = viewModel()
        repository.actionResult = Result.failure(DownloadException.InvalidState(7, DownloadState.Completed, "resume"))

        viewModel.resume(7)
        advanceUntilIdle()

        val event = viewModel.events.first() as DownloadsEvent.ActionFailed
        assertEquals(7L, event.id)
        assertTrue(event.error is DownloadException.InvalidState)
    }
}
