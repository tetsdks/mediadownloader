package com.markhoor.mediadownloader.data.browser

import android.util.Log
import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.asMediaTitle
import com.markhoor.mediadownloader.core.isAdvertMediaUrl
import com.markhoor.mediadownloader.core.isArtworkUrl
import com.markhoor.mediadownloader.core.isBrightcovePlaybackApi
import com.markhoor.mediadownloader.core.isHlsPlaylistUrl
import com.markhoor.mediadownloader.core.isHlsSegmentUrl
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.imdbVideoPageFromStream
import com.markhoor.mediadownloader.core.isSamePageAs
import com.markhoor.mediadownloader.core.isSamePathAs
import com.markhoor.mediadownloader.core.isSpelledOutBy
import com.markhoor.mediadownloader.core.titleFromSlug
import com.markhoor.mediadownloader.data.network.CookieSource
import com.markhoor.mediadownloader.domain.models.DetectionEvent
import com.markhoor.mediadownloader.domain.models.DetectionState
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.domain.models.PageCommand
import com.markhoor.mediadownloader.domain.models.PageSignal
import com.markhoor.mediadownloader.domain.models.ScriptMessage
import com.markhoor.mediadownloader.domain.models.ScriptSource
import com.markhoor.mediadownloader.domain.repo.MediaDetector
import com.markhoor.mediadownloader.domain.usecase.ParseLinkUseCase
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Finds the media on the pages a browser shows: by the page's own address when a parser reads it,
 * by the requests the page makes, and by what the page scripts report from inside it.
 *
 * Signals arrive from three threads - the main thread, Chromium's network thread and the script
 * bridge - and are handled one at a time, in order, on [serialDispatcher]; everything this class
 * remembers about the page is touched only there, so none of it needs a lock. Network work runs
 * on [ioDispatcher] and comes back to [serialDispatcher], where an answer for a page the browser has
 * already left is dropped.
 *
 * The collaborators come in as [Lazy] and are first read on [serialDispatcher] or [ioDispatcher]:
 * the session is made with a screen's ViewModel, on the main thread, and building them means
 * loading the network client and asking the system about the device.
 *
 * @param injectIntervalMs the shortest gap between two injections of a script into one page.
 * @param clock milliseconds, for spacing injections.
 */
