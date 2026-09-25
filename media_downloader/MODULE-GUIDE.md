# media_downloader — Module Guide

> **Using the module from an app?** Start with [`HOST-GUIDE.md`](HOST-GUIDE.md): setup and every public API, with recipes. This file explains how the module works inside.

## 1. Overview

A self-contained Android library that finds, parses and downloads media from web pages:
link parsing (paste/share), in-browser media detection, and background downloads with
progress. It has **no screens**: the host app owns all UI (browser chrome, search bar, quality
sheet, downloads list) and drives the module through `MediaDownloader`, or through the
ViewModels in `presentation/` for its own screens.

It is meant to be copied into another project as-is: the module depends on no other in-repo
module, only on libraries from the version catalog.

## 2. Using it

```kotlin
// Application.onCreate
MediaDownloader.initialize(
    context = this,
    config = MediaDownloaderConfig(
        strictSupportedSitesOnly = true,
        twitterApiKey = BuildConfig.TWEELOAD_KEY,   // from local.properties, never source
    ),
)

// Before offering a download for a page or a pasted link
when (MediaDownloader.siteAccess(url)) {
    SiteAccess.Allowed -> { /* show the download action */ }
    SiteAccess.Blocked -> { /* adult site, YouTube, or an app-blocked host */ }
    SiteAccess.Unsupported -> { /* not a supported site in strict mode, or not a web page */ }
}

// A pasted or shared link, from a screen
private val viewModel: LinkParseViewModel by viewModels { LinkParseViewModel.Factory }

viewModel.parse(clipboardText)                    // a url, or text with a url in it
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
            when (state) {
                LinkParseUiState.Idle -> Unit
                is LinkParseUiState.Loading -> showProgress()
                is LinkParseUiState.Success -> showQualities(state.media)   // MediaModel
                is LinkParseUiState.Failure -> showError(state.error)       // MediaParseException
            }
        }
    }
}

// Or without a screen
val media: Result<MediaModel> = MediaDownloader.parse(link)
```

### Downloading

```kotlin
// From a parsed link: pick a quality and hand it over
MediaDownloader.download(media, quality)           // Result<Long> - the download's id
    .onFailure { error -> /* DownloadException.SiteBlocked / InvalidMediaUrl */ }

// Or from a screen, with state and one-shot events
private val downloads: DownloadsViewModel by viewModels { DownloadsViewModel.Factory }

downloads.download(media, quality)
repeatOnLifecycle(Lifecycle.State.STARTED) {
    launch {
        downloads.uiState.collect { state ->
            when (state) {
                DownloadsUiState.Loading -> showProgress()
                is DownloadsUiState.Content -> render(state.inProgress, state.completed)   // or an empty state
            }
        }
    }
    launch {
        downloads.events.collect { event ->
            when (event) {
                is DownloadsEvent.Started -> toast("Downloading")
                is DownloadsEvent.NotStarted -> toast(event.error.message)
                is DownloadsEvent.ActionFailed -> toast(event.error.message)
            }
        }
    }
}
downloads.pause(id); downloads.resume(id); downloads.delete(id, deleteFile = true)
```

