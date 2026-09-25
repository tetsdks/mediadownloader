package com.markhoor.mediadownloader.di

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.markhoor.mediadownloader.MediaDownloaderConfig
import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.Constants.Device
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.data.browser.MediaDescriber
import com.markhoor.mediadownloader.data.browser.MediaDetectionSession
import com.markhoor.mediadownloader.data.browser.ScriptLibrary
import com.markhoor.mediadownloader.data.browser.SniffPolicy
import com.markhoor.mediadownloader.data.browser.StreamLocator
import com.markhoor.mediadownloader.data.device.DeviceProfile
import com.markhoor.mediadownloader.data.device.NetworkStatus
import com.markhoor.mediadownloader.data.download.DirectFileDownloader
import com.markhoor.mediadownloader.data.download.DownloadEngine
import com.markhoor.mediadownloader.data.download.HlsStreamDownloader
import com.markhoor.mediadownloader.data.download.HttpFileFetcher
import com.markhoor.mediadownloader.data.download.MediaMuxerRemuxer
import com.markhoor.mediadownloader.data.hls.HlsQualityReader
import com.markhoor.mediadownloader.data.local.DownloadDao
import com.markhoor.mediadownloader.data.local.MediaDownloaderDatabase
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.network.MediaSizeProbe
import com.markhoor.mediadownloader.data.network.WebViewCookieSource
import com.markhoor.mediadownloader.data.repo.DownloadRepositoryImpl
import com.markhoor.mediadownloader.data.repo.MediaParserRepositoryImpl
import com.markhoor.mediadownloader.data.scraper.ScraperRacer
import com.markhoor.mediadownloader.data.scraper.ScraperResolver
import com.markhoor.mediadownloader.data.scraper.dailymotion.DailymotionScraper
import com.markhoor.mediadownloader.data.scraper.facebook.FacebookShareScraper
import com.markhoor.mediadownloader.data.scraper.facebook.FacebookVideoScraper
import com.markhoor.mediadownloader.data.scraper.fallback.GetInDeviceScraper
import com.markhoor.mediadownloader.data.scraper.instagram.InstagramEmbedScraper
import com.markhoor.mediadownloader.data.scraper.instagram.InstagramGraphQlScraper
import com.markhoor.mediadownloader.data.scraper.instagram.InstagramPreviewScraper
import com.markhoor.mediadownloader.data.scraper.instagram.InstagramScraper
import com.markhoor.mediadownloader.data.scraper.instagram.InstagramSignedInScraper
import com.markhoor.mediadownloader.data.scraper.linkedin.LinkedInScraper
import com.markhoor.mediadownloader.data.scraper.pinterest.PinterestScraper
import com.markhoor.mediadownloader.data.scraper.tiktok.TikTokScraper
import com.markhoor.mediadownloader.data.scraper.twitter.TwitterScraper
import com.markhoor.mediadownloader.data.storage.DownloadStorage
import com.markhoor.mediadownloader.data.work.DownloadNotifier
import com.markhoor.mediadownloader.data.work.DownloadRunLocks
import com.markhoor.mediadownloader.data.work.DownloadScheduler
import com.markhoor.mediadownloader.domain.repo.DownloadRepository
import com.markhoor.mediadownloader.domain.repo.MediaDetector
import com.markhoor.mediadownloader.domain.usecase.CheckSiteAccessUseCase
import com.markhoor.mediadownloader.domain.usecase.EnqueueDownloadUseCase
import com.markhoor.mediadownloader.domain.usecase.ParseLinkUseCase
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The module's composition root. Plain constructor wiring instead of a DI framework, so a host
 * app is never made to adopt one or to share its version of one.
 *
 * Everything is created on first use and lives as long as the process.
 */
