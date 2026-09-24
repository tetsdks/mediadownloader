package com.markhoor.mediadownloader.presentation.linkparse

import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaParseException

/** Where parsing a pasted or shared link stands. */
sealed interface LinkParseUiState {

    /** Nothing asked yet, or the last answer was dismissed. */
    data object Idle : LinkParseUiState

    /** [url] is being read. */
    data class Loading(val url: String) : LinkParseUiState

    /** The media behind the link, ready to offer. */
    data class Success(val media: MediaModel) : LinkParseUiState

    /** Why the link gave no media; [error] says which case, for the host's own message. */
    data class Failure(val error: MediaParseException) : LinkParseUiState
}
