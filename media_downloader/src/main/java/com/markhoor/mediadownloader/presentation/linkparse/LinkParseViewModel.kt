package com.markhoor.mediadownloader.presentation.linkparse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.markhoor.mediadownloader.MediaDownloader
import com.markhoor.mediadownloader.domain.models.MediaParseException
import com.markhoor.mediadownloader.domain.usecase.ParseLinkUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parses a pasted or shared link for a host screen. Obtain it with
 * `by viewModels { LinkParseViewModel.Factory }` and collect [uiState].
 *
 * Asking again while a link is still being read cancels the earlier request, so an old answer can
 * never replace a newer one.
 */
class LinkParseViewModel internal constructor(
    parseLinkProvider: Lazy<ParseLinkUseCase>,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** Built on first use, on [ioDispatcher]: the ViewModel itself is made on the main thread. */
    private val parseLink: ParseLinkUseCase by parseLinkProvider

    private val _uiState = MutableStateFlow<LinkParseUiState>(LinkParseUiState.Idle)
    val uiState: StateFlow<LinkParseUiState> = _uiState.asStateFlow()

    /** Guards [parseJob]: callers on different threads must not both keep a request running. */
    private val jobLock = Any()
    private var parseJob: Job? = null

    /** Reads the media behind [text] - a url, or text with a url in it. */
    fun parse(text: String) = synchronized(jobLock) {
        parseJob?.cancel()
        _uiState.value = LinkParseUiState.Loading(text.trim())
        // Started only once it is recorded as the current request, and it writes its answer only
        // while it still is: an answer that lands as a newer request begins is dropped.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val answer = withContext(ioDispatcher) { parseLink(text) }.fold(
                onSuccess = { media -> LinkParseUiState.Success(media) },
                onFailure = { error ->
                    LinkParseUiState.Failure(
                        error as? MediaParseException ?: MediaParseException.MediaNotFound(text.trim(), error),
                    )
                },
            )
            synchronized(jobLock) {
                if (parseJob === coroutineContext.job) _uiState.value = answer
            }
        }
        parseJob = job
        job.start()
    }

    /** Back to [LinkParseUiState.Idle], dropping any link still being read. */
    fun reset() = synchronized(jobLock) {
        parseJob?.cancel()
        parseJob = null
        _uiState.value = LinkParseUiState.Idle
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val component = MediaDownloader.requireComponent()
                LinkParseViewModel(lazy { component.parseLink }, component.ioDispatcher)
            }
        }
    }
}
