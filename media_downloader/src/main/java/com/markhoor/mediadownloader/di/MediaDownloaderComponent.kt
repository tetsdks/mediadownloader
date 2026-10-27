package com.markhoor.mediadownloader.di

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.markhoor.mediadownloader.FixedSetting
import com.markhoor.mediadownloader.MediaCollectionSource
import com.markhoor.mediadownloader.MediaDownloaderConfig
import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.Constants.Device
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.data.browser.MediaDescriber
import com.markhoor.mediadownloader.data.browser.IncognitoProfile
import com.markhoor.mediadownloader.data.browser.MediaDetectionSession
import com.markhoor.mediadownloader.data.browser.TabFileStore
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
import com.markhoor.mediadownloader.data.network.CookieSource
import com.markhoor.mediadownloader.data.network.FallbackCookieSource
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.network.MediaSizeProbe
import com.markhoor.mediadownloader.data.network.WebViewCookieSource
import com.markhoor.mediadownloader.data.repo.DownloadRepositoryImpl
import com.markhoor.mediadownloader.data.repo.MediaParserRepositoryImpl
import com.markhoor.mediadownloader.data.scraper.ScraperRacer
import com.markhoor.mediadownloader.data.scraper.HostSuppliedScraper
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
import com.markhoor.mediadownloader.data.scraper.imdb.ImdbScraper
import com.markhoor.mediadownloader.data.scraper.linkedin.LinkedInScraper
import com.markhoor.mediadownloader.data.scraper.pinterest.PinterestScraper
import com.markhoor.mediadownloader.data.scraper.tube.TubeScraper
import com.markhoor.mediadownloader.data.scraper.tiktok.TikTokPageScraper
import com.markhoor.mediadownloader.data.scraper.tiktok.TikTokScraper
import com.markhoor.mediadownloader.data.scraper.twitter.TwitterScraper
import com.markhoor.mediadownloader.data.storage.DownloadStorage
import com.markhoor.mediadownloader.data.work.DownloadNotifier
import com.markhoor.mediadownloader.data.work.DownloadRunLocks
import com.markhoor.mediadownloader.data.work.DownloadScheduler
import com.markhoor.mediadownloader.domain.policy.RestrictedCategory
import com.markhoor.mediadownloader.domain.policy.RestrictedSites
import com.markhoor.mediadownloader.domain.repo.DownloadRepository
import com.markhoor.mediadownloader.domain.repo.MediaDetector
import com.markhoor.mediadownloader.domain.repo.TabStore
import com.markhoor.mediadownloader.domain.usecase.CheckSiteAccessUseCase
import com.markhoor.mediadownloader.domain.usecase.DownloadCollectionUseCase
import com.markhoor.mediadownloader.domain.usecase.EnqueueDownloadUseCase
import com.markhoor.mediadownloader.domain.usecase.ParseLinkUseCase
import com.markhoor.mediadownloader.domain.usecase.ReadLinkUseCase
import com.markhoor.mediadownloader.domain.usecase.SiteAccessSettings
import io.ktor.client.HttpClient
import java.io.File
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
    initialConfig: MediaDownloaderConfig,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * How the host app wants the module to behave, now rather than at start: the collaborators
     * that can honour a change ask this each time they need an answer. See [reconfigure] for the
     * ones that cannot, and why.
     */
    @Volatile
    var config: MediaDownloaderConfig = initialConfig
        private set

    /**
     * Takes [wanted] as the configuration from here on, except for the settings something already
     * built is holding - those keep what [MediaDownloaderConfig] was first given, and are answered
     * with so a host app is never left thinking a change landed when it did not.
     */
    fun reconfigure(wanted: MediaDownloaderConfig): Set<FixedSetting> {
        val current = config
        config = wanted.keepingFixedSettingsOf(current)
        return current.fixedSettingsChangedBy(wanted)
    }

    /** What the device can take; decided once. */
    val deviceProfile: DeviceProfile by lazy {
        DeviceProfile.of(
            appContext,
            config.performanceMode
        )
    }

    /** The readers the host app brought, in the shape everything here expects. */
    private val hostSuppliedScrapers: List<HostSuppliedScraper> by lazy {
        config.extraSources.map(::HostSuppliedScraper).filter { it.hosts.isNotEmpty() }
    }

    /**
     * One instance for the life of the process, reading the configuration as it stands: a host app
     * that lifts a block or leaves strict mode is answered from the next question on, including by
     * the browser screens and the downloads already in flight.
     */
    val checkSiteAccess: CheckSiteAccessUseCase by lazy {
        CheckSiteAccessUseCase {
            SiteAccessSettings(
                strictSupportedSitesOnly = config.strictSupportedSitesOnly,
                extraBlockedHosts = config.extraBlockedHosts,
                allowedRestrictions = config.allowedRestrictions,
                extraSupportedHosts = hostSuppliedScrapers.flatMapTo(mutableSetOf()) { it.hosts },
            )
        }
    }

    /**
     * Whether a parser reads this page, so a download whose link was refused has somewhere to go
     * back to for a fresh one. A page found by sniffing alone answers `false`: there is nothing to
     * re-read it with, and the only thing left to try is the link already in hand.
     */
    fun canReadPageOf(url: String): Boolean = scraperResolver.scrapersFor(url).isNotEmpty()

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
            { config.downloadFolderName },
        )
    }
    val downloadNotifier: DownloadNotifier by lazy {
        DownloadNotifier(
            appContext,
            { config.notificationIcon },
            { config.notificationActivity },
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
            // Lazily: observing the downloads must not be what initialises WorkManager.
            schedulerProvider = lazy { downloadScheduler },
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

    /** What a link turns out to be: one piece of media, or a collection listing many. */
    val readLink: ReadLinkUseCase by lazy {
        ReadLinkUseCase(
            checkSiteAccess = checkSiteAccess,
            parseLink = parseLink,
            collectionSources = config.extraSources.filterIsInstance<MediaCollectionSource>(),
        )
    }

    /** Adding a collection outlives the screen that asked for it, so it runs in the module's scope. */
    val downloadCollection: DownloadCollectionUseCase by lazy {
        DownloadCollectionUseCase(enqueue = enqueueDownload, scope = scope)
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

    /** The browser's open tabs, kept between runs in a small file of the module's own. */
    val tabStore: TabStore by lazy {
        TabFileStore(
            fileOf = { File(appContext.filesDir, Browser.TABS_FILE) },
            scope = scope,
            json = HttpClientFactory.json,
        )
    }

    /**
     * The profile every incognito page browses in, and the count of how many want it. One for the
     * process, so two incognito tabs share a session and the last one out empties it.
     */
    val incognitoProfile: IncognitoProfile by lazy { IncognitoProfile() }

    /**
     * A detector for one browser screen, living as long as [scope] - its ViewModel's. Made on the
     * main thread, so it is handed its collaborators unbuilt; the session builds them off it.
     *
     * [cookies] is where the page's session is read from: the browser's own jar, or an incognito
     * page's, which is also the one that is never written to disk.
     */
    fun newMediaDetector(
        scope: CoroutineScope,
        cookies: CookieSource = WebViewCookieSource,
    ): MediaDetector = MediaDetectionSession(
        policyProvider = lazy { sniffPolicy },
        scriptsProvider = lazy { scriptLibrary },
        locatorProvider = lazy { streamLocator },
        describerProvider = lazy { mediaDescriber },
        parseLinkProvider = lazy { parseLink },
        cookies = cookies,
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
        // The browser's tabs are read here so a screen that opens later finds them in memory: it
        // asks for them on the main thread, before it can know whether to open a tab of its own.
        tabStore.load()
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
        // Every Instagram reader asks with the same session: the browser's own, or the host's.
        val instagramCookies = FallbackCookieSource(WebViewCookieSource) { config.instagramCookie }
        val instagram = InstagramScraper(
            fetcher = httpFetcher,
            signedIn = InstagramSignedInScraper(httpFetcher, instagramCookies),
            preview = InstagramPreviewScraper(httpFetcher, instagramCookies),
            embed = InstagramEmbedScraper(httpFetcher, instagramCookies),
            graphQl = InstagramGraphQlScraper(httpFetcher, instagramCookies),
            getInDevice = getInDevice,
            cookies = instagramCookies,
        )
        ScraperResolver(
            facebookVideo = FacebookVideoScraper(httpFetcher, WebViewCookieSource),
            facebookShare = FacebookShareScraper(httpFetcher, instagram, WebViewCookieSource),
            instagram = instagram,
            linkedIn = LinkedInScraper(httpFetcher),
            imdb = ImdbScraper(httpFetcher, WebViewCookieSource),
            tikTok = TikTokScraper(httpFetcher, TikTokPageScraper(httpFetcher, WebViewCookieSource)),
            twitter = config.twitterApiKey?.takeIf { it.isNotBlank() }
                ?.let { TwitterScraper(httpFetcher, it) },
            dailymotion = DailymotionScraper(httpFetcher, appPackage = appContext.packageName),
            pinterest = PinterestScraper(httpFetcher),
            tube = TubeScraper(httpFetcher, WebViewCookieSource) {
                RestrictedSites.categoryOf(it) == RestrictedCategory.Adult
            },
            getInDevice = getInDevice,
            hostSupplied = hostSuppliedScrapers,
        )
    }

    // endregion
}