A `DownloadModel` has its `state` (`Queued`, `Downloading`, `Paused`, `WaitingForNetwork`,
`Completed`, `Failed`), `downloadedBytes`, `totalBytes` (a stream's is an estimate that firms up)
and `progressPercent`, the `filePath` and the `errorMessage` of the last failure.

Downloads run in WorkManager with a progress notification, survive the app being closed or its
process being killed, wait for a connection by themselves, and resume from what is already on
disk. Files go to `Download/<downloadFolderName>/Websites/<site>/`, named after the title, and are
added to the media index. Tapping a notification opens the app's launcher activity with
`MediaDownloader.EXTRA_DOWNLOAD_ID` - or, when the launcher is a splash screen that would not
pass the extra on, the screen named in `MediaDownloaderConfig.notificationActivity`.

`DownloadRequest.startPaused` adds a download paused, to be started with `resume` - used to carry
downloads over from an older version of a host app.

**Host requirements:** call `initialize` in `Application.onCreate` (WorkManager can start a
download in a fresh process before any screen); ask for `POST_NOTIFICATIONS` on Android 13+
(without it downloads still run, silently); ask for `WRITE_EXTERNAL_STORAGE` on Android 9 and below;
on Android 10 set `requestLegacyExternalStorage="true"`. A host with its own WorkManager
`Configuration.Provider` must let unknown workers fall through (its factory returns `null` for them).

### Browser

```kotlin
private val browserViewModel: BrowserViewModel by viewModels { BrowserViewModel.Factory }

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    // The host's own WebView (its clients go in here, not on the WebView)...
    val browser = browserViewModel.attach(binding.webView, viewLifecycleOwner, myWebViewClient, myChromeClient)
    // ...or one the module makes: binding.container.addView(browser.webView)
    // val browser = browserViewModel.createBrowser(requireContext(), viewLifecycleOwner)

    browser.load("https://www.dailymotion.com/")
    binding.back.setOnClickListener { if (!browser.goBack()) finish() }
    binding.reload.setOnClickListener { browser.reload() }
    binding.downloadButton.setOnClickListener { browser.onDownloadButtonClick() }

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                browserViewModel.uiState.collect { state ->
                    binding.address.text = state.url
                    binding.progress.isVisible = state.isLoading
                    binding.downloadButton.isVisible = state.showDownloadButton
                    sheet.render(state.media)          // None / Searching / Found(media, isDescribing)
                }
            }
            launch {
                browserViewModel.events.collect { event ->
                    when (event) {
                        BrowserEvent.ShowMedia -> sheet.show()
                        BrowserEvent.HideMedia -> sheet.hide()
                        BrowserEvent.NothingFound -> { toast(R.string.no_media); sheet.hide() }
                        BrowserEvent.BrowserCrashed -> recreateWebView()   // the old one cannot be used again
                    }
                }
            }
        }
    }
}
// From the sheet: MediaDownloader.download(found.media, chosenQuality)
```

The module finds the page's media three ways, and the host only renders the result:
- **the page's address**, when a parser reads it (a Dailymotion video, a pin, a TikTok): found with
  no tap at all;
- **the requests the page makes**, on sites served by sniffing their player (Rumble, Vimeo, TED,
  Bitchute and any other allowed site): streams and files the page asks for;
- **page scripts** put into the page: Facebook, Instagram, X, Threads and TikTok have their own;
  every other allowed site gets the generic one, which draws a button on each picture and video.

`showDownloadButton` is on when there is media, the site allows downloads, and no page script is
showing its own button on the media - one button that says which video it means beats two.
Blocked sites get no script, no sniffing and no button; browsing them still works.

### Errors

Every call is safe from any thread. Nothing fails silently:

- Before `initialize`, calls that return a `Result` fail with
  `MediaDownloaderNotInitializedException`; the others throw it.
- `parse` fails with a `MediaParseException`; `download` with a `DownloadException`
  (`SiteBlocked`, `InvalidMediaUrl`); `pause`/`resume`/`delete` with `DownloadException.NotFound`
  or `InvalidState` (e.g. resuming a completed download).
- A download that fails after its retries ends `Failed` with its `errorMessage`; `resume` tries it
  again from where it stopped.

`MediaModel` carries the title, the cover, the source url and its `qualities`; each
`MediaQualityModel` has a url, a label (`720p`, `HD`, `Page 2`…), a type, a size when known,
the headers its download needs and, for streams that keep sound apart, an `audioUrl`.

A failure is always a `MediaParseException`: `SiteBlocked`, `SiteUnsupported`,
`LinkNotRecognised` (an allowed site, but not a post), or `MediaNotFound`.

To add the module to a project: copy the `media_downloader/` folder, add
`include(":media_downloader")` to `settings.gradle.kts`, and
`implementation(project(":media_downloader"))` to the app.

## 3. Architecture & conventions

```
com.markhoor.mediadownloader
├── MediaDownloader.kt          public entry point
├── MediaDownloaderConfig.kt    public configuration
├── di/                         MediaDownloaderComponent: plain constructor wiring
├── core/
│   ├── Constants.kt            every constant: limits, host lists, endpoints, headers
│   └── Extensions.kt           every extension/helper function
├── domain/
│   ├── models/                 MediaModel, MediaQualityModel, MediaType, SiteAccess, MediaParseException,
│   │                           DownloadModel, DownloadState, DownloadRequest, DownloadException,
│   │                           MediaDownloaderNotInitializedException,
│   │                           PageSignal, ScriptMessage, DetectionState, DetectionEvent, PageCommand
│   ├── policy/                 RestrictedSites (+ RestrictedCategory: YouTube, Adult)
│   ├── repo/                   MediaParserRepository, DownloadRepository, MediaDetector
│   └── usecase/                CheckSiteAccessUseCase, ParseLinkUseCase, EnqueueDownloadUseCase
├── data/
│   ├── network/                HttpClientFactory, HttpFetcher, MediaSizeProbe, CookieSource
│   ├── hls/                    HlsPlaylistParser (pure), HlsQualityReader, …Dto
│   ├── scraper/                SiteScraper, ScraperResolver, ScraperRacer, one package per site
│   ├── browser/                MediaDetectionSession, SniffPolicy, StreamLocator, MediaDescriber,
│   │                           ScriptLibrary (+ assets/media_downloader/*.js)
│   ├── device/                 DeviceProfile: low-end detection
│   ├── download/               DownloadEngine, DirectFileDownloader, HlsStreamDownloader,
│   │                           HttpFileFetcher, StreamRemuxer (MediaMuxer), DownloadTask/ProgressMeter
│   ├── local/                  Room: DownloadEntity, DownloadDao, MediaDownloaderDatabase
│   ├── storage/                DownloadStorage: folders, names, publishing, media scan
│   ├── work/                   DownloadWorker, DownloadScheduler, DownloadNotifier, DownloadRunLocks
│   └── repo/                   MediaParserRepositoryImpl, DownloadRepositoryImpl
└── presentation/
    ├── linkparse/              LinkParseViewModel, LinkParseUiState
    ├── downloads/              DownloadsViewModel, DownloadsUiState, DownloadsEvent
    └── browser/                BrowserViewModel, BrowserUiState, BrowserEvent, MediaBrowser,
                                DetectingWebViewClient/ChromeClient, PageScriptBridge
```

- **Layering:** presentation → domain ← data. Domain is pure Kotlin and imports neither.
- **Visibility:** only the host-facing API is `public`; everything else is `internal`.
- **Naming:** domain models `…Model`, network payloads `…Dto`, Room rows `…Entity`,
  UI state `…UiState`, implementations `…RepositoryImpl`. Enums and exceptions carry no layer
  suffix. No stacked suffixes.
- **One file each:** every extension function lives in `core/Extensions.kt` (grouped by
  region) and every constant in `core/Constants.kt` (grouped by object) - playlist tags and
  scratch file names included. Elsewhere, helpers are plain private functions; only compiled
  regexes stay private beside the one parser that reads them. The one public constant,
  `MediaDownloader.EXTRA_DOWNLOAD_ID`, is an alias of its value in `Constants`.
- **Page scripts are plain `.js` files** in `src/main/assets/media_downloader/`, not Kotlin strings.
  The methods they call are `PageScriptBridge`'s, by exact name and argument count: a missing one
  is a silent TypeError that stops the rest of the script.
- **Null and thread safety:** no `lateinit`, no `!!`; absent values are nullable and checked
  with a clear error for the host. Shared state is immutable, `@Volatile`, atomic or locked, and a
  download's state only changes through conditional writes, so concurrent callers cannot both win.
- **DI:** no framework inside the module (§6). Collaborators are created lazily in
  `MediaDownloaderComponent`.
- **Hosts:** a url's site is decided on its normalised host, never by substring.
- **Errors:** scrapers may throw; `SiteScraper.scrape` turns every failure into
  `Result.failure` in one place. `HttpFetcher` never throws. `CancellationException` is always
  rethrown, never wrapped.
- **Concurrency:** no `GlobalScope`, no `runBlocking` outside tests, the IO dispatcher injected
  into the repository, racing work cancelled as soon as an answer is in.

**Adding a site:** a `SiteScraper` in `data/scraper/<site>/` (a pure `…Parser` object beside it
when the parsing is worth testing), its link check in the *Site links* region of
`Extensions.kt`, its endpoints and headers in `Constants.kt`, a line in `ScraperResolver`, and
its wiring in `MediaDownloaderComponent`.

## 4. Tech stack

| Library | Why |
|---|---|
| Ktor client (OkHttp engine) | every request the parsers make |
| kotlinx-serialization-json | api answers and embedded JSON |
| lifecycle-viewmodel-ktx | the ViewModels in `presentation/` |
| Room (KSP) | the downloads table, the one source of truth for progress |
| WorkManager | running downloads in the background, surviving process death, waiting for network |
| androidx.core | notifications and permission checks |
| lifecycle-runtime-ktx | tying a `MediaBrowser` to its screen's lifecycle |
| JUnit 4, kotlinx-coroutines-test, ktor-client-mock | unit tests; a fake media server for the engine |

## 5. Milestone progress

- [x] **1. Foundation** — module, conventions, site access (blocked/supported), entry point
- [x] **2. Link parsing** — every site scraper, HLS quality expansion, sizes, `parse()`,
      `LinkParseViewModel`
- [x] **3. Download engine** — direct/parallel/HLS downloads, remux + sound, worker, progress,
      pause/resume/delete, retry, network wait, process death *(device-verified on SM-A266B, Android 16)*
- [x] **4. Browser detection** — WebView attach/create, parser pages, request sniffing, page scripts,
      events, low-end mode *(device-verified on SM-A266B, standard and low-end mode)*
- [x] **5. App migration** — the app runs on the module; `url-parser`, `adm_downloader` and the
      app's own download layer are removed *(device-verified on SM-A266B, upgrading a real install)*
- [x] **6. Clean-up** — conventions self-check (no `lateinit`/`!!`, every constant and extension
      in its one file), the app's leftover download helpers removed, guide final

## 6. Decisions & assumptions

- **No DI framework inside the module.** A library that brings Koin forces it, and its version,
  on every host. The host may still wrap `MediaDownloader` in its own DI.
- **`initialize` is idempotent; the first config wins.** The process outlives activities.
- **One host parser.** The old code had two; the app's read a url inside a query as the host.
- **Blocking covers embedded urls everywhere**, and YouTube is part of the block list.
- **Restricted categories are lifted one at a time, never all at once.** `allowYouTube` and
  `allowAdultSites` are separate, both `false` by default; `extraBlockedHosts` is never lifted.
  `RestrictedSites` only names a host's category; `CheckSiteAccessUseCase` alone decides, and the
  sniffer and `StreamLocator` ask it (`blocksHostOf`) instead of keeping their own YouTube test, so
  one switch changes every path at once. A lifted category counts as supported in strict mode,
  or the switch would do nothing there. Media hosts are judged by host only - a CDN request's
  query often carries a referrer. Google Play removes apps that download from YouTube or adult
  sites, so the app sets both from `BuildConfig` fields that are `false`.
- **A string with no host is `Unsupported` in both modes.**
- **No adult-site scrapers.** The old url-parser carried six, unreachable behind the block
  list; they were not brought over.
- **The tweeload key is configuration**, not source (`MediaDownloaderConfig.twitterApiKey`).
  Without it X links are `LinkNotRecognised`. The app reads it from `local.properties`
  (`tweeload.apiKey`, gitignored) into `BuildConfig.TWEELOAD_API_KEY`; the value is the one the
  old url-parser had hard-coded, so a build machine needs that line to parse X links. Instagram's and Pinterest's anonymous
  browser-session cookies stay in `Constants.kt`: they identify no account.
- **Ktor only.** The size probe and the signed-in Instagram request moved off their own OkHttp
  clients onto the shared Ktor client; nothing depends on OkHttp directly.
- **HTML entities are decoded in pure Kotlin** (numeric + common named) instead of
  `Html.fromHtml`, so the parsers run in JVM tests.
- **Behaviour fixed while moving** (each was wrong in the old code):
  - Instagram GraphQL set `Content-Type` by hand, which Ktor rejects; the fallback never ran.
  - `pin.it` short links never resolved to a pin id.
  - TikTok and Dailymotion durations were multiplied by 60 as well as 1000.
  - TikTok's direct fallback lost the video id when the link had no `?is_from_webapp`.
  - X quality names read the width (or nothing, on `/pu/vid/` urls); now the short side.
  - HLS attribute lists were split on commas, cutting `CODECS="avc1…,mp4a…"` in half, and the
    media-playlist reader kept state between calls.
  - A Facebook video post shared by link offered its og:image labelled as a video; the post's
    own video file is now read first.
  - A Facebook reel page with no playable file could win the race with an empty answer.
- **Titles are empty, not "Unknown"**, when a site gives none; the host picks a fallback name.
- **Downloads (Milestone 3):**
  - One WorkManager job per download, constrained to a connection and retried with backoff. That
    replaces the old separate network-watcher worker. A lost connection does not retry: it queues a
    fresh job behind the current one (`APPEND_OR_REPLACE`), which starts the moment the connection
    is back - a retry would wait out a backoff that grows with every drop. Lost connections do not
    count towards the 5 attempts; other failures do. Media that can never be saved (a protected
    stream, an empty playlist - `UnusableMediaException`) fails at once with its reason.
  - A download waiting for a connection can be resumed: that tries again now, as the old app did.
  - The module has its own Room database (`media_downloader.db`, version 1). A schema change needs
    a migration; rows are the user's downloads.
  - Parallel parts only after a ranged request is *answered* with 206, and any part answered with
    the whole file drops back to one piece (the pexels "nine copies" bug). A resume the server
    ignores starts over instead of appending. `bitchute.com` always uses one connection.
  - HLS: best encode of a master, playlists of playlists followed (3 deep), subtitles skipped,
    byte-range pieces asked for by range, each finished piece kept so a retry fetches only the rest.
    A track's pieces are deleted once joined (a marker file says the joined file is whole), so a
    stream needs about twice its size free, not three times.
  - AES-128 streams are decrypted piece by piece - the playlist's IV or the piece's media sequence
    number - with each key fetched once. The old engine saved them still encrypted. SAMPLE-AES and
    DRM cannot be undone and fail at once, saying so.
  - Separate sound that is gone for good (4xx, unusable) leaves the picture a download of its own;
    sound cut off by the connection fails the run so it is retried, never finished silent.
  - A "file" that arrives as a small HLS playlist - a stream whose url did not say so - is
    downloaded as the stream.
  - A ranged answer must start at the byte asked for, and no more than the range is written; a
    part longer than its range, or cut for a file whose length has since changed, is fetched again
    instead of failing the download forever. A resume of a file of unknown size answered with 416
    is the file already whole. Publishing and recording a finished file cannot be split by a stop.
  - Streams are remuxed into a plain MP4 with `MediaExtractor` → `MediaMuxer` (no decoding), sound
    from a separate playlist added in the same pass, timestamps shifted to start at zero. If the
    remux fails the joined stream is kept: it still plays. The old code kept `.ts` bytes under an
    `.mp4` name and remuxed fMP4 piece by piece with a +40 ms guess per piece.
  - The file is written beside its final place under a hidden per-download name and renamed at the
    end - no copy between volumes, no half file in galleries, no two downloads sharing a partial.
    The extension is corrected from the file's first bytes, and the name is picked under a lock.
  - Resuming now carries the separate sound url; the old resume dropped it.
  - File names drop characters file systems refuse plus `#` and `%`; emoji are kept.
  - No analytics inside the module: the host observes states and reports what it wants.
  - `DownloadRequest.siteFolder` lets the host file a kind of media apart (the app's Shorts go to
    `Websites/Other`, as they did). It is one folder name, never a path.
  - `WRITE_EXTERNAL_STORAGE` is declared up to API 29: Android 10 still needs it under the host's
    `requestLegacyExternalStorage`. It was capped at 28, which merged over the app's own entry and
    made every download fail on Android 10.
- **Browser (Milestone 4):**
  - One detector per browser screen, held by `BrowserViewModel`; it holds no view. Signals from the
    main thread, Chromium's network thread and the script bridge go into one channel and are handled
    one at a time on one worker, so the page's state has a single owner and no locks. The old code
    shared it between threads through unguarded fields.
  - A network answer (a playlist beside a segment, a parser result) is dropped if the browser has
    left the page meanwhile; the old code could label a new page with the old page's video.
  - Scripts are injected with `evaluateJavascript` rather than `loadUrl("javascript:…")`, which
    percent-decodes the text (checked: nothing in the scripts relied on that).
  - X pictures: the script hands over the picture's own url, which the old code sent to the parser
    as a post and got nothing; it is now offered as the image it is.
  - A detached WebView keeps a client that survives a renderer crash. All WebViews in an app share
    one renderer, and Chromium kills the app if any live WebView leaves the crash unhandled - found
    on the device with a crash test and three detached WebViews alive.
  - The Pexels script and its pixel script were dead code in the old app and were not brought over;
    Pexels is served by the generic script.
  - A new page cancels the previous page's "nothing found" countdown, and script buttons counted on
    a generic page no longer hide the host's button on a page the generic script does not run on.
- **App migration (Milestone 5):**
  - An upgrade keeps the host's data: the app's old download table stays in its database, under
    the same name and columns, so Room's identity hash is unchanged (checked:
    `5f4a37dc41d16932f90d236a7d4f7db3` before and after) and nothing is wiped.
  - Unfinished downloads from the old engine are imported once, **all paused**: they can be days
    old with expired signed links, and starting them on the first launch after an update would
    spend data unasked. The old engine's scratch folders are deleted - 13 GB on the test phone -
    and its WorkManager jobs cancelled.
  - The app's own notification tap went straight to its home screen; the module now can too
    (`notificationActivity`), instead of through a splash screen that dropped the download id.
  - The app's worker factory built its old worker for every class name; it is gone, and the
    default factory builds the module's worker.
  - The app's JVM tests of the removed code were ported to the module where they pinned
    behaviour (restricted sites, adult-word mirrors, the supported list, Rumble pages). One
    expectation changed on purpose: YouTube is blocked by the module's list.
