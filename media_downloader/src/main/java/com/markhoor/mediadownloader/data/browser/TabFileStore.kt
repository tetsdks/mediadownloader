package com.markhoor.mediadownloader.data.browser

import android.util.Log
import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.domain.models.SavedTabModel
import com.markhoor.mediadownloader.domain.repo.TabStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A tab on disk. Its own shape, so the file outlives any renaming in the model. */
@Serializable
private data class SavedTabDto(
    val url: String = "",
    val title: String = "",
    val active: Boolean = false,
)

/**
 * The open tabs, in a small file of the app's own. Not the database: tabs are what a screen was
 * showing, and losing them costs a reload, while a download row lost costs a file half written -
 * the two do not belong under one schema, and a browser's tabs should never be a reason to
 * migrate the downloads.
 *
 * The file is written whole, through a temporary name, so a process killed mid-write leaves the
 * tabs as they were rather than half of them.
 *
 * @param fileOf where to keep them; asked for each time so nothing touches the filesystem until
 *   something is actually read or written.
 */
internal class TabFileStore(
    private val fileOf: () -> File,
    private val scope: CoroutineScope,
    private val json: Json,
) : TabStore {

    @Volatile
    private var kept: List<SavedTabModel>? = null

    /** One writer at a time: several tabs changing at once must not interleave in one file. */
    private val writing = Mutex()

    private val serializer = ListSerializer(SavedTabDto.serializer())

    override fun load() {
        kept = read()
    }

    /**
     * What was kept. Read from disk only if the start-up read has not happened yet - the file is
     * a few hundred bytes, and a browser screen opening before the app has finished starting is
     * better served by a short read than by no tabs at all.
     */
    override fun saved(): List<SavedTabModel> = kept ?: read().also { kept = it }

    override fun save(tabs: List<SavedTabModel>) {
        val keep = tabs.take(Browser.MAX_TABS)
        kept = keep
        scope.launch {
            writing.withLock {
                // Whatever is current, not what this call was given: a later change has the say.
                write(kept.orEmpty())
            }
        }
    }

    private fun read(): List<SavedTabModel> {
        val file = fileOf()
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
                .take(Browser.MAX_TABS)
                .map { SavedTabModel(url = it.url, title = it.title, isActive = it.active) }
        }.getOrElse { error ->
            // A file written by a version that did not understand it, or a half-written one: the
            // tabs are gone either way, and refusing to start a browser over it would be worse.
            Log.e(Download.LOG_TAG, "The open tabs could not be read", error)
            emptyList()
        }
    }

    private fun write(tabs: List<SavedTabModel>) {
        val file = fileOf()
        runCatching {
            file.parentFile?.mkdirs()
            val half = File(file.parentFile, file.name + Browser.HALF_WRITTEN_SUFFIX)
            half.writeText(
                json.encodeToString(
                    serializer,
                    tabs.map { SavedTabDto(url = it.url, title = it.title, active = it.isActive) },
                ),
            )
            if (!half.renameTo(file)) {
                file.delete()
                half.renameTo(file)
            }
        }.onFailure { error -> Log.e(Download.LOG_TAG, "The open tabs could not be kept", error) }
    }
}
