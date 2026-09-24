package com.markhoor.mediadownloader.domain.repo

import com.markhoor.mediadownloader.domain.models.DetectionEvent
import com.markhoor.mediadownloader.domain.models.DetectionState
import com.markhoor.mediadownloader.domain.models.PageCommand
import com.markhoor.mediadownloader.domain.models.PageSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Finds the media on the pages a browser shows. It holds no view: the browser reports through
 * [onSignal], from any thread, and carries out [commands] on its own.
 */
internal interface MediaDetector {
    val state: StateFlow<DetectionState>
    val events: Flow<DetectionEvent>
    val commands: Flow<PageCommand>

    fun onSignal(signal: PageSignal)
}