- **Nothing heavy on the main thread, nothing thrown into the host:**
  - `initialize` only creates the component; restoring downloads and building everything a screen
    first reaches for (device check, network client, database, WorkManager) happen on IO right after.
  - `parse`, `download`, `pause`, `resume`, `delete` run on IO, and `observeDownloads` /
    `observeDownload` reach the database on IO when collected.
  - The module's ViewModels (`BrowserViewModel`, `DownloadsViewModel`, `LinkParseViewModel`) and
    the browser's detection session are handed their collaborators as `Lazy` and first read them
    on a background dispatcher, so making one on the main thread builds nothing - with or without
    the warm-up having finished.
  - Browser detection runs in a child of the ViewModel's scope with its own exception handler:
    anything a background lookup throws is logged under `MediaDownloader`, never raised into the
    host's scope, which has no handler and would crash the app.
  - A notification the system refuses is logged; the download carries on.
  - **The host's button waits for a button that is really there.** `genericButtonsDrawn` reports
    whether the script is holding this page, and `nativeButtonWanted` puts the host's floating
    button away while it is. Tracking outlives placement: an element can stay connected while what
    it showed is gone - a card swapped out under an SPA navigation - and `place()` then hides its
    button with `display:none`. Counting those said the script had the page when the reader could
    see no button at all. Measured on a dailymotion video page, whose player sits in an iframe the
    script cannot reach: one hidden leftover from the feed left the page with no button of either
    kind. The count therefore measures each button's own box, not the tracked element's - an
    element off screen still measures its full size, so scrolling is untouched.
  - **A button on <body> is positioned against the viewport, not the page.** Instagram's floating
    button shares `styledata` with the buttons that sit inside a post's own box, where
    `position:absolute` is right. Appended to `<body>` it is not: instagram's app shell leaves the
    body at zero height, so `bottom` measured against nothing and the button was laid out 177px
    above the top of the screen - present, visible, 40x40, and not on the display. It is `fixed`
    there, at the maximum z-index, because instagram lays its own overlay over a reel.
  - **A file whose sound is a second file is joined, not just the first url.** `audioUrl` used to
    be read only on the HLS path; a direct download took the picture and left the sound where it
    was, and the reader got a silent video. `DownloadEngine.downloadWithSound` fetches both and
    hands them to the same `StreamRemuxer` a stream's tracks go through. The sound is allowed to
    fail - a silent video beats no video - so anything short of both tracks arriving and joining
    leaves the picture as the file. The sound is measured on a meter of its own and folded in at
    the end: a direct download starts its meter from what is on disk for the file it is fetching,
    so sharing one made the progress fall back to nothing the moment the picture finished.
  - **A WebView the module makes may start media by itself.** `MediaBrowser.configure` sets
    `mediaPlaybackRequiresUserGesture = false` (and a wide viewport) only when `ownsWebView`:
    detection has nothing to find until a player asks for its file, and many only do that on play.
    A host's own WebView is never changed underneath it.
  - **A folder that cannot be written refuses the download before it is made.**
    `DownloadStorage.writeRefusalOrNull()` *writes* a hidden probe file into
    `Download/<root>` rather than reading the permission, because on Android 10 a granted
    `WRITE_EXTERNAL_STORAGE` with legacy storage off still cannot write there - a permission check
    reports everything fine and the download then fails with whatever the file system said.
    `storageRefusalFor(sdkInt, granted, legacyStorage)` turns a refusal into the one thing the host
    can do about it (`StorageRefusal`), and `DownloadRepositoryImpl.enqueue` throws it as
    `DownloadException.StorageNotWritable`. Legacy mode is read from the system
    (`Environment.isExternalStorageLegacy()`) rather than assumed from the Android version, so a
    user who *did* grant the permission is never told to grant it again for a manifest flag only a
    new build can add; that refusal also goes to the log, since nobody but the integrator can act
    on it. The answer is reused for `WRITE_PROBE_TTL_MS`, so
    carrying a host's old downloads over probes once rather than once per row.
  - **Media that is not there to take fails at once, and says so.** Both a body that is a page or a
    placeholder picture (`UnusableMediaException`) and a refusal from the server
    (`HttpStatusException.isMediaGone()`: any 4xx but 408 and 429) end the download immediately with
    "This video is private or no longer available", instead of four more attempts over a growing
    backoff that are told the same thing. Measured: a TikTok video the api cannot see answers
    HTTP 400 - it used to spend 5 attempts and finish as a bare "Download failed".
    The row is marked too, so a screen can say it as well: the reason is stored behind
    `Download.MEDIA_GONE_MARKER`, and `DownloadEntity.toModel` takes the marker off again and
    raises `DownloadModel.isMediaGone`. A marker rather than a column, because a new column
    changes the schema and this database wipes itself rather than migrate; a row that failed
    before this existed simply reports an ordinary failure.
  - **A download waiting for a connection says so.** Both places it can start waiting post
    "Waiting for a connection": the job carries a network constraint, so a connection lost while
    downloading arrives as a WorkManager *stop* (`DownloadWorker.onStopped`, never `onFailed`),
    and one asked for with no connection never starts the worker at all
    (`DownloadRepositoryImpl.startAndSayIfItMustWait`). Without both, the download is silent - its
    progress notification vanishes, or none ever appears - until the connection comes back, which
    reads as a download that died. The worker clears it with `clearResult` the moment it runs, and
    a pause or a delete clears it through `notifier.cancel`; the stop path checks that its own
    write moved the row, so a pause or delete racing it stays silent.
