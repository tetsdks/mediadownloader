package com.markhoor.mediadownloader.presentation.browser

import com.markhoor.mediadownloader.domain.models.DetectionEvent
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.SiteAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
class BrowserViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val detector = RecordingDetector()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the detector's state becomes the screen's`() = runTest(dispatcher) {
        val viewModel = BrowserViewModel { detector }
        val media = MediaModel("t", null, emptyList(), "s")

        detector.state.value = detector.state.value.copy(
            pageUrl = "https://rumble.com/", progress = 40, siteAccess = SiteAccess.Allowed, isSearching = true,
        )
        advanceUntilIdle()
        assertEquals(PageMediaState.Searching, viewModel.uiState.value.media)
        assertTrue(viewModel.uiState.value.isLoading)

        detector.state.value = detector.state.value.copy(media = media, isDescribingMedia = true, showDownloadButton = true)
        advanceUntilIdle()
        assertEquals(PageMediaState.Found(media, isDescribing = true), viewModel.uiState.value.media)
        assertTrue(viewModel.uiState.value.showDownloadButton)
    }

    @Test
    fun `the detector's moments become the screen's events`() = runTest(dispatcher) {
        val viewModel = BrowserViewModel { detector }
        detector.eventChannel.send(DetectionEvent.RendererGone)
        assertEquals(BrowserEvent.BrowserCrashed, viewModel.events.first())
    }
}
