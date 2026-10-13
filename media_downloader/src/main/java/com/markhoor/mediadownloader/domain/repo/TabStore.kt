package com.markhoor.mediadownloader.domain.repo

import com.markhoor.mediadownloader.domain.models.SavedTabModel

/**
 * Where the open tabs are kept between runs.
 *
 * [saved] answers from memory and is read on the main thread, the moment a browser screen opens:
 * the tabs have to be there before the host asks whether to open its first one, so there is no
 * room for a wait. What makes that safe is that the file is read at start, off the main thread,
 * long before any screen exists. [save] returns at once and writes on its own.
 */
internal interface TabStore {

    fun saved(): List<SavedTabModel>

    fun save(tabs: List<SavedTabModel>)

    /** Reads the file into memory. Called at start, off the main thread. */
    fun load()
}
