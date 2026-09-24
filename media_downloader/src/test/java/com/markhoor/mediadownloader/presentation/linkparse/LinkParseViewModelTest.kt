package com.markhoor.mediadownloader.presentation.linkparse

import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaParseException
import com.markhoor.mediadownloader.domain.repo.MediaParserRepository
import com.markhoor.mediadownloader.domain.usecase.CheckSiteAccessUseCase
import com.markhoor.mediadownloader.domain.usecase.ParseLinkUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class LinkParseViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val media = MediaModel(title = "t", thumbnailUrl = null, qualities = emptyList(), sourceUrl = "s")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: MediaParserRepository) =
        LinkParseViewModel(lazyOf(ParseLinkUseCase(CheckSiteAccessUseCase(true, emptySet()), repository)), dispatcher)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    @Test
    fun `loading, then the media`() = test {
        val answer = CompletableDeferred<Result<MediaModel>>()
        val viewModel = viewModel(object : MediaParserRepository {
            override suspend fun parse(url: String) = answer.await()
        })

        viewModel.parse("https://www.pinterest.com/pin/1/")
        advanceUntilIdle()
        assertEquals(LinkParseUiState.Loading("https://www.pinterest.com/pin/1/"), viewModel.uiState.value)

        answer.complete(Result.success(media))
        advanceUntilIdle()
        assertEquals(LinkParseUiState.Success(media), viewModel.uiState.value)
    }

    @Test
    fun `a refused site ends in its reason`() = test {
        val viewModel = viewModel(object : MediaParserRepository {
            override suspend fun parse(url: String) = Result.success(media)
        })
        viewModel.parse("https://www.youtube.com/watch?v=1")
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is LinkParseUiState.Failure && state.error is MediaParseException.SiteBlocked)
    }

    /** A second link replaces the first; the first one's answer must never land. */
    @Test
    fun `a newer request wins over an older one`() = test {
        val first = CompletableDeferred<Result<MediaModel>>()
        val second = media.copy(title = "second")
        val viewModel = viewModel(object : MediaParserRepository {
            override suspend fun parse(url: String) =
                if (url.endsWith("/1/")) first.await() else Result.success(second)
        })

        viewModel.parse("https://www.pinterest.com/pin/1/")
        advanceUntilIdle()
        viewModel.parse("https://www.pinterest.com/pin/2/")
        first.complete(Result.success(media))
        advanceUntilIdle()

        assertEquals(LinkParseUiState.Success(second), viewModel.uiState.value)
    }

    @Test
    fun `reset returns to idle`() = test {
        val viewModel = viewModel(object : MediaParserRepository {
            override suspend fun parse(url: String) = Result.success(media)
        })
        viewModel.parse("https://www.pinterest.com/pin/1/")
        viewModel.reset()
        advanceUntilIdle()
        assertEquals(LinkParseUiState.Idle, viewModel.uiState.value)
    }
}