internal class MediaDetectionSession(
    policyProvider: Lazy<SniffPolicy>,
    scriptsProvider: Lazy<ScriptLibrary>,
    locatorProvider: Lazy<StreamLocator>,
    describerProvider: Lazy<MediaDescriber>,
    parseLinkProvider: Lazy<ParseLinkUseCase>,
    private val cookies: CookieSource,
    injectIntervalProvider: Lazy<Long>,
    private val scope: CoroutineScope,
    private val serialDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = { System.nanoTime() / Browser.NANOS_PER_MILLI },
) : MediaDetector {

    private val policy: SniffPolicy by policyProvider
    private val scripts: ScriptLibrary by scriptsProvider
    private val locator: StreamLocator by locatorProvider
    private val describer: MediaDescriber by describerProvider
    private val parseLink: ParseLinkUseCase by parseLinkProvider
    private val injectIntervalMs: Long by injectIntervalProvider

    private val signals = Channel<PageSignal>(Channel.UNLIMITED)

    /** Requests and resources in [signals] not yet looked at; see [Browser.MAX_PENDING_BULK_SIGNALS]. */
    private val pendingBulkSignals = AtomicInteger(0)

    /**
     * Where the session's work runs: a child of [scope], so it ends with the screen, but one whose
     * failures are logged instead of reaching [scope]. A ViewModel's scope has no handler, and an
     * exception escaping into it - a cookie store that throws while the WebView updates - would
     * crash the app.
     */
    private val workScope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, error -> Log.e(Download.LOG_TAG, "Page detection failed", error) },
    )

    private val _state = MutableStateFlow(DetectionState())
    override val state: StateFlow<DetectionState> = _state.asStateFlow()

    private val _events = Channel<DetectionEvent>(Channel.BUFFERED)
    override val events: Flow<DetectionEvent> = _events.receiveAsFlow()

    /** A script is big and only worth running on the page it was built for: stale ones are dropped. */
    private val _commands = Channel<PageCommand>(Browser.COMMAND_BUFFER, BufferOverflow.DROP_OLDEST)
    override val commands: Flow<PageCommand> = _commands.receiveAsFlow()

    /** Everything known about the page on screen. Touched only on [serialDispatcher]. */
    private var page = PageMemory()
    private var userAgent = ""

    /** Bumped whenever the offered media is replaced, so a late description of old media is dropped. */
    private var mediaGeneration = 0

    private var lastInjectedPage = ""
    private var lastInjectedAt = Long.MIN_VALUE / 2
    private var pendingInjection: Pair<String, PageScript>? = null
    private var injectJob: Job? = null
    private var searchJob: Job? = null
    private var fallbackJob: Job? = null
    private var parserJob: Job? = null

    init {
        workScope.launch(serialDispatcher) {
            for (signal in signals) {
                if (isBulk(signal)) pendingBulkSignals.decrementAndGet()
                try {
                    handle(signal)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad signal must not stop detection for the rest of the session.
                    Log.e(Download.LOG_TAG, "Page signal failed: ${signal::class.simpleName}", e)
                }
            }
        }
    }

    override fun onSignal(signal: PageSignal) {
        val bulk = isBulk(signal)
        if (bulk && pendingBulkSignals.incrementAndGet() > Browser.MAX_PENDING_BULK_SIGNALS) {
            pendingBulkSignals.decrementAndGet()
            return
        }
        if (signals.trySend(signal).isFailure && bulk) pendingBulkSignals.decrementAndGet()
    }

    /** The signals a page sends by the hundred; every other one changes what the page is and is never dropped. */
    private fun isBulk(signal: PageSignal): Boolean =
        signal is PageSignal.RequestSeen || signal is PageSignal.ResourceLoaded

    private fun handle(signal: PageSignal) {
        when (signal) {
            is PageSignal.Attached -> userAgent = signal.userAgent
            is PageSignal.PageStarted ->
                if (signal.url.isNotBlank() && signal.url != page.siteUrl) page.siteUrl = signal.url
            is PageSignal.PageCommitted -> onPageCommitted(signal)
            is PageSignal.PageFinished -> keepInstagramSignIn(signal.url)
            is PageSignal.TitleChanged -> {
                if (!signal.title.startsWith("http")) page.title = signal.title.asMediaTitle()
                publish { it.copy(pageTitle = signal.title) }
            }
            is PageSignal.ProgressChanged -> publish { it.copy(progress = signal.progress) }
            is PageSignal.ResourceLoaded -> onResourceLoaded(signal.resourceUrl, signal.livePageUrl)
            is PageSignal.RequestSeen -> onRequestSeen(signal.url, signal.requestHeaders)
            is PageSignal.Script -> onScriptMessage(signal.message)
            PageSignal.DownloadButtonPressed -> if (_state.value.showDownloadButton) showMedia()
            PageSignal.RendererGone -> {
                resetMedia()
                publish { it.copy(isRendererGone = true) }
                _events.trySend(DetectionEvent.RendererGone)
            }
        }
    }

    // region Pages

    private fun onPageCommitted(signal: PageSignal.PageCommitted) {
        page.seenMedia.clear()
        publish { it.copy(canGoBack = signal.canGoBack, canGoForward = signal.canGoForward) }
        if (signal.isReload) return

        val url = signal.url
        // The name and artwork of a tapped card stay only across the navigation the tap made.
        val stillOnTappedCard = page.cardPage.isNotBlank() && url.isSamePathAs(page.cardPage)
        val previous = page
        page = PageMemory(siteUrl = url, title = signal.title.takeUnless { it.startsWith("http") }.orEmpty().asMediaTitle())
        if (stillOnTappedCard) {
            page.cardTitle = previous.cardTitle
            page.cardThumb = previous.cardThumb
            page.cardPage = previous.cardPage
            page.awaitingMedia = previous.awaitingMedia
        }
        fallbackJob?.cancel()
        // A search for the page left behind would report "nothing found" over this one.
        searchJob?.cancel()
        resetMedia()
        publish {
            it.copy(
                pageUrl = url,
                pageTitle = signal.title,
                siteAccess = policy.siteAccess(url),
                scriptAnswered = false,
                isRendererGone = false,
                isSearching = false,
                // Kept while the generic script still works here: a url change without a new
                // document gets no new answer. A page it does not work on draws no such buttons.
                hasScriptButtons = it.hasScriptButtons && policy.usesGenericScript(url),
            )
        }
        if (policy.isParserLink(url) && !policy.hasOwnScript(url) && policy.allowsDownloads(url)) {
            fetchParserMedia(url)
        }
    }

    /** A signed-in Instagram session is written to disk, so the signed-in parser can use it. */
    private fun keepInstagramSignIn(url: String) {
        if (!url.startsWith("https://www.instagram.com/") || url.contains("/accounts/login")) return
        workScope.launch(ioDispatcher) {
            if (cookies.cookiesFor("https://www.instagram.com")?.contains("sessionid") == true) cookies.flush()
        }
    }

    // endregion

    // region Scripts

    private fun onResourceLoaded(resourceUrl: String, livePageUrl: String) {
        // The document the script would land in, and the page the browser last announced: during a
        // navigation they can differ, and both must allow a script before one is put in.
        val pageUrl = livePageUrl.ifBlank { page.siteUrl }
        val script = policy.scriptFor(pageUrl, resourceUrl) ?: return
        val announced = page.siteUrl
        if (pageUrl != announced && livePageUrl.isNotBlank() && announced.isNotBlank() &&
            policy.scriptFor(announced, resourceUrl) == null
        ) {
            return
        }
        injectThrottled(pageUrl, script)
    }

    /**
     * Puts [script] into the page at most once per [injectIntervalMs], and once more after a burst
     * so the last cards loaded are scanned too. A page loads resources by the hundred and each one
     * used to re-inject, which on a slow device meant parsing a 100 kB script dozens of times a second.
     */
    private fun injectThrottled(pageUrl: String, script: PageScript) {
        val now = clock()
        val sinceLast = now - lastInjectedAt
        if (pageUrl == lastInjectedPage && sinceLast < injectIntervalMs) {
            pendingInjection = pageUrl to script
            if (injectJob?.isActive != true) {
                injectJob = workScope.launch(serialDispatcher) {
                    delay((injectIntervalMs - sinceLast).milliseconds)
                    val (pendingPage, pendingScript) = pendingInjection ?: return@launch
                    pendingInjection = null
                    if (pendingPage == page.siteUrl) inject(pendingPage, pendingScript)
                }
            }
            return
        }
        inject(pageUrl, script)
    }

    private fun inject(pageUrl: String, script: PageScript) {
        lastInjectedPage = pageUrl
        lastInjectedAt = clock()
        val singleMediaPage = policy.pageShowsOneMedia(pageUrl)
        val parserSite = policy.isParserSite(pageUrl)
        workScope.launch(serialDispatcher) {
            val source = scripts.build(script, singleMediaPage, parserSite, policy.postPathFor(pageUrl))
            _commands.trySend(PageCommand.RunScript(source))
        }
    }

    private fun onScriptMessage(message: ScriptMessage) {
        when (message) {
            is ScriptMessage.ButtonsDrawn ->
                publish { it.copy(hasScriptButtons = message.count > 0, scriptAnswered = true) }
            is ScriptMessage.CardChanged -> onCardChanged(message)
            is ScriptMessage.MediaRequested -> onMediaRequested(message)
            is ScriptMessage.MediaFound -> onMediaFound(message)
        }
    }

    /**
     * The card on screen changed. A sniffed stream has no name or artwork of its own, so the
     * card's are kept for it - and the media already offered is relabelled, because TikTok fetches
     * a reel a moment before its card slides in.
     */
    private fun onCardChanged(message: ScriptMessage.CardChanged) {
        val cardTitle = message.title.orEmpty().asMediaTitle()
        val cardThumb = message.thumbnail?.takeIf { it.isArtworkUrl() }.orEmpty()
        // What a tap said about its download beats what the page says afterwards.
        val tapIsWaiting = page.tappedPage.isNotBlank() && page.tappedPage.isSamePathAs(page.siteUrl)
        if (!tapIsWaiting) {
            page.cardTitle = cardTitle
            page.cardThumb = cardThumb
            page.cardPage = page.siteUrl
        }
        val pageMedia = message.mediaUrl?.takeIf { it.isHttpUrl() }
        if (pageMedia != null && _state.value.media == null && page.seenMedia.add(pageMedia)) {
            // A card opened from a feed arrives with its video cached: nothing is requested again,
            // so the page's own statement of its stream is the only way to it.
            val type = if (message.isImage) MediaType.Image else MediaType.Video
            offer(
                mediaOf(
                    url = pageMedia,
                    type = type,
                    title = page.cardTitle.ifBlank { cardTitle },
                    thumbnail = page.cardThumb.ifBlank { cardThumb }.ifBlank { pageMedia.takeIf { message.isImage }.orEmpty() },
                ),
            )
            showMediaIfAwaited()
            return
        }
        if (cardTitle.isBlank() && cardThumb.isBlank()) return
        if (tapIsWaiting || page.parserFoundMedia) return
        val current = _state.value.media ?: return
        publish {
            it.copy(
                media = current.copy(
                    title = cardTitle.ifBlank { current.title },
                    thumbnailUrl = cardThumb.ifBlank { current.thumbnailUrl.orEmpty() }.ifBlank { null },
                ),
            )
        }
    }

    /**
     * A per-media button was pressed on a site with no parser. What was found before is dropped,
     * so what is found next is this card's; then the card's own stream is looked for every way
     * the page allows, from the most certain to the last stream heard.
     */
    private fun onMediaRequested(message: ScriptMessage.MediaRequested) {
        val site = page.siteUrl
        val cardPageUrl = message.cardPageUrl.orEmpty()
        val tapThumb = message.thumbnail?.takeIf { it.isArtworkUrl() }.orEmpty()
        // A tap with no artwork keeps what the page said about itself, when it is this page.
        if (tapThumb.isNotBlank() || page.cardPage != site) page.cardThumb = tapThumb
        page.cardPage = cardPageUrl
        // The player on the page knows how long its media runs as soon as it has read the header,
        // and on most sites nothing else here ever does: no parser reads them, and a file's own
        // length would cost a request and a read. A parser's answer still wins over this.
        page.cardDurationMillis = message.durationMillis
        page.tappedPage = site
        // A slug names the media when it is the media's own page: a card's, or a page holding one
        // video. The page a press came from is neither when it is a listing - imdb's "fall tv
        // guide" plays trailers inside it, and its slug named every one of them.
        val slugNamesMedia = !cardPageUrl.isSamePageAs(site) || policy.pageShowsOneMedia(site)
        val fromSlug = if (slugNamesMedia) cardPageUrl.titleFromSlug() else ""
        page.cardTitle = tappedTitle(message.title.orEmpty().asMediaTitle(), fromSlug, site)

        page.seenMedia.clear()
        resetMedia()
        // The tap restarts the player, so its segments are worth one more look.
        page.segmentPlaylistSought = false
        lookForStreams()
        page.scriptFoundMedia = false
        page.awaitingMedia = !message.isPlaying
        if (message.isPlaying) showMedia()

        // A pre-roll plays from the player's own element, so the file handed over is the advert. It
        // is not the media and not a reason to give up: the press is answered as a player with no
        // file of its own, from the page's stream or the one the player moves on to.
        val direct = message.mediaUrl?.takeIf { it.isHttpUrl() && !it.isAdvertMediaUrl() }
        when {
            // A post a parser reads: its own link gives its name and every quality, and the feed stays put.
            cardPageUrl.isNotBlank() && policy.isParserLink(cardPageUrl) -> {
                showMedia()
                fetchParserMedia(cardPageUrl)
            }
            direct == null && !message.isPlaying && !message.isImage && cardPageUrl.isHttpUrl() ->
                readCardPage(cardPageUrl)
            // A player showing a preview of its video: imdb's listings play a few seconds of each,
            // all the same length and size, and the video itself is on the page this names.
            direct?.imdbVideoPageFromStream()?.takeIf { !it.isSamePageAs(site) } != null -> {
                val videoPage = direct.imdbVideoPageFromStream().orEmpty()
                page.cardPage = videoPage
                page.awaitingMedia = true
                readCardPage(videoPage)
            }
            direct != null -> onMediaFound(
                ScriptMessage.MediaFound(
                    source = ScriptSource.Generic,
                    type = if (message.isImage) MediaType.Image else MediaType.Video,
                    mediaUrl = direct,
                    thumbnail = page.cardThumb.ifBlank { direct.takeIf { message.isImage }.orEmpty() }.ifBlank { null },
                    title = page.cardTitle.ifBlank { null },
                ),
            )
            message.isImage -> {
                if (!message.isPlaying) showMedia()
                fallBackToLastStream()
            }
            else -> answerFromLoadedPage(message.isPlaying)
        }
    }

    /**
     * A press on a player with no file of its own - a blob, or a frame the script cannot see into -
     * answered from what the page already loaded.
     */
    private fun answerFromLoadedPage(isPlaying: Boolean) {
        // The player this press was on is made to fetch its stream again, so it is given a moment
        // to say which video it plays: a page may hold one per row - imdb's listings do - and the
        // stream the page remembers is then only the first row's, which is what every press was
        // being answered with. What the page already loaded answers when nothing is heard: a
        // player streaming through MSE asks for nothing when it is restarted.
        if (!isPlaying) showMedia()
        page.awaitingPressedStream = true
        val remembered = page.stream ?: page.masters.values.singleOrNull()
        fallBackToLastStream(
            master = remembered,
            waitMs = if (remembered != null) Browser.PRESSED_STREAM_WAIT_MS else Browser.SNIFFER_FALLBACK_MS,
        )
    }

    /**
     * A card's name. On one media's page the page's own heading is best; on a feed the slug wins,
     * since a heading there can be the player's chrome ("Premium Only Content") - unless the heading
     * spells the slug out, when it is the same name with its punctuation kept.
     */
    private fun tappedTitle(fromDom: String, fromSlug: String, site: String): String = when {
        policy.pageShowsOneMedia(site) && fromDom.isNotBlank() -> fromDom
        fromSlug.isSpelledOutBy(fromDom) -> fromDom
        fromSlug.length >= Browser.MIN_SLUG_TITLE_LENGTH -> fromSlug
        fromDom.length > Browser.LONG_DOM_TITLE_LENGTH -> fromDom
        fromSlug.isNotBlank() -> fromSlug
        else -> fromDom
    }

    /** Asks again the questions the page already let slip: its Brightcove api, its last segment. */
    private fun lookForStreams() {
        val apiUrl = page.brightcoveApiUrl
        if (apiUrl.isNotBlank()) {
            val headers = page.brightcoveApiHeaders
            lookFor(headers) { locator.brightcoveStream(apiUrl, headers) }
        }
        val segment = page.lastSegment
        if (segment.isNotBlank()) {
            page.segmentPlaylistSought = true
            val headers = page.lastSegmentHeaders
            val query = page.lastPlaylistQuery
            lookFor(headers) { locator.playlistForSegment(segment, headers, query) }
        }
    }

    /** A card on a site with no parser: its page is read for the file it names, not opened. */
    private fun readCardPage(cardPageUrl: String) {
        if (!policy.allowsDownloads(cardPageUrl)) {
            // Nothing could be found there; the tap opens the card, as a tap on a link does.
            _commands.trySend(PageCommand.Load(cardPageUrl))
            return
        }
        val headers = headersForPageMedia()
        val token = page.token
        showMedia()
        workScope.launch(ioDispatcher) {
            val onPage = locator.bitchuteVideoId(cardPageUrl)?.let { locator.bitchuteMedia(it) }
                ?: locator.mediaOnPage(cardPageUrl, headers)
            withContext(serialDispatcher) {
                if (token != page.token) return@withContext
                if (onPage != null) {
                    onMediaFound(
                        ScriptMessage.MediaFound(
                            source = ScriptSource.Generic,
                            type = MediaType.Video,
                            mediaUrl = onPage,
                            thumbnail = page.cardThumb.ifBlank { null },
                            title = page.cardTitle.ifBlank { null },
                        ),
                    )
                } else if (cardPageUrl.isSamePageAs(page.siteUrl)) {
                    // A frame's press hands over the page it sits on. Loading that again would
                    // only reload what is on screen - hqporner's player is a frame from another
                    // site - so the answer is what the frame itself streams.
                    answerFromLoadedPage(isPlaying = false)
                } else {
                    _commands.trySend(PageCommand.Load(cardPageUrl))
                }
            }
        }
    }

    /** A player streaming through MSE asks for nothing when restarted; the last stream heard is its. */
    private fun fallBackToLastStream(master: PageStream? = null, waitMs: Long = Browser.SNIFFER_FALLBACK_MS) {
        fallbackJob?.cancel()
        fallbackJob = workScope.launch(serialDispatcher) {
            delay(waitMs.milliseconds)
            page.awaitingPressedStream = false
            val (url, headers) = master?.let { it.url to it.headers } ?: page.lastSniffed ?: return@launch
            if (_state.value.media != null) return@launch
            offer(mediaOf(url, MediaType.Video, page.cardTitle, page.cardThumb, headers))
            // The press has its answer. A live-cam widget beside thisvid's player streams on,
            // and each piece it fetched replaced the video until the sheet offered one of them.
            page.scriptFoundMedia = true
            showMediaIfAwaited()
        }
    }

    /** A site script found media: a file, a post for the parser, or both. */
    private fun onMediaFound(message: ScriptMessage.MediaFound) {
        page.seenMedia.clear()
        resetMedia()
        val mediaUrl = message.mediaUrl?.takeIf { it.isNotBlank() }
        val postUrl = message.postUrl?.takeIf { it.isNotBlank() }
        if (mediaUrl?.isAdvertMediaUrl() == true) {
            hideMedia()
            return
        }
        page.scriptFoundMedia = mediaUrl != null || postUrl != null
        when {
            mediaUrl != null -> {
                // Only the generic script reads a url out of the page, and a site may serve its own
                // file only to a request that looks like it came from its page.
                val headers = if (message.source == ScriptSource.Generic) headersForPageMedia() else emptyMap()
                offer(
                    media = mediaOf(mediaUrl, message.type, message.title.orEmpty().asMediaTitle(), message.thumbnail.orEmpty(), headers),
                    postUrl = postUrl,
                    keepFoundUrl = message.source == ScriptSource.Threads,
                )
                showMedia()
            }
            postUrl != null -> {
                showMedia()
                fetchPostMedia(postUrl)
            }
        }
    }

    // endregion

    // region Requests

    private fun onRequestSeen(url: String, requestHeaders: Map<String, String>) {
        val site = page.siteUrl
        if (!policy.sniffsRequestsOn(site) || page.scriptFoundMedia || page.parserFoundMedia) return
        if (!policy.mayTake(url, site)) return

        val headers = headersFor(url, requestHeaders)
        val isSegment = url.isHlsSegmentUrl()
        if (isSegment) {
            page.lastSegment = url
            page.lastSegmentHeaders = headers
        }
        // The signature the page spent on a playlist opens its neighbours on some CDNs.
        if (url.isHlsPlaylistUrl()) page.lastPlaylistQuery = url.substringAfter('?', "")
        if (url.isBrightcovePlaybackApi()) {
            page.brightcoveApiUrl = url
            page.brightcoveApiHeaders = requestHeaders
        }
        val sniffed = policy.classify(url, site)
        if (sniffed is SniffedRequest.Media && url.isHlsPlaylistUrl()) rememberPageStream(url, headers)
        onSniffed(sniffed, headers)
        // Pieces of a video are not a video: once per page, the playlist beside them is looked for.
        if (sniffed == SniffedRequest.Ignore && isSegment && !page.segmentPlaylistSought) {
            page.segmentPlaylistSought = true
            val query = page.lastPlaylistQuery
            lookFor(headers) { locator.playlistForSegment(url, headers, query) }
        }
    }

    /**
     * The stream a player on this page loaded, while every playlist the page asked for is one
     * stream's - its master and variants share a folder. A second stream's playlist (a feed of
     * players) makes it unknown which one a tap means, so none is remembered from then on.
     */
    private fun rememberPageStream(url: String, headers: Map<String, String>) {
        if (page.hasSeveralStreams) return
        val folder = url.streamFolder()
        val known = page.stream
        when {
            known == null -> page.stream = PageStream(folder, url, headers)
            known.folder != folder -> {
                page.stream = null
                page.hasSeveralStreams = true
            }
        }
    }

    private fun onSniffed(sniffed: SniffedRequest, headers: Map<String, String>) {
        when (sniffed) {
            SniffedRequest.Ignore -> Unit
            is SniffedRequest.ParserLink -> fetchParserMedia(sniffed.url)
            is SniffedRequest.Media -> {
                // The stream the pressed player asked for, once it was made to fetch again. Imdb's
                // listings play a few seconds of each video inline - every one of them the same
                // length and size - and that clip says which video it belongs to, whose own page
                // has the video itself.
                if (page.awaitingPressedStream) {
                    page.awaitingPressedStream = false
                    val videoPage = sniffed.url.imdbVideoPageFromStream()
                    if (videoPage != null && !page.siteUrl.isSamePageAs(videoPage)) {
                        page.cardPage = videoPage
                        page.awaitingMedia = true
                        readCardPage(videoPage)
                        return
                    }
                }
                // A player keeps asking for the one variant it plays, never again for its master.
                // Offered as heard, that is one quality - 144p, where xHamster's player starts - so
                // the master already read on this page is offered in its place, with every quality.
                val master = page.masters[sniffed.url.streamFolder()]?.takeIf { sniffed.url.isHlsPlaylistUrl() }
                val url = master?.url ?: sniffed.url
                val sent = master?.headers ?: headers
                if (!page.seenMedia.add(url)) return
                page.lastSniffed = url to sent
                // The page's own name only once it has one; right after a navigation the WebView
                // reports the url as the title, and the slug is readable at once.
                val title = page.cardTitle.ifBlank { page.siteUrl.titleFromSlug() }.ifBlank { page.title.trim() }
                offer(mediaOf(url, MediaType.Video, title, page.cardThumb, sent))
                showMediaIfAwaited()
            }
        }
    }

    /** Runs a lookup off the page's thread and treats its answer as a request the page made. */
    private fun lookFor(headers: Map<String, String>, lookup: suspend () -> String?) {
        val token = page.token
        workScope.launch(ioDispatcher) {
            val found = lookup() ?: return@launch
            withContext(serialDispatcher) {
                if (token != page.token || page.scriptFoundMedia || page.parserFoundMedia) return@withContext
                onSniffed(policy.classify(found, page.siteUrl), headers)
            }
        }
    }

    /** What the page's own request carried, with its cookies and the browser's agent. */
    private fun headersFor(url: String, requestHeaders: Map<String, String>): Map<String, String> = buildMap {
        cookies.cookiesFor(url)?.let { put(Network.HEADER_COOKIE, it) }
        headerValue(requestHeaders, Network.HEADER_REFERER)?.let { put(Network.HEADER_REFERER, it) }
        (headerValue(requestHeaders, Network.HEADER_USER_AGENT) ?: userAgent.takeIf { it.isNotBlank() })
            ?.let { put(Network.HEADER_USER_AGENT, it) }
    }

    /** How a file read out of the page itself is asked for: from the page, by the browser. */
    private fun headersForPageMedia(): Map<String, String> = buildMap {
        page.siteUrl.takeIf { it.isHttpUrl() }?.let { put(Network.HEADER_REFERER, it) }
        userAgent.takeIf { it.isNotBlank() }?.let { put(Network.HEADER_USER_AGENT, it) }
    }

    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    // endregion

    // region Media

    /** The parser's reading of the page itself (or a card on it); [url] is retried if it fails. */
    private fun fetchParserMedia(url: String?) {
        val link = url ?: page.parserRetryLink ?: return
        val token = page.token
        parserJob?.cancel()
        parserJob = workScope.launch(ioDispatcher) {
            val result = parseLink(link)
            withContext(serialDispatcher) {
                if (token != page.token) return@withContext
                result.onSuccess { media ->
                    page.parserRetryLink = null
                    page.parserFoundMedia = media.qualities.any { it.url.isNotBlank() }
                    offer(media.copy(sourceUrl = page.siteUrl.ifBlank { media.sourceUrl }))
                }.onFailure {
                    page.parserRetryLink = link
                    hideMedia()
                }
            }
        }
    }

    /** A post a site script pointed at, read by the parser. */
    private fun fetchPostMedia(postUrl: String) {
        val token = page.token
        workScope.launch(ioDispatcher) {
            val result = parseLink(postUrl)
            withContext(serialDispatcher) {
                if (token != page.token) return@withContext
                result.onSuccess { offer(it.copy(sourceUrl = page.siteUrl.ifBlank { it.sourceUrl })) }
                    .onFailure { hideMedia() }
            }
        }
    }

    /**
     * Offers [media] at once and describes it after - its qualities, sizes and name. A subtitle
     * playlist is turned away first, so the video's own playlist can take its place.
     */
    private fun offer(media: MediaModel, postUrl: String? = null, keepFoundUrl: Boolean = false) {
        val generation = ++mediaGeneration
        val first = media.qualities.firstOrNull()
        val offeredOn = page
        workScope.launch(ioDispatcher) {
            if (first != null && first.url.isHlsPlaylistUrl() && locator.isSubtitlePlaylist(first.url, first.headers)) {
                return@launch
            }
            withContext(serialDispatcher) {
                if (generation != mediaGeneration) return@withContext
                publish { it.copy(media = media, isDescribingMedia = true, isSearching = false) }
            }
            val described = describer.describe(media, postUrl, keepFoundUrl)
            withContext(serialDispatcher) {
                // Kept even when newer media has replaced this offer: the player asks for its variant
                // right after the master, before the master has been read.
                if (page === offeredOn && first != null && first.url.isHlsPlaylistUrl() && described.qualities.size > 1) {
                    page.masters[first.url.streamFolder()] = PageStream(first.url.streamFolder(), first.url, first.headers)
                }
                if (generation != mediaGeneration) return@withContext
                val shown = _state.value.media ?: media
                // A card that relabelled the media meanwhile knows better than the first guess did.
                val relabelled = described.copy(
                    title = if (shown.title != media.title) shown.title else described.title,
                    thumbnailUrl = if (shown.thumbnailUrl != media.thumbnailUrl) shown.thumbnailUrl else described.thumbnailUrl,
                )
                publish { it.copy(media = relabelled, isDescribingMedia = false) }
            }
        }
    }

    private fun resetMedia() {
        mediaGeneration++
        publish { it.copy(media = null, isDescribingMedia = false) }
    }

    private fun mediaOf(
        url: String,
        type: MediaType,
        title: String,
        thumbnail: String,
        headers: Map<String, String> = emptyMap(),
    ) = MediaModel(
        // With nothing better, the page's own address names it, then the page's title - never
        // left blank for the host to name after the site ("Giphy").
        title = title.ifBlank { page.siteUrl.titleFromSlug() }.ifBlank { page.title },
        thumbnailUrl = thumbnail.ifBlank { null },
        qualities = listOf(MediaQualityModel(url = url, label = QualityLabels.HD, type = type, headers = headers)),
        sourceUrl = page.siteUrl,
        durationMillis = page.cardDurationMillis.takeIf { type == MediaType.Video },
    )

    // endregion

    // region Showing media

    /**
     * Asks the host to show the page's media. If none turns up within [Browser.NOTHING_FOUND_MS],
     * the host is told so - rather than a spinner that never ends.
     */
    private fun showMedia() {
        _events.trySend(DetectionEvent.ShowMedia)
        publish { it.copy(isSearching = it.media == null) }
        if (searchJob?.isActive == true) return
        searchJob = workScope.launch(serialDispatcher) {
            delay(Browser.NOTHING_FOUND_MS.milliseconds)
            val media = _state.value.media
            if (media == null || media.qualities.none { it.url.isNotBlank() }) {
                _events.trySend(DetectionEvent.NothingFound)
            }
            publish { it.copy(isSearching = false) }
        }
    }

    /** A tap that waited for its card's page to play: this is that media. */
    private fun showMediaIfAwaited() {
        if (!page.awaitingMedia) return
        page.awaitingMedia = false
        showMedia()
    }

    private fun hideMedia() {
        searchJob?.cancel()
        publish { it.copy(isSearching = false) }
        _events.trySend(DetectionEvent.HideMedia)
    }

    // endregion

    /** Applies [change] and works out again whether the host's own button belongs on the page. */
    private fun publish(change: (DetectionState) -> DetectionState) {
        var searchEnded = false
        _state.update { current ->
            val next = change(current)
            searchEnded = current.isSearching && !next.isSearching
            next.copy(showDownloadButton = nativeButtonWanted(next))
        }
        // The script marks the button that was pressed while the search runs, so a reader can see
        // the press was taken; this is what tells it to stop, whatever the search came to.
        if (searchEnded) _commands.trySend(PageCommand.RunScript(Browser.SEARCH_DONE_SCRIPT))
    }

    /**
     * The host's button needs media to offer, a page that allows it, and no script already showing
     * a button on the media itself. Where the generic script works, its first answer is waited
     * for, or both buttons would show together for a moment.
     */
    private fun nativeButtonWanted(state: DetectionState): Boolean {
        if (state.media == null || state.isRendererGone || !policy.allowsNativeButton(state.pageUrl)) return false
        if (state.hasScriptButtons) return false
        return !(policy.usesGenericScript(state.pageUrl) && !state.scriptAnswered)
    }

    /** What is known about the page on screen; replaced whole when the browser moves to another. */
    private class PageMemory(
        var siteUrl: String = "",
        var title: String = "",
    ) {
        /** Identifies this page; an answer carrying another page's token is dropped. */
        val token: Any = Any()
        var parserRetryLink: String? = null
        var scriptFoundMedia = false
        var parserFoundMedia = false
        var lastSniffed: Pair<String, Map<String, String>>? = null
        var segmentPlaylistSought = false
        var lastSegment = ""
        var lastSegmentHeaders: Map<String, String> = emptyMap()
        var lastPlaylistQuery = ""
        var brightcoveApiUrl = ""
        var brightcoveApiHeaders: Map<String, String> = emptyMap()
        var tappedPage = ""
        var cardTitle = ""
        var cardThumb = ""
        var cardPage = ""

        /** How long the player that was pressed says its media runs; see [mediaOf]. */
        var cardDurationMillis: Long? = null
        var awaitingMedia = false

        /** The page's one stream, while it has only one; see [rememberPageStream]. */
        var stream: PageStream? = null
        var hasSeveralStreams = false

        /** A press is waiting to hear which stream the player it was on asks for. */
        var awaitingPressedStream = false

        /**
         * Playlists read as masters - more than one quality - by their folder, which their variants
         * share. Only a playlist actually read that way counts, so a CDN folder holding many videos'
         * single playlists never has one of them stand in for another.
         */
        val masters: MutableMap<String, PageStream> = object : LinkedHashMap<String, PageStream>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageStream>?) =
                size > Browser.MAX_PAGE_MASTERS
        }

        /** Media already offered on this page, bounded: a feed can stream hundreds. */
        val seenMedia: MutableSet<String> = BoundedSet(Browser.MAX_SEEN_MEDIA)
    }

    /** A stream's first playlist heard on the page (its master), and the folder its variants share. */
    private class PageStream(val folder: String, val url: String, val headers: Map<String, String>)

    /** Where a playlist sits; a master and its variants share it. */
    private fun String.streamFolder(): String = substringBefore('?').substringBeforeLast('/')

    /** A set that forgets its oldest entries past [limit]. */
    private class BoundedSet(private val limit: Int) : java.util.AbstractSet<String>(), MutableSet<String> {
        private val entries = object : LinkedHashMap<String, Unit>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > limit
        }

        override val size: Int get() = entries.size
        override fun add(element: String): Boolean = entries.put(element, Unit) == null
        override fun iterator(): MutableIterator<String> = entries.keys.iterator()
        override fun clear() = entries.clear()
    }
}