- **Low-end devices and low memory:**
  - `PerformanceMode.Auto` treats a device as low-end when Android says it is low-RAM, the app heap
    is at most 128 MB, total memory is at most 3 GB, or it has at most 4 cores.
  - Page scripts: injected at most once per 500 ms (1.2 s low-end) plus once after each burst,
    instead of once per resource the page loads - the generic script is 100 kB. Their own DOM scans
    pause while the page is hidden and run 2.5× slower on low-end; the endless pulse animation on
    their buttons is off on low-end.
  - The main thread and Chromium's network thread only post signals; requests for style sheets,
    fonts and scripts are dropped before that.
  - Page and api reads are streamed and capped (25 MB, 8 MB low-end; pages read for a card's file
    3 MB), where before only a declared length was checked. The cap is also never more than a
    sixteenth of the heap: a body is held as bytes and then as text, and scrapers race.
  - A stream is fetched by a fixed set of workers, not a coroutine per piece; the remux buffer is
    held between 1 and 16 MB whatever the stream claims.
  - Page signals: the requests and resources a page fires by the hundred wait in a queue of at most
    512 and the newest are dropped past that; a page change is never dropped.
  - Page-supplied text is cut before it is kept: titles to 1,000 characters, urls over 8 kB (a
    data: url) dropped, a download row's headers to 32 of bounded length, its error to 1,000.
  - Fewer things at once on low-end: 4 parts per file and 4 stream pieces (8 standard), 7 playlist
    probes (21), 2 size probes (4).
  - Cached page scripts are released when Android signals memory pressure.
  - A killed WebView renderer is reported as `BrowserEvent.BrowserCrashed` instead of crashing
    the app; a module-created WebView lets Android reclaim its renderer while out of sight.

