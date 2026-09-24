package com.markhoor.mediadownloader

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.markhoor.mediadownloader.core.Constants
import com.markhoor.mediadownloader.di.MediaDownloaderComponent
import com.markhoor.mediadownloader.domain.models.DownloadException
import com.markhoor.mediadownloader.domain.models.DownloadModel
import com.markhoor.mediadownloader.domain.models.DownloadRequest
import com.markhoor.mediadownloader.domain.models.MediaDownloaderNotInitializedException
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaParseException
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.SiteAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The module's single entry point.
 *
 * Call [initialize] once, from `Application.onCreate`, before anything else:
 * ```
 * MediaDownloader.initialize(this, MediaDownloaderConfig(strictSupportedSitesOnly = true))
 * ```
 * Every call is safe from any thread. A call made before [initialize] fails with
 * [MediaDownloaderNotInitializedException] - as `Result.failure` where the call returns a
 * `Result`, thrown otherwise.
 */
object MediaDownloader {

    /** Extra on the intent a download notification opens the app with: the download's id (Long). */
    const val EXTRA_DOWNLOAD_ID = Constants.Download.EXTRA_DOWNLOAD_ID

    @Volatile
    private var component: MediaDownloaderComponent? = null

    /** Whether [initialize] has run. */
    val isInitialized: Boolean
        get() = component != null

    /**
     * Sets the module up. Safe to call more than once, from any thread - the process outlives
     * activities, so this can be reached again - but only the first call's [config] is used.
     *
     * @throws IllegalArgumentException when [config] cannot work, with the reason.
     */
    fun initialize(context: Context, config: MediaDownloaderConfig = MediaDownloaderConfig()) {
        if (component != null) return
        require(config.downloadFolderName.isNotBlank() && '/' !in config.downloadFolderName) {
            "MediaDownloaderConfig.downloadFolderName must be one folder name, was \"${config.downloadFolderName}\""
        }
        require(config.notificationIcon != 0) { "MediaDownloaderConfig.notificationIcon must be a drawable resource" }
        synchronized(this) {
            if (component == null) {
                val appContext = context.applicationContext
                val created = MediaDownloaderComponent(appContext, config)
                component = created
                appContext.registerComponentCallbacks(MemoryPressureCallbacks(created))
                // Off the main thread: opening the database is disk work.
                created.scope.launch { created.downloadRepository.restoreUnfinished() }
                created.scope.launch { created.warmUp() }
            }
        }
    }

    // region Links

    /** Whether downloads may be offered for [url]'s site, and if not, why. */
    fun siteAccess(url: String): SiteAccess = requireComponent().checkSiteAccess(url)

    /** Shorthand for `siteAccess(url) == SiteAccess.Allowed`. */
    fun isAllowed(url: String): Boolean = siteAccess(url) == SiteAccess.Allowed

    /**
     * The media behind a pasted or shared link - [text] may be a url or text with a url in it -
     * with every quality labelled and sized where possible. Fails with a [MediaParseException].
     * For a screen, [com.markhoor.mediadownloader.presentation.linkparse.LinkParseViewModel]
     * wraps this with loading state.
     */
    suspend fun parse(text: String): Result<MediaModel> =
        componentResult { it.parseLink(text) }

    // endregion

    // region Downloads

    /**
     * Starts downloading [request] in the background, with a progress notification. Returns the
     * download's id, or fails with a [DownloadException] when the page or media is on a blocked
     * site or the url is not a media url.
     */
    suspend fun download(request: DownloadRequest): Result<Long> =
        componentResult { it.enqueueDownload(request) }

    /** Starts downloading one of [media]'s qualities; see [download] and [DownloadRequest.siteFolder]. */
    suspend fun download(
        media: MediaModel,
        quality: MediaQualityModel,
        fileName: String? = null,
        siteFolder: String? = null,
    ): Result<Long> = download(DownloadRequest.of(media, quality, fileName, siteFolder))

    /**
     * Why downloads cannot be saved right now, or `null` when they can - the folder is written to,
     * so this catches a missing permission *and* Android 10 without legacy storage. Ask it before
     * offering a download, so the user is asked for the permission instead of watching a download
     * fail; [download] refuses with the same [DownloadException.StorageNotWritable] regardless.
     */
    suspend fun storageRefusalOrNull(): DownloadException.StorageNotWritable? {
        val current = requireComponent()
        return withContext(current.ioDispatcher) { current.downloadStorage.writeRefusalOrNull() }
    }

    /** Every download, newest first, updated as they progress. */
    fun observeDownloads(): Flow<List<DownloadModel>> {
        val current = requireComponent()
        // The repository is reached when the flow is collected, on IO: a screen creating its
        // ViewModel on the main thread must not build the database and WorkManager there.
        return flow { emitAll(current.downloadRepository.observeDownloads()) }.flowOn(current.ioDispatcher)
    }

    /** One download, or `null` once it is deleted. */
    fun observeDownload(id: Long): Flow<DownloadModel?> {
        val current = requireComponent()
        return flow { emitAll(current.downloadRepository.observeDownload(id)) }.flowOn(current.ioDispatcher)
    }

    /**
     * Pauses a queued, running or waiting download. Fails with [DownloadException.NotFound] or,
     * for one that is already paused, completed or failed, [DownloadException.InvalidState].
     */
    suspend fun pause(id: Long): Result<Unit> = componentResult { it.downloadRepository.pause(id) }

    /**
     * Carries on a paused download, or tries a failed one again from where it stopped. Fails with
     * [DownloadException.NotFound] or [DownloadException.InvalidState].
     */
    suspend fun resume(id: Long): Result<Unit> = componentResult { it.downloadRepository.resume(id) }

    /**
     * Stops and removes a download; with [deleteFile], a completed download's file is deleted too.
     * Fails with [DownloadException.NotFound].
     */
    suspend fun delete(id: Long, deleteFile: Boolean = false): Result<Unit> =
        componentResult { it.downloadRepository.delete(id, deleteFile) }

    // endregion

    /** The component, or `null` before [initialize] - for code the system can start first. */
    internal fun componentOrNull(): MediaDownloaderComponent? = component

    internal fun requireComponent(): MediaDownloaderComponent =
        component ?: throw MediaDownloaderNotInitializedException()

    /** Runs [block] on IO, where whatever it reaches for first is built. */
    private suspend inline fun <T> componentResult(
        crossinline block: suspend (MediaDownloaderComponent) -> Result<T>,
    ): Result<T> {
        val current = component ?: return Result.failure(MediaDownloaderNotInitializedException())
        return withContext(current.ioDispatcher) { block(current) }
    }

    /** Let's go of cached page scripts when the system asks the app to use less memory. */
    private class MemoryPressureCallbacks(private val component: MediaDownloaderComponent) : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION") // The levels still arrive on older releases, which is where memory is tight.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) component.releaseMemory()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Superseded by onTrimMemory", ReplaceWith("onTrimMemory(level)"))
        override fun onLowMemory() = component.releaseMemory()
    }
}
