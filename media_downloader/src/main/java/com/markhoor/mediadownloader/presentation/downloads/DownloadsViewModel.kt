package com.markhoor.mediadownloader.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.markhoor.mediadownloader.MediaDownloader
import com.markhoor.mediadownloader.core.Constants.Presentation
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.repo.DownloadRepository
import com.markhoor.mediadownloader.domain.usecase.EnqueueDownloadUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The downloads and the actions on them, for a host screen. Obtain it with
 * `by viewModels { DownloadsViewModel.Factory }`, collect [uiState] for the lists and [events] for
 * the outcome of [download].
 */
class DownloadsViewModel internal constructor(
    enqueueDownloadProvider: Lazy<EnqueueDownloadUseCase>,
    repositoryProvider: Lazy<DownloadRepository>,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /**
     * Built on first use, on [ioDispatcher]: the ViewModel is made on the main thread, and the
     * repository opens the database and WorkManager.
     */
    private val enqueueDownload: EnqueueDownloadUseCase by enqueueDownloadProvider
    private val repository: DownloadRepository by repositoryProvider

    val uiState: StateFlow<DownloadsUiState> = flow { emitAll(repository.observeDownloads()) }
        .flowOn(ioDispatcher)
        .map { downloads ->
            val (completed, inProgress) = downloads.partition { it.state == DownloadState.Completed }
            DownloadsUiState.Content(inProgress = inProgress, completed = completed)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(Presentation.STOP_TIMEOUT_MS), DownloadsUiState.Loading)

    private val _events = Channel<DownloadsEvent>(Channel.BUFFERED)
    val events: Flow<DownloadsEvent> = _events.receiveAsFlow()

    fun download(request: DownloadRequest) {
        viewModelScope.launch {
            val event = withContext(ioDispatcher) { enqueueDownload(request) }.fold(
                onSuccess = { id -> DownloadsEvent.Started(id) },
                onFailure = { error -> DownloadsEvent.NotStarted(error) },
            )
            _events.send(event)
        }
    }

    fun download(media: MediaModel, quality: MediaQualityModel, fileName: String? = null) =
        download(DownloadRequest.of(media, quality, fileName))

    fun pause(id: Long) = act(id) { repository.pause(id) }

    fun resume(id: Long) = act(id) { repository.resume(id) }

    fun delete(id: Long, deleteFile: Boolean = false) = act(id) { repository.delete(id, deleteFile) }

    /** Success shows in [uiState] by itself; only a failure needs telling. */
    private fun act(id: Long, action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            withContext(ioDispatcher) { action() }
                .onFailure { error -> _events.send(DownloadsEvent.ActionFailed(id, error)) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val component = MediaDownloader.requireComponent()
                DownloadsViewModel(
                    enqueueDownloadProvider = lazy { component.enqueueDownload },
                    repositoryProvider = lazy { component.downloadRepository },
                    ioDispatcher = component.ioDispatcher,
                )
            }
        }
    }
}