- **Titles from pages go through one normaliser** (`asMediaTitle`): entities decoded, leading
  decoration ("▶️") dropped, attribution lines, bare addresses and player labels rejected, site
  suffixes (`Constants.Titles.SITE_SUFFIXES`, LinkedIn's "posted on the topic") and repeated
  segments removed, and a caption that ran on into escaped JSON cut where it ended. A title left
  blank falls back to the page's address slug, then the page title, never to the site's name.
- **Detection beyond the per-site rules:** a video file (`.mp4/.webm/.m4v/.mov`) the page's own
  player asks for is media on any allowed site; the generic script sees media inside shadow roots
  (the hit test answers with the shadow host) and gives the only video on a page the page's
  og:image when the player sets no poster. Qualities of a master playlist are listed best first,
  one per label.
- **A press on a player with no file of its own** (a blob, streamed through MSE) is answered from
  what the page already loaded, since its playlist was requested before the press: the page's one
  stream, or the one playlist on the page read as a master (more than one quality). An advert's
  stream from another host is never a master, so a pre-roll does not hide the video. A file the
  element hands over that is an advert is treated as no file at all, not as the answer. A page
  with several masters (a feed) is not guessed at.
- **A variant is offered as its master.** A player asks for the one variant it plays and never
  again for its master, so a variant whose folder holds a master already read on the page is
  replaced by that master, with every quality. Only a playlist actually read as a master counts,
  so a CDN folder holding many videos' single playlists never has one stand in for another.
- **A stream's pieces are never video files**, though they end in `.mp4`: an fMP4 init segment
  (`init-v1-a1.mp4`) and anything inside a folder named like a media file (`480p.av1.mp4/…`).
- **A page's own slug names its media only when the page is that media's**: a card's page, or a
  page the module reads as holding one video. Imdb's "fall tv guide" plays trailers inside it, and
  its slug named every one of them. Only a video's own page on imdb counts as one media's page -
  its listings and a film's page do not.
- **A heading beside the media** names it when the markup gives nothing else: imdb plays a trailer
  inside its own island of divs, with the title written under the player. Only a heading across
  the media and within a couple of lines of it, and only once the walk and the card's text have
  found nothing.
- **A card's link is found by the nearest link the media sits inside**, at any depth (bounded by
  the card's own height, so a link far taller than the media is the page around it). Pinterest
  keeps a pin's `<a>` sixteen levels above the picture, one past where the walk used to give up:
  its pins had no card, so a video pin's button offered the grid thumbnail rather than the pin.
- **A player is asked what it is playing** when its element holds only a blob: JW Player names the
  file in its playlist item. A page may hold a player per row - imdb's listings do - and without
  it every press there was answered from the one stream the page had heard, so each row offered the
  same video, at the same size, under the same name. A press also waits a moment
  (`PRESSED_STREAM_WAIT_MS`) for the player it was on to fetch, before what the page already loaded
  answers instead.
- **Imdb plays a few seconds of a video in its listings** (`/mc/vi<id>/previews/...`), all of them
  the same length and size. Those are never offered: the press resolves the video's own page from
  the id, which holds the video itself.
- **On a site the parser reads, a picture is offered by way of its post**, so a card leading
  anywhere else gets no button at all (`Constants.Browser.PARSER_POST_PATHS`, handed to the page
  script as `window.mksPostPath`). Pinterest's "Browse by category" tiles are pictures inside
  links to `/ideas/...`: their buttons could only hand over a listing, so pressing one read the
  card, found nothing and opened the category page - which reads as a button that does nothing. Imdb's
  posts live at `/video/`, so its home - a poster per film, with the section's link lying beside
  each - draws no buttons at all; a picture there needs a link of its own, not one found next to
  it. A press on a player is never turned into a press on the tile.
- **A poster standing for a video on another page** is handed over as that page, even on a page
  read as holding one media: imdb's film page shows the trailer's slate, whose link is the page
  that has it. A video hidden again (imdb keeps its trailer's `<video>` behind the poster with
  `visibility: hidden`) gives its button up, so the poster can take it; a poster only stands aside
  for a video that has a button of its own. Imdb's few-second autoplay preview
  (`hls-preview-….m3u8`) is not offered as the film.
- **The generic script's titles** skip text that is not rendered (xvideos keeps its age gate's
  heading hidden in the page), a lone media file name (YouPorn's player debug panel), the
  player's own labels (quality and speed menus, "Player settings"), loading notices, and rows of
  labelled stats ("Rating: … Viewed: …"). A leading field label ("Description:") is dropped.
- **A press whose answer came from the page stands.** Once a press is answered - the page's stream,
  its master, or the last file heard - later requests do not replace it: live-cam widgets and
  banner loops beside a player stream on for as long as the page is open.
- **A frame player** (hqporner's comes from another site) hands over the page it sits on. When
  reading that page finds nothing, the press is answered from what the frame streamed; the page is
  never reloaded under the sheet.
- **A player that is only a cover** (KVS/Flowplayer, video.js before play) is started by pressing its
  own play control (`.fp-play`, `.jw-icon-display`, `.vjs-big-play-button`, …), looked for only
  inside the player's own box.
- **A player's labelled sources** (`<source size="720">`) - the best one is handed over, not the one
  playing.
- **KVS `get_file` links** end in `.mp4/`, and count as video files. They are often single use: a
  spent one answers with a GIF. The size probe ignores a picture or a page sent in a video's place,
  and a download whose bytes turn out to be one fails as unusable instead of being saved.
- **Adverts on the adult tubes:** the TrafficStars, adtng, ExoClick and live-cam CDNs are advert
  hosts, and so are the rotating `<8 chars>.bkcdn.net` / `.bxcdn.net` hosts when the file is named
  by a hash. The advert-label rule applies only to the advert's own card (the media and a caption),
  not to a column that happens to hold the player and a labelled banner.
- **A thumbnail is an image or nothing.** A download keeps its `thumbnailUrl` only when it is a
  `data:image/` url or an http(s) url that is not a playlist or a media file
  (`isUsableThumbnail`): the old app stored Pinterest's `.m3u8` as the "thumbnail", which no image
  loader can draw. Imported Pinterest rows get their cover rebuilt from the stream url
  (`i.pinimg.com/videos/thumbnails/originals/...`).

## 7. Known gaps

- Instagram, Facebook and LinkedIn scrapers are verified by unit tests on sample pages, not yet
  live: they need a signed-in session or real post links.
- The module logs only failures it cannot report any other way (a job started before
  `initialize`, background upkeep errors), under the tag `MediaDownloader`.
- Pinterest downloads are filed under `Website`, as in the old app; `Constants.Storage.SITE_FOLDERS`
  is the place to give it a folder.
- While the app is force-stopped a running download's row still reads `Downloading`; it is carried
  on at the next launch. Android cancels a force-stopped app's jobs, so nothing can run meanwhile.
- Imdb's film page offers the trailer by way of its own page (the poster's link); the few-second
  preview the film page autoplays is deliberately not offered. Redtube serves only a playlist shell
  from the VPN region tested, and motherless did not load there at all.
- Not yet verified on Android 9 and 10 (legacy storage paths), nor on a device that is low-end by
  hardware: low-end mode was verified by forcing it on the SM-A266B (the SM-J810F was offline).
- Facebook, Instagram and Threads page scripts are ported unchanged but not yet exercised on
  device: they need signed-in pages. X link parsing and download are verified on device (with
  the key); its page script is not.
- The Facebook page parser matches `"browser_native_hd_url":"…"` as plain JSON. The old code also
  read the key when a page wrote it escaped inside a script string; whether Facebook still serves
  that shape is unverified, so the stricter match was kept (it also stops an SD url being taken for
  HD when HD is `null`).

- Device-tested in September 2026 on the SM-A26 (strict mode on and off): Dailymotion, Rumble, TED,
  Vimeo, IMDb, archive.org, Pexels, Giphy, Imgur, Streamable, Bitchute, analog.com (Brightcove),
  LinkedIn, Instagram (signed out), TikTok, X, Facebook, Pinterest, Shorts; a blocked site and an
  unsupported one in both modes. Not reachable from that network, so untested there: 9GAG, Tumblr
  and Wikimedia Commons (blocked by the ISP; the phone's VPN needed a Wi-Fi that was down).
- Reddit's signed-out "get the app" sheet covers the feed without being fixed or modal, and the
  generic script's button is drawn for the picture under it. Recognising that sheet would mean
  guessing from class names; the button is right again once the sheet is closed.
- Page scripts on signed-in Facebook, Instagram, Threads and X pages are still untested: no test
  account. Their links parse and download (Threads untested: no public post found).
- Restricted sites with `allowAdultSites = true`, device-tested in September 2026 over a VPN by
  pressing the page button on the main player: xvideos, xnxx, pornhub, xhamster, spankbang,
  youporn, tube8, eporner, tnaflix, drtuber, sunporno, youjizz, upornia, hdzog, pornone, porntrex,
  hqporner and empflix offer the video with its qualities and sizes. Thisvid's `get_file` links are
  single use, so its file is offered but the download can fail cleanly as unusable. Redtube (only a
  playlist shell from that region) and motherless (unreachable) were not tested. A tap during a
  pre-roll that streams from the player's own element waits for the video; one whose advert is
  served from a host not yet listed can still offer the advert.

## 8. Build & test

```
./gradlew :media_downloader:assembleDebug
./gradlew :media_downloader:testDebugUnitTest
./gradlew :media_downloader:lintDebug
```

Device check - real downloads through the worker, Room and notification, files inspected with
`MediaMetadataRetriever` (duration, picture, sound). Installs only the test APK, never the app:

```
ANDROID_SERIAL=<device> ./gradlew --no-daemon :media_downloader:connectedDebugAndroidTest
```

The browser check alone, in low-end mode:

```
ANDROID_SERIAL=<device> ./gradlew --no-daemon :media_downloader:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.markhoor.mediadownloader.BrowserDeviceCheck \
  -Pandroid.testInstrumentationRunnerArguments.performanceMode=LowEnd
```

Process death, by hand: install the test APK with `adb install -r -t`, run
`am instrument -w -e class com.markhoor.mediadownloader.DownloadDeviceCheck#processDeath -e processDeath start
com.markhoor.mediadownloader.test/androidx.test.runner.AndroidJUnitRunner`, kill the process while it
downloads (`adb shell run-as com.markhoor.mediadownloader.test kill -9 <pid>`), then run the same with
`-e processDeath verify`.

Live check against the real sites (skipped in normal runs):

```
MEDIA_DOWNLOADER_LIVE=1 MEDIA_DOWNLOADER_LINKS="https://www.pinterest.com/pin/… https://…" \
TWITTER_API_KEY=… ./gradlew --no-daemon :media_downloader:testDebugUnitTest --tests "*LiveParseCheck" --rerun -i
```