internal class MediaDownloaderComponent(
    val appContext: Context,
    val config: MediaDownloaderConfig,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** What the device can take; decided once. */
    val deviceProfile: DeviceProfile by lazy {
        DeviceProfile.of(
            appContext,
            config.performanceMode
        )
    }

    val checkSiteAccess: CheckSiteAccessUseCase by lazy {
        CheckSiteAccessUseCase(
            strictSupportedSitesOnly = config.strictSupportedSitesOnly,
            extraBlockedHosts = config.extraBlockedHosts,
            allowedRestrictions = config.allowedRestrictions,
        )
    }

    val parseLink: ParseLinkUseCase by lazy {
        ParseLinkUseCase(
            checkSiteAccess = checkSiteAccess,
            repository = MediaParserRepositoryImpl(
                resolver = scraperResolver,
                racer = ScraperRacer(),
                hlsQualityReader = HlsQualityReader(httpFetcher, sizeProbe),
                sizeProbe = sizeProbe,
                ioDispatcher = ioDispatcher,
            ),
        )
    }

    // region Downloads

    /** Work that outlives any screen, such as restoring downloads after a restart. */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + ioDispatcher + CoroutineExceptionHandler { _, error ->
            // Background upkeep must never take the host app down; it is retried on next start.
            Log.e(Download.LOG_TAG, "Background work failed", error)
        },
    )

    private val database: MediaDownloaderDatabase by lazy {
        MediaDownloaderDatabase.create(
            appContext
        )
    }
    val downloadDao: DownloadDao by lazy { database.downloadDao() }
    val downloadStorage: DownloadStorage by lazy {
        DownloadStorage(
            appContext,
            config.downloadFolderName
        )
    }
    val downloadNotifier: DownloadNotifier by lazy {
        DownloadNotifier(
            appContext,
            config.notificationIcon,
            config.notificationActivity
        )
    }
    val downloadRunLocks = DownloadRunLocks()
    val networkStatus: NetworkStatus by lazy { NetworkStatus(appContext) }
    val downloadScheduler: DownloadScheduler by lazy {
        DownloadScheduler(
            WorkManager.getInstance(
                appContext
            )
        )
    }

    val downloadEngine: DownloadEngine by lazy {
        val fileFetcher = HttpFileFetcher(HttpClientFactory.createForDownloads())
        val lowEnd = deviceProfile.isLowEnd
        // One remuxer: a stream's tracks and a direct download's two files are joined the same way.
        val remuxer = MediaMuxerRemuxer()
        DownloadEngine(
            direct = DirectFileDownloader(
                fetcher = fileFetcher,
                maxParallelParts = if (lowEnd) Device.LOW_END_PARALLEL_PARTS else Download.MAX_PARALLEL_PARTS,
            ),
            hls = HlsStreamDownloader(
                fetcher = fileFetcher,
                playlistFetcher = httpFetcher,
                remuxer = remuxer,
                maxParallelSegments = if (lowEnd) Device.LOW_END_PARALLEL_SEGMENTS else Download.MAX_PARALLEL_SEGMENTS,
            ),
            remuxer = remuxer,
            ioDispatcher = ioDispatcher,
        )
    }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepositoryImpl(
            dao = downloadDao,
            scheduler = downloadScheduler,
            storage = downloadStorage,
            notifier = downloadNotifier,
            network = networkStatus,
            ioDispatcher = ioDispatcher,
        )
    }

    val enqueueDownload: EnqueueDownloadUseCase by lazy {
        EnqueueDownloadUseCase(
            checkSiteAccess,
            downloadRepository
        )
    }

    // endregion

    // region Browser

    private val scriptLibrary: ScriptLibrary by lazy {
        ScriptLibrary(
            readAsset = { path ->
                appContext.assets.open(path).bufferedReader().use { it.readText() }
            },
            profile = deviceProfile,
            ioDispatcher = ioDispatcher,
        )
    }
    private val sniffPolicy: SniffPolicy by lazy { SniffPolicy(checkSiteAccess, scraperResolver) }

    private val streamLocator: StreamLocator by lazy {
        StreamLocator(
            fetcher = httpFetcher,
            blocksHostOf = checkSiteAccess::blocksHostOf,
            playlistProbesAtOnce = if (deviceProfile.isLowEnd) Browser.PLAYLIST_PROBES_AT_ONCE_LOW_END else Browser.PLAYLIST_PROBES_AT_ONCE,
        )
    }

    private val mediaDescriber: MediaDescriber by lazy {
        MediaDescriber(
            parseLink = parseLink,
            hlsQualityReader = HlsQualityReader(httpFetcher, sizeProbe),
            sizeProbe = sizeProbe,
            sizeProbesAtOnce = if (deviceProfile.isLowEnd) Browser.SIZE_PROBES_AT_ONCE_LOW_END else Browser.SIZE_PROBES_AT_ONCE,
        )
    }

    /**
     * A detector for one browser screen, living as long as [scope] - its ViewModel's. Made on the
     * main thread, so it is handed its collaborators unbuilt; the session builds them off it.
     */
    fun newMediaDetector(scope: CoroutineScope): MediaDetector = MediaDetectionSession(
        policyProvider = lazy { sniffPolicy },
        scriptsProvider = lazy { scriptLibrary },
        locatorProvider = lazy { streamLocator },
        describerProvider = lazy { mediaDescriber },
        parseLinkProvider = lazy { parseLink },
        cookies = WebViewCookieSource,
        injectIntervalProvider = lazy {
            if (deviceProfile.isLowEnd) Browser.INJECT_INTERVAL_LOW_END_MS else Browser.INJECT_INTERVAL_MS
        },
        scope = scope,
        // One signal at a time: the session's page state is only ever touched from here.
        serialDispatcher = Dispatchers.Default.limitedParallelism(1),
        ioDispatcher = ioDispatcher,
    )

    /**
     * Builds, off the main thread, everything a screen reaches for first: the device check (a
     * system call), the network client (a large class graph to load), the database and
     * WorkManager. Each is created on first use, and a screen's first use is on the main thread -
     * after this has run, it only reads what is already there.
     */
    fun warmUp() {
        deviceProfile
        downloadRepository
        parseLink
        sniffPolicy
        streamLocator
        mediaDescriber
    }

    /** The system is short of memory: let go of what can be read again. */
    fun releaseMemory() {
        scriptLibrary.release()
    }

    // endregion

    // region Network

    private val httpClient: HttpClient by lazy { HttpClientFactory.create() }
    private val httpFetcher: HttpFetcher by lazy {
        HttpFetcher(
            client = httpClient,
            json = HttpClientFactory.json,
            maxResponseBytes = deviceProfile.maxResponseBytes,
        )
    }
    private val sizeProbe: MediaSizeProbe by lazy { MediaSizeProbe(httpClient) }

    // endregion

    // region Scrapers

    private val scraperResolver: ScraperResolver by lazy {
        val getInDevice = GetInDeviceScraper(httpFetcher)
        val instagram = InstagramScraper(
            signedIn = InstagramSignedInScraper(httpFetcher, WebViewCookieSource),
            preview = InstagramPreviewScraper(httpFetcher),
            embed = InstagramEmbedScraper(httpFetcher),
            graphQl = InstagramGraphQlScraper(httpFetcher),
            getInDevice = getInDevice,
        )
        ScraperResolver(
            facebookVideo = FacebookVideoScraper(httpFetcher),
            facebookShare = FacebookShareScraper(httpFetcher, instagram),
            instagram = instagram,
            linkedIn = LinkedInScraper(httpFetcher),
            tikTok = TikTokScraper(httpFetcher),
            twitter = config.twitterApiKey?.takeIf { it.isNotBlank() }
                ?.let { TwitterScraper(httpFetcher, it) },
            dailymotion = DailymotionScraper(httpFetcher, appPackage = appContext.packageName),
            pinterest = PinterestScraper(httpFetcher),
            getInDevice = getInDevice,
        )
    }

    // endregion
}
