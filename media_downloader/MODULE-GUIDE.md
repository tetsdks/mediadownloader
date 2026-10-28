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

Several pages at once - tabs - come from the same ViewModel. The module builds every tab's
WebView, so this is the `createBrowser` way of working rather than the `attach` one, and
`uiState`/`events` always describe the tab on screen:

```kotlin
// Once per screen; the tabs themselves live as long as the ViewModel.
val tabs = browserViewModel.tabs(requireContext(), viewLifecycleOwner)

tabs.open("https://www.dailymotion.com/")           // a tab, shown
tabs.openInBackground(link)                          // "open in a new tab"
binding.back.setOnClickListener { if (!tabs.goBack()) finish() }

viewLifecycleOwner.lifecycleScope.launch {
    launch {
        // The WebView on screen: add it to the container, and swap it when this changes.
        tabs.activeBrowser.collect { browser -> container.show(browser?.webView) }
    }
    launch {
        tabs.state.collect { strip.render(it.tabs, it.activeId) }   // label, progress, favicon
    }
}
// strip: tabs.select(id), tabs.close(id), tabs.open(), tabs.restoreLastClosed()
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
│   │                           ScriptLibrary (+ assets/media_downloader/*.js), TabFileStore
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
    └── browser/                BrowserViewModel (+ PageSession), BrowserUiState, BrowserEvent,
                                MediaBrowser, BrowserTabs (+ TabRoster), BrowserTabsUiState,
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
- [x] **7. Tabs** — several pages at once, a detection session per tab, a budget of live WebViews,
      a link's window opened as a tab *(device-verified on SM-A266B: two sites side by side, a
      `target="_blank"` tab beside its opener, back closing it, a download from a tab, undo close
      and a rotation)*

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
- **A WebView the module makes is sized `MATCH_PARENT`, not left to its content.** Wrap content -
  which is what a view with no layout parameters becomes inside a Compose `AndroidView` - makes
  Chromium resolve `vh`/`dvh`/`svh` to zero, so a page cannot size the view that sizes it. Tiktok
  lays its player out in `vh`: the video played, at the right resolution, inside a box zero pixels
  high, and the page read as broken while the button detection produced worked. Anything that
  renders blank in the browser is worth measuring a `100vh` element in before blaming detection.
- **A tab is a detection session; the screen hears only the one it is showing.** Tabs could have
  been one session told which WebView to listen to, and every rule in it - the page's memory, the
  press that is waiting, the script injected a moment ago - would then have had to carry a tab with
  it. A session per tab instead: `BrowserViewModel.newSession` makes one in a scope of its own, a
  child of the ViewModel's, so closing a tab cancels everything that tab had running and leaves the
  others alone. The screen's `uiState` and `events` are filled from whichever session is shown,
  and the sessions that are not shown are **still collected and thrown away** rather than left
  buffered: a tab that found media while it was in the background must not pop the host's sheet
  minutes later, over a page the user is reading, the moment it is selected. What it found is in
  its state all along, which is why selecting a tab shows its media at once.
- **A tab does not always keep its WebView.** A renderer is tens of megabytes and the roster holds
  up to `Browser.MAX_TABS` (16), so past `Browser.LIVE_TABS` (4, or 2 on a low-end device) the
  least recently shown tab saves its page with `WebView.saveState` and gives its WebView up;
  selecting it builds one again and restores it, and the strip says `hasLivePage = false` in the
  meantime. A tab in the background is otherwise left alone and keeps loading: the budget is about
  memory, not about stopping work the user asked for. The tab on screen is never the one given up,
  and neither is a tab in the middle of a callback of its own - destroying a WebView from inside
  its own `onCreateWindow` takes the app with it, which is what `TabRoster.overBudget(keep = …)` is
  for.
- **Every page is told there is no camera and no microphone.** Asking Chromium for the list of
  capture devices asks the phone's camera service for each camera's stream configuration, and a
  phone whose camera firmware answers that badly makes the framework throw - `AssertionError: At
  least one stream configuration for IMPLEMENTATION_DEFINED must exist`, from inside
  `android.hardware.camera2`, reached through `org.chromium.media.VideoCaptureFactory`. Chromium
  rethrows it at its JNI boundary, on its own thread, and the app the WebView lives in dies. No
  client, no `WebSettings` and no `try` on the Java side can catch that, so the only place to stop
  it is the ask itself: `no_capture.js` replaces `navigator.mediaDevices` with one that enumerates
  nothing and refuses everything, and the sites that probe for cameras (fingerprinting, mostly)
  never reach the camera service. Nothing here wanted a camera anyway - a capture permission the
  module is asked for is already refused - so what the pages lose is a probe.
  It goes into **every** page as the navigation commits, a blocked site included, because it is
  not about finding media; that is also why it is sent from `PageStarted` rather than through
  `scriptFor`. The limit of doing it without another dependency: a page whose own script runs
  before this one lands is not covered. `WebViewCompat.addDocumentStartJavaScript` would close
  that window, at the cost of `androidx.webkit` on every consumer.
- **A schema change starts the downloads table again; it is not migrated.** A migration written
  here has to be right for every version of every app that has ever published this library, for
  ever, and a migration that is wrong crashes those apps on launch with nothing their author can
  do about it - the module is not in a position to fix it for them, because the fix is a new
  release of each app. `fallbackToDestructiveMigration(dropAllTables = true)` cannot fail that
  way. The cost is real and is paid by the user: on the upgrade that changes the schema the
  downloads **list** is empty again. What is not lost is the media - a finished download was
  published to the gallery when it finished and was never the module's to delete - so what goes is
  the history, and any download still running is forgotten rather than resumed.
  Two things make that survivable. `DownloadWorker` ends cleanly when its row is gone
  (`dao.get(id) ?: return Result.success()`), so the jobs still queued for the rows that went do
  nothing rather than fail; and `restoreUnfinished` sweeps the scratch folders that no longer have
  a row, since each is a video's worth of bytes that nothing would ever come back for. A downgrade
  starts the table again too, so an older build installed over a newer one opens rather than
  refusing to.
  The rows are the user's own downloads, not a cache, so this is a reason for a schema change to
  stay rare - not a licence to change it freely.
- **A database that cannot be read ends the stream, not the app.** `observeDownloads` and
  `observeDownload` are Room flows, and a host collects them in a ViewModel's scope, where a throw
  is a dead process rather than a failed read. The database can fail a *read*: Room writes to its
  own invalidation table on every query, so a device with no space left answers `SQLiteFullException`
  to a plain `SELECT`. Both streams now end on a failure instead - what was read stays on screen,
  nothing new arrives, and the reason is in the log. A cancelled collector is still cancelled;
  `endingOnFailure` guards against failures, it is not a shield over the scope.
  Everything else that touches the database already answers with a `Result`: `attempt` turns any
  exception into one, which is why a full disk makes a pause or a delete fail rather than crash.
  What the module cannot answer for is **WorkManager's own database** - it reschedules on its own
  thread, inside its own code, and a `SQLiteFullException` there is not reachable from here.
- **A proxy the device names but cannot resolve is dropped before the client sees it.** Some
  carriers write a name rather than a host into the APN's proxy field - `Airtel `, `Jio 4G` - and
  Android hands it to every http client in the app. OkHttp 5 will not look up a hostname it cannot
  make canonical: it throws `IllegalArgumentException: unexpected hostname: Airtel `, which is not
  an `IOException`, so it is not a failed request. On an enqueued call OkHttp tells the callback of
  a failure and then rethrows from its own thread, where nothing in this module - or in the host -
  can catch it, and the app dies. `SafeProxySelector` wraps the device's own selector and leaves
  out the proxies that would be refused, so the request goes direct instead; one that genuinely
  needed that proxy still fails, as it already did, but as a failed request.
  What is dropped was measured against OkHttp 5.5.0 rather than guessed: a host is refused for
  **whitespace in it, or being empty**, while upper case, an underscore, a trailing dot and a name
  with no dot at all are merely looked up and not found. So only whitespace is dropped - dropping
  more would bypass proxies that work. `CarrierProxyCrashTest` holds both halves: the crash
  against a real `OkHttpClient`, and the same proxy behind the guard failing like any other
  request.
- **The tabs are the user's, so they are kept on disk - in a file, not the database.** A browser's
  tabs are expected back after the app is closed or killed; losing them because a process ended is
  a bug, not a lifecycle. They are written whenever the address, name or selection changes, which
  is why `keepTabs` compares what it is about to write: published state changes with every percent
  a page loads, and none of that belongs in a file. The file is small and written whole through a
  temporary name - a process killed mid-write leaves the tabs as they were rather than half of
  them - and one that cannot be read costs the tabs and nothing else. It is deliberately **not** a
  Room table: a tab lost costs a reload, a download row lost costs a file half written, and the
  downloads should never have to migrate because the browser learned a new field.
  `TabStore.saved()` answers from memory, because a screen asks for it on the main thread before
  it can know whether to open a tab of its own; `warmUp` reads the file at start, off the main
  thread, so by then there is something to answer with.
  What is kept is each tab's address, its name and which one was shown. Not its history:
  `WebView.saveState` gives a `Bundle`, and a Bundle is a parcel - the wrong thing to write to disk
  and read back after an app or OS update. Not its picture either; a card has none until its tab
  has been drawn again.
- **Closing the last tab leaves one on the home page, not nothing and not a blank.** A browser with
  no tab is a screen with nothing on it and nothing to do, and every host would have had to invent
  the same answer, so the roster never empties once it has been filled. Leaving a *blank* tab there
  was the first answer and it was the wrong one - a tab in the strip with no page in the browser
  reads as a bug, which is how it was reported - so the host names a `homeUrl` when it hosts the
  tabs and that is what the replacement opens on, the same page `open()` with no address opens.
  The closed tab still goes on the undo list, so the one that was there is a press away. Before the
  first `open` there are no tabs at all, deliberately: that is what lets the host decide when the
  browser starts rather than the module loading a page nobody asked for.
- **A WebView says when it has painted, because until it has it is a white rectangle.** A tab just
  opened has a WebView with nothing in it, and a page takes a second or two to arrive: that is the
  white screen a new tab was reported as opening on, and the same blank shows when a tab whose page
  was put away is selected again. `onPageCommitVisible` - the first frame of a page being on screen
  - is reported as `PageSignal.PageVisible` and kept in `DetectionState.isPageVisible`, so a host
  can draw its own new-tab screen over the WebView and take it away at exactly the right moment.
  It is set false when a WebView is attached and when a renderer dies, and deliberately **not** when
  the next page starts loading: a browser goes on showing the page it is leaving until the new one
  has something to put there, and a host that hid it then would blank a page that is perfectly
  visible.
- **A tab's card carries a picture of its page, taken while it still has one.** A switcher is a
  grid of pages, so a favicon is not enough; the picture is taken at the two moments the page is on
  screen and about to stop being it - as another tab is selected, and before a tab's WebView is
  given up - with `capturePreview` for the host to refresh the shown one as the switcher opens.
  The page is drawn by hand onto a software canvas, scaled to `Browser.PREVIEW_WIDTH` in `RGB_565`,
  which is about a third of a megabyte a tab rather than a megabyte and a half: a full-sized
  `ARGB_8888` picture of sixteen tabs is more memory than the renderers they are pictures of.
  Playing video comes out grey, because what a hardware surface is showing is not in the view's own
  drawing - the same gap every browser's switcher has. Drawing a page again in software is slow
  enough to be felt, so it is only done where a wait is expected: `capturePreview` takes every live
  tab's picture as the switcher opens, and a tab's last picture is taken as its page is given up.
  Taking one on the way past - on every switch - put a visible stutter on a tab switch and bought
  nothing: the pictures are only ever looked at in the switcher.
- **Every tab's WebView is laid out, on screen or not.** Only the shown tab is in the host's
  layout, so nothing measures the others, and a WebView of no size makes Chromium resolve `vh` to
  zero - the bug already recorded for a wrap-content WebView, arriving by another door. Each is
  measured and laid out to the size the page on screen last had (the display's size until a tab has
  been shown), which also gives a tab opened in the background a sensible page and a picture for its
  card before it has ever been looked at.
- **The rules about tabs are a class with no WebView in it.** `TabRoster` holds the order, the
  selection, who opened whom and whose page goes next; `BrowserTabs` holds the Android half and
  asks it. That is the only way any of it is tested on the JVM, and the rules are the part worth
  testing: a tab opened by a link sits beside its opener and closing it goes back to the opener,
  not to a neighbour the user never chose; a tab opened in the background counts as just used,
  because it is loading and is the last page worth throwing away.
- **A link that asks for a window becomes a tab, and only on a tap.** A tab's WebView is the only
  one given `setSupportMultipleWindows(true)`: without it `target="_blank"` quietly loads in the
  same page, with it `onCreateWindow` is called and a tab is opened for it. The WebView has to be
  handed over before anything is loaded in it, so the tab is opened with no address at all and the
  page puts one there a moment later - which is why a new tab's strip entry is nameless for an
  instant. `javaScriptCanOpenWindowsAutomatically` is deliberately left off and the gesture is
  checked: a page opening windows by itself is a popup, and on these sites there are many.
- **A dead renderer rebuilds only the tab on screen.** Every WebView in the app shares one
  renderer, so when the system kills it each live tab hears `RendererGone` at once; rebuilding them
  all would be the worst answer on a device that has just run out of memory. The shown tab is given
  a new WebView and the address it was on, and the others keep theirs and come back when they are
  selected. The host still gets `BrowserCrashed` to say so, but with tabs it has nothing to do
  about it. The crash is answered once per tab: the state says `isRendererGone` until a page lands,
  so without that latch a rebuild would be answered by the state of the browser it just replaced,
  for ever.
- **One generic reader for the tube sites, reachable only by consent.** The old url-parser carried
  six adult scrapers, unreachable behind the block list, and they were not brought over. There is
  one now (`data/scraper/tube/`), and one is the point: the sites are too many to write a scraper
  apiece for and too alike to need it. `TubePlayerParser` recognises the players rather than the
  hosts - `flashvars` (Aylo), `html5player.setX(…)` (xVideos, XNXX), schema.org `VideoObject`,
  then Open Graph - and every shape drops advert urls, because the pre-roll is often the only file
  on the page before the video is asked for. Which links it claims is a category and a path, not a
  list: `RestrictedSites` says the host is Adult and the path names a video. Nothing changes for an
  app that leaves `allowAdultSites` off: the link is refused before any scraper is asked.
- **A press falls back to the page when a best-effort read comes up empty.** Claiming a link sends
  a press to the parser instead of to the streams the page loaded, which is right for a site whose
  page says what it plays and wrong for one that encrypts it. So `fetchParserMedia` takes a
  fallback: on a link only the tube reader claims, a failed read answers the press from the page,
  exactly as a site with no reader at all would. Without it, claiming xHamster made its button stop
  answering altogether - a reader that could not read left the page unable to try.
- **Several streams on a video's own page are the video and its advert**, not a feed: they are all
  offered and the longest stands (see the rule above). Only a page showing one media does this; a
  listing is still not guessed at. Up to `Browser.MAX_PAGE_STREAMS` are kept.
- **A parser that may come up empty does not switch the sniffer off.** Sites that encrypt their
  player data (xHamster hands out hex blobs) cannot be read from the page at all, so
  `ScraperResolver.readsBestEffort` marks the tube reader's links, and `SniffPolicy.mayTake` keeps
  watching requests on them. The parser's answer still wins when it has one.
- **The page is asked for more than once.** Networks that filter these sites reset the connection
  instead of answering, and the reset lands on roughly half the attempts: measured on a filtered
  network, one try parsed a watch page 2 times in 5 and four tries 5 times in 5. Retrying a reset
  is not retrying an error the server gave - a server that answers is never asked twice.
- **A string with no host is `Unsupported` in both modes.**
- **A WebView the module makes is sized `MATCH_PARENT`, not left to its content.** Wrap content -
  which is what a view with no layout parameters becomes inside a Compose `AndroidView` - makes
  Chromium resolve `vh`/`dvh`/`svh` to zero, so a page cannot size the view that sizes it. Tiktok
  lays its player out in `vh`: the video played, at the right resolution, inside a box zero pixels
  high, and the page read as broken while the button detection produced worked. Anything that
  renders blank in the browser is worth measuring a `100vh` element in before blaming detection.
- **One adult-site scraper, reachable only by consent.** The old url-parser carried six,
  unreachable behind the block list, and they were not brought over. The PornHub network has one
  now (`data/scraper/pornhub/`) because an app that turns `allowAdultSites` on had no parser at
  all there and fell through to the browser, which offered it the pre-roll. Nothing changes for an
  app that leaves the switch off: `CheckSiteAccessUseCase` refuses the link before any scraper is
  asked. The page's own player object is read rather than its markup or its media requests, which
  is also what keeps the advert out - the advert is not in that object. Its hosts are in
  `PARSER_SITE_HOSTS`, so the browser reads the page instead of sniffing it.
- **The page is asked for more than once.** Networks that filter that site reset the connection
  instead of answering, and the reset lands on roughly half the attempts: measured on a filtered
  network, one try parsed 2 times in 5 and four tries 5 times in 5. Retrying a reset is not
  retrying an error the server gave - a server that answers is never asked twice.
- **The tweeload key is configuration**, not source (`MediaDownloaderConfig.twitterApiKey`).
  Without it X links are `LinkNotRecognised`. The app reads it from `local.properties`
  (`tweeload.apiKey`, gitignored) into `BuildConfig.TWEELOAD_API_KEY`; the value is the one the
  old url-parser had hard-coded, so a build machine needs that line to parse X links. Instagram's and Pinterest's anonymous
  browser-session cookies stay in `Constants.kt`: they identify no account. An Instagram session,
  which does identify one, is configuration too (`instagramCookie`) and is never a constant here.
- **A post is read as a stranger first and as the reader second.** Instagram has always been read
  with the browser's own cookies; Facebook, TikTok and IMDb can be too. A Facebook post shared with
  friends, or one inside a group the reader belongs to, and a TikTok video a private account left up
  for its followers, are invisible to a request carrying no session - which is what those scrapers
  used to send, so the press came back with nothing and the reader was told the media could not be
  found. Signed out there are no cookies to send and every request goes exactly as it did before.
  **The order matters and was measured.** Facebook serves a public page to a stranger in the shape
  these parsers know (`browser_native_hd_url` and its SD twin) and serves the same page to a session
  as the signed-in app, which names no file at all: asking as the reader first cost a signed-in
  reader the parse outright - the same reel gave HD 10.0 MB and SD 2.3 MB read as a stranger and
  nothing read as the reader, so the press fell back to the one stream the player had been heard
  fetching. The session is therefore the second question, asked only where there is one and the
  first came back empty - and TikTok ends up the same shape for its own reason, below.
  Two things this does not fix: a post Facebook itself will not show ("this content isn't available
  at the moment") has nothing behind it whatever session asks, and a link pasted into an app that
  has never browsed the site has no session to borrow.
- **Instagram is the exception to stranger-first: it tells a stranger nothing, so every reader
  there asks with the session.** Measured on a live public reel
  (`instagram.com/reel/DQN9eI6CF55`, October 2026): the `/reels/` page answers `200` with 500 kB
  that hold the title, the cover and `PolarisLoggedOut` 44 times, and **no** `video_versions`,
  `image_versions2` or `web_info`; the embed page answers `200` with `"contextJSON":null` and no
  `video_url`; the api answers `403` to the canned cookies and `401 {"require_login":true}` to a
  perfect request with a freshly issued csrf token. The post is public, and its video is readable by
  nobody who is not signed in. So the session is no longer the second question on Instagram - the
  page reader, the embed reader and the api all ask with it, and a `/p/` post, whose only reader is
  the api, became readable again for a signed-in user, having had no working reader at all. Three
  things that cost nothing and are what Instagram's own web client sends were missing from the api
  request and are now there: `X-IG-App-ID` (without it the api answers `403` however good the rest
  is), the csrf token echoed back out of the cookie it came in, and the session.
- **A session the host supplied stands in only where there is no session at all, and the module
  ships none.** `MediaDownloaderConfig.instagramCookie` is read by one `FallbackCookieSource` that
  all four Instagram readers share: the browser's own cookies win whenever they carry a
  `sessionid`, and the supplied one is given only to `instagram.com` urls - it is one site's
  session and means nothing to another, and Threads' session is not Instagram's though one account
  opens both. It is deliberately not a constant in this repository: a session is enough to act as
  the account it came from, this repository is public, an APK is readable, Instagram locks an
  account it sees used from everywhere at once, and the session expires on its own schedule. The
  host decides, puts it in `local.properties` like the X key, and carries the consequence; the
  module's answer to a signed-out user is to say so.
- **The third-party resolver was dead and is rewritten; it is now the only thing that reads an
  Instagram post signed out.** `GetInDeviceScraper` POSTed to
  `www.getindevice.com/wp-json/aio-dl/video-data` - a WordPress REST route, `wp-json` being
  WordPress's own namespace and `aio-dl` a downloader plugin's. That site is a Next.js app now
  (`x-nextjs-cache`, behind Cloudflare) and WordPress went with the rebuild: `www.` answers `301`
  to the apex, the route answers `404` with 35 kB of html, and `/wp-json/` **itself** is `404`, so
  it was the whole api that left rather than one path. The failure was invisible - html where json
  was expected throws in the decoder, which is a failed scraper, which is what a link with no media
  looks like - and it had been that way for an unknown time, costing a wasted request on Facebook
  video, Facebook share, TikTok and Instagram reels, which are the links it was the safety net for.
  The api it answers on now takes **two requests**: `GET /api/token/` for a token, then
  `POST /api/download/` with it in `x-request-token`, because without one the api says
  `401 {"error":"Missing request token"}`. Both urls need the trailing slash and the bare host: a
  `301` or `308` on a POST does not reach the api as a POST. Verified live in October 2026 against
  the reel this started with, signed out: 1080p, 0.48 MB, in 2.0s, and the url it names serves
  `206 video/mp4` to us directly.
- **That api is read field by field, not deserialized.** It is somebody else's private api with no
  documentation, no promises and a record of being rebuilt underneath us, and its own answers are
  loosely typed - `size` and `duration` arrive as `null` here and as something unknown elsewhere,
  `is_hls` as a boolean, `quality` as a frame size (`1080x1920`) for a video and a word (`HD`) for
  a photo. So every field is read through `text(key)`, which takes a string, a number or `null`
  alike: a shape that changes again costs this reader its answer and nothing else. Three rules
  worth naming: a video labelled by its frame size is named by its **short** side, so `1080x1920`
  and `1920x1080` are both `1080p`; `audios` is offered only where there is nothing to watch or
  look at, because on a reel the api lists the same progressive file again as its sound and that is
  not a second quality; and a video whose sound is genuinely a file of its own (`mergeParts`) is
  given that sound as its `audioUrl`, unless it is the same url again.
- **The resolver's answer is refused when it is the site's own page rather than the post.**
  Facebook does not answer a video id it has never had with a `404`: it answers with the Watch
  feed, and the resolver reads whatever video that feed happens to be showing and hands it over as
  the link's. Measured on every malformed shape - `watch/?v=999999999999999`, `/reel/999…`,
  `fb.watch/zzzzzzzzzz/` - each came back as a real, playable video (`206 video/mp4` to us) titled
  exactly `Facebook`; Threads does the same with its sign-up page, answering a post that does not
  exist with the picture off it, titled `Join Threads to share ideas…`. That is the group
  cover-photo bug again - somebody else's media offered as this link's - and a wrong video is worse
  than no video, so an answer whose whole title is the site's own name or its sign-up line is
  refused ([GetInDevice.SHELL_NAMES]), as is one that opens with the site's own name and then says
  what the page is for - `LinkedIn Login, Sign in`, which is what LinkedIn answers for a link that
  is not a post. It is one string and a site writing it in another language gets past, and a post
  of one's own titled nothing but `Facebook` is refused along with it; that is the side to err on,
  since the site's own reader is racing this one. TikTok, Pinterest and X answer an id they do not
  have with an error and are untouched by any of this; Instagram does not, which is the next entry.
- **An Instagram reel the resolver cannot resolve is answered with the account's own picture, and
  the title guard cannot see it.** Measured on two made-up reel codes (`/reel/ZZZZZZZZZZZ`,
  `/reel/QQQQQQQQQQQ`): both came back `200` with no video, no author, no title - and the **same**
  real, downloadable jpg, on Instagram's profile-picture namespace `t51.75761-19`. The guard above
  is no use here because an Instagram answer carries no title at all, so nothing in the shape says
  this is not the reel; the module would have offered a stranger's profile picture as the link's
  media. A picture on a `-19` namespace is therefore dropped before anything else is read
  ([GetInDevice.PROFILE_PICTURE_PATH]), which leaves no quality at all and so reads as the nothing
  it is. It costs a real post nothing: a reel the resolver can read draws its video from `/t2/` and
  its cover from `-15`, and a `/p/` code the service does not have is answered with an error
  instead of a picture.
- **A post the resolver says is gone is not blamed on signing in.** Every reader of ours is locked
  out of a signed-out Instagram and so cannot tell a deleted post from a hidden one, which made the
  module ask for a sign-in in both cases - advice that cannot help when there is no post. The
  resolver can tell, because it reads a site as an account that is signed in: when **it** cannot
  find a post, a session was not what was missing. Its refusal is therefore raised as
  [MediaGoneException] rather than swallowed as nothing ([GetInDevice.GONE_WORDS]), and the
  Instagram reader then refuses with the ordinary nothing-found instead of
  [SignInRequiredException]. Found on a live reel the owner had deleted
  (`instagram.com/reel/DUnGTG7CDDv`, confirmed signed in): every reader of ours said nothing and the
  parse advised signing in, which would have shown the user the same empty page. Only the refusal
  that names the post is read this way - its `Request timed out. The server took too long to
  respond.` says nothing about the post and stays the plain nothing-found. The two come back about
  equally often for the same link (four probes: two of each, 33.4s and 33.6s against 35.8s twice),
  so a deleted post still advises signing in roughly half the time; that is the safe half, since
  without a session the module genuinely cannot tell.
- **The signed-in reader is preferred, not waited for without end.** It read the post as the user
  sees it, so it decided the answer whenever it answered - and until it did, nothing else counted,
  because its answer was awaited before the others were even looked at. The cost was measured on a
  live `/p/` post: **13.9s**, where every other reader had answered inside two seconds and the
  answer taken in the end was one of theirs. It is now ranked rather than awaited: it wins whenever
  it answers, and once another reader has answered it is given
  [Scraping.SIGNED_IN_GRACE_MS] to answer before that other answer is taken. A private post is
  still its alone - with every other reader empty, nothing is taken until it is done, however long
  that is, which is the case worth being slow for. Same three links after the change: `/p/` 2.4s
  and 2.1s, the reel 1.4s and 1.1s.
- **No branch of that `select` may finish with `null` except the one that means "everybody is
  done".** The branches are a race of waits, and `select` returns whichever finishes first: a
  branch that finishes with nothing ends the race and takes every reader still working down with
  it. The resolver's branch did that - it completed the moment the resolver *failed*, so a link the
  resolver could not read ended the parse before the user's own session had answered. Branches that
  have nothing to say now wait for ever and are canceled at the end. The same reasoning put a
  second condition on the resolver's grace: a reader of ours that has answered and is merely being
  held for the signed-in grace must not lose to it.
- **The resolver is a backup everywhere it is used, never a peer, and the racer is what enforces
  it.** It was a peer: in `ScraperResolver` it sat in the same list as the Facebook and TikTok
  readers, and `ScraperRacer` takes the first success - so on a good link it could win by being
  quicker and hand over a worse answer (no title, and on Facebook a feed video where ours would
  have failed honestly). `firstSuccess` now takes a `backups` list: those readers start with the
  rest, so they cost no waiting, and their answer is held until every other reader has come back
  empty. **With one bound**: once a backup answer is in hand the others get
  [Scraping.BACKUP_GRACE_MS] and are then no longer waited for, because a site that stalls a
  request holds it for [Network.REQUEST_TIMEOUT_MS] - measured, by accident, on the first build of
  this: Instagram stalled one reader and a reel that parses in 2.7s took **60s** and then failed.
  A failure reports **our** reader's error rather than the backup's, which is what carries
  `needsSignIn`. `InstagramScraper` does the same thing in its own select, after its artwork grace.
- **The backup is offered only where it was measured, and never for an adult site.** Facebook,
  TikTok, X, LinkedIn and Pinterest in `backupsFor`, plus Instagram and Threads inside their own
  reader. Not Dailymotion or IMDb: it cannot read them (`Unable to process this URL`). Not the tube
  reader: an adult link is not named to a third party, whatever the host app allowed. Not a
  host-supplied site: the app's own reader is the authority on it. And `backupsFor` is deliberately
  **not** part of `scrapersFor`, which the browser asks whether a parser reads a page - the browser
  must keep sniffing a page whose only reader is somebody else's service.
- **A link with no reader of ours may still have the backup.** `scrapersFor` empty used to mean
  `LinkNotRecognised` outright; an X link in an app that set no tweeload key is exactly that, and
  the backup reads one. The parse now refuses only when both are empty.
- **A `/p/` post now has the resolver behind GraphQL.** A `/p/` post is not a reel: the post page
  and the embed page are reel readers and have nothing in them for it, which left it one reader -
  and none at all once Instagram signed the public out of GraphQL. The same live check reads
  `instagram.com/p/<code>` and a Threads photograph signed out.
- **"Nothing found" and "nothing to find" are different failures.** When every Instagram reader
  comes back empty *and* there is no session, `SignInRequiredException` is thrown instead of the
  ordinary nothing-found, and the parse hands the host
  `MediaParseException.MediaNotFound(needsSignIn = true)`. The link is not empty and the user is
  not stuck: signing in to the site in the browser is what fixes it, and only the host can say so.
  With a session the same empty answer stays an ordinary not-found - it is then this post that
  could not be read.
- **A share link is read from the page it lands on**, before its ids are taken apart. A group's post
  lives at `/groups/<group>/posts/<story>/`, which is not the `/<author>/posts/<story>` the ids
  build, so rebuilding the address asked Facebook for a page that does not exist - signed in, with
  the post open and readable in the browser beside it, the link still came back as "couldn't find
  any media behind that link". The share link simply leads to the post, so whatever the fetch lands
  on is read first and the rebuilt address is the fallback.
- **A group's cover photo is not the group post's picture.** Facebook shows a group's post to nobody
  outside the group and serves the group's own card instead - and that card's `og:image` is the
  group's cover, which the photo reader took for the post's picture. Measured on a share link: the
  address resolved to `/groups/2943938342600974/posts/4463780570616736/` and offered
  `278395917_518487713186254_n.jpg`, byte for byte the file the group's front page carries, while
  the post on screen in the browser held `780981845_122124632054918752_n.jpg`. The reader was handed
  a photograph that was nowhere in the post, which is worse than being told nothing was found. Where
  a page belongs to a group, the group's front page is now asked what its cover is and a match is
  refused; the link fails honestly and the browser, which reads the post as it is drawn, still gets
  the right picture. A post outside a group costs no extra request, and a group post that does name
  its own picture is read as before. There is nothing better to be had from a link alone: signed in,
  the same page comes back as the app shell naming no file, and mbasic - the one html face that used
  to answer - now replies "Facebook is not available on this browser".
- **A Threads share link names no post, so the post is read out of the page it serves.** Threads'
  own share sheet hands out `threads.com/share/<code>`, where the code is the share's and not the
  post's. Every Instagram-and-Threads reader builds its request from the post's address, so a shared
  link matched no rule at all and was turned away as a link that does not point at media - on a site
  the library lists as supported, which is how it was reported. The share page is a redirect to the
  post and names it in `og:url`, so that one request is made first and every reader is given the
  address it needs. A post's own link costs nothing extra: it is passed straight on.
- **A Threads post may be a photograph, and nothing read one.** The preview reader wanted
  `video_versions` and gave up without it, while the page says plainly what it is -
  `"video_versions":null,"media_type":1` - and carries the picture as the first `image_versions2`
  candidate, the same file its og:image points at. A photograph was therefore "no media found".
  It now falls back to the signed-in page reader, which already reads a picture, **only for a
  Threads post**: on an Instagram reel those candidates are the video's cover, and a cover offered
  as the media would be a worse answer than none.
- **A picture is its own artwork.** Only the generic script hands a thumbnail over beside the media;
  the site scripts send the file alone, so a photograph pressed on Facebook opened the sheet with an
  empty grey square over a picture whose address it already had. An image arriving with no artwork
  of its own is now shown as itself.
- **A picture with no words is named after who posted it.** Facebook's photo viewer keeps the post's
  text out of reach of the image - every box around it is exactly the picture's height - and a group
  photo may carry no text at all, so the sheet opened as "Facebook", the page's own title, which is
  also what the file was saved under. The first line or two of the first box above it that says
  anything is taken instead, never the reaction and comment counts, which is all that sits beside a
  viewer.
- **TikTok is asked of the api first and of its own page second.** tikwm answers a public video
  best - several files, each with its size, fetchable by anyone - but it is a stranger to the
  reader's account, so `TikTokPageScraper` reads the site itself when the api has nothing. The page
  carries the files in a `<script id="api-data">` block. They are handed over **with** the session
  that was given them: measured against the site, the play url answers `403` to a request with no
  cookies however it is dressed up, and `206` with the cookies, a browser's name and the site as
  where it came from - so all three travel with the media rather than being left behind at the page.
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
  - The module has its own Room database (`media_downloader.db`). A schema change **starts the
    table again** rather than migrating it - see the decision in §6 - and the scratch files of the
    rows that go are swept at the next start.
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
- **A stream the page preloads is the video.** `<link rel="preload" as="fetch" href="…m3u8">` is
  what a page asks the browser to start fetching before its player exists, and the advert before
  the video belongs to another network and is never preloaded. It is read last of the page shapes
  and is what finally made xHamster readable from a pasted link - a site that encrypts everything
  its player is handed still says in its head what it is about to play. (The idea came from the
  `url-parser` module in the ViolationFix copy of `newdownloader`, whose `xhamster_desi` reader
  does nothing else.)
- **A player is restarted on a press only where there is more than one.** `load()` detaches a
  MediaSource and a site's player does not always put it back: on xHamster the element was left
  with no source at all ("The element has no supported sources"), so the video after the pre-roll
  was never fetched and the press kept its answer of thirty seconds of advert. Restarting exists
  to tell several players apart, so a page with one is left alone.
- **An answer as short as an advert does not stand the sniffer down.** The press used to mark the
  page answered, and nothing was listened to afterwards; now a pre-roll-shaped answer leaves the
  page being listened to, so the video can take its place when it plays.
- **An AV1 stream is offered as its H.264 twin.** An mp4 written by MediaMuxer holds H.264 and AAC
  and nothing else, and a player asks for AV1 wherever the device can decode it - so xHamster's
  video downloaded in full and then failed to join. Where a site keeps both codecs at one address
  (`_TPL_.av1.mp4.m3u8` beside `_TPL_.h264.mp4.m3u8`), `StreamLocator.h264TwinOf` reads the twin
  and that is what is offered; a stream with no twin is offered as it is.
- **The longer stream wins.** Once something with a known running time is on offer, a later find
  is described before it is shown and is left where it is when it turns out to be a fraction as
  long (`Browser.PREROLL_MAX_MS`, `PREROLL_SHARE_OF_VIDEO`). That is the shape of a pre-roll seen
  from here: thirty seconds before a video that runs for minutes. It is the only rule that catches
  an advert served from a throwaway host - one domain per campaign, which no list can hold - and it
  works in both directions, so an advert heard after the video does not take its place either.
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
- **IMDb's video pages are read, not listened to.** A press there used to be answered from
  whatever the player had been heard asking for, which is nothing at all when the file is already
  in the cache: the reader pressed, the disc spun and the sheet never opened - and when it was
  heard, it was the one height the player had chosen (a sniffed "HD" of 29.7 MB where the page
  offers 93.1 MB). `ImdbScraper` reads the page instead, where the player's own list names every
  height, and the linked data beside it names the video, pictures it and says how long it runs.
  Two things are worth knowing: the site answers a request carrying no session with `202` and an
  empty page, so the browser's own cookies go with it - which makes this a reader for a page the
  reader has been to, and a link pasted on a device that has never opened IMDb is still answered
  with nothing; and the files themselves are signed cdn urls that ask for nothing, so a download
  outlives the session it was read with. Reading the page also makes `isParserLink` true for those
  pages, so a press anywhere - a listing's poster, the strip under a video, the trailer rails on
  the home page - is answered by the parser rather than by what was overheard.
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
- **A page can be one post's own and still list other posts under it.** Linkedin ends a post's
  page with a "related posts" section - eleven more articles, each with a video or a picture of
  its own - and the page's url answers with the post at the top, so every button down there handed
  that one over: a press on one of those videos offered a 56 kB picture from a different post
  altogether. A press now looks for the post the pressed media belongs to first: the nearest thing
  above it naming exactly one post (`window.mksPostPath` again) is that post's own page, while a
  node naming several is the list around it and the walk stops there. Found, it is also treated
  like a card on a feed - the poster on screen is not the download, since that post's own page has
  the picture itself rather than linkedin's `feedshare-shrink_800` copy, and neither the page's
  `og:title` nor its `og:image` describes it. Not found - which is every page holding only what
  its url names - nothing changes. Verified on the device against both: a related post's video
  offered its own 720p/640p, and the page's own video still offered the page's.
- **A poster standing for a video on another page** is handed over as that page, even on a page
  read as holding one media: imdb's film page shows the trailer's slate, whose link is the page
  that has it. A video hidden again (imdb keeps its trailer's `<video>` behind the poster with
  `visibility: hidden`) gives its button up, so the poster can take it; a poster only stands aside
  for a video that has a button of its own. Imdb's few-second autoplay preview
  (`hls-preview-….m3u8`) is not offered as the film.
- **A card link with no box of its own stands where its container does.** On a site the parser
  reads, a picture only gets a button when the link beside it is that picture's own - it either
  contains the picture or is laid over it, which is how imdb hangs a video's page on its slate, and
  the overlap is measured. TikTok's grids (discover, search, a profile) keep each cover's
  `<a href="/@who/video/<id>">` inside the card as an **empty** element: no children, `display:
  inline`, measured 0 by 0. Nothing can overlap a box of no size, so every card on those pages lost
  its button even though the link names exactly the one video. The nearest thing above such a link
  that has a box now answers for it, which on TikTok is the cover itself; a link belonging to the
  section around a picture still fails the height test that follows, so imdb's home is unaffected.
  Measured after: discover draws a button per cover again and a press opens the pressed video - SD
  5.6 MB, HD 6.7 MB, watermarked 10.2 MB for the first card - and a profile grid draws thirteen.
- **"display" is not "play".** A picture is judged a video's cover partly by what is drawn over it:
  a play badge or a running time, found by class name. That was a substring test, and Pexels hangs
  an "Edit in Canva" button over every photograph carrying `DisplayNone_mobile-tablet` - which
  contains the word. Every photo on the site was therefore taken for a video card: its picture was
  never offered, the press went looking for a video that does not exist, and the sheet opened as
  "HD, size unknown". The word is now looked for again with "display" taken out of the way, which
  leaves `player`, `play-button` and `playlist` counting as they did; measured on the device
  afterwards, a photo gives its own 399.7 kB and a video card still gives its 3.0 MB rather than a
  thumbnail. The class is read with `getAttribute`, since an svg's `className` is an object.
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
- **A sound that is gone and a sound that was interrupted are different answers.** A video whose
  sound is a second file wrapped the sound's whole fetch in `runCatching`, which reads every
  outcome as one: a connection broken mid-sound was taken for sound that will never come, so the
  silent picture was published and the download called complete, and nothing ever asked for the
  sound again. Measured on the device, serving the two tracks from a socket on the phone's own
  loopback that hands the picture over whole and then cuts the sound off mid-body: before,
  `Completed, 262144 bytes, error=null` with the sound asked for exactly once; after, `Queued,
  error=unexpected end of stream` and four rounds of retries in the server's own log. The stream
  downloader already told the two apart, so its rule is now shared by both
  (`unlessMediaIsGone`): only a 4xx that means gone - 408 and 429 excepted, as `isMediaGone`
  says - or media this module cannot save leaves the picture as the file. `runCatching` also
  caught `CancellationException`, against the module's own rule. On the device that was latent
  rather than live: `markFinishing` runs right after and is a suspend call, so it threw on the
  cancelled coroutine before the picture could be moved out of the work dir, and a paused download
  kept its picture before the fix too (measured: `video.track=2059987` either way). It is the next
  thing that suspends that was saving it, which is not a thing to rely on.
- **The two tracks of one download need a scratch dir each.** Both are fetched in parallel parts,
  and the parts of one are named exactly like the parts of the other - `part-0`, beside one record
  of the length they were cut for. Sharing a dir, the picture's own "already whole" test deleted
  whatever the sound had fetched on every single run, so the sound began again from nothing however
  often the download was retried; on a connection that cannot hold for a whole track it could never
  finish inside the attempts it is given. Isolated with the cancellation fix already in place, so
  it stands on its own: the sound's finished 2 MB first part was asked for again from byte zero
  (`audio.m4a bytes=0-2097151`) while the picture cost only its `bytes=0-0` probe. Both tracks now
  build under `video/` and `audio/`, as a stream's tracks already did. Needs both tracks over 4 MB
  to bite, which is why it is proved on the JVM: measured on the device, the biggest plain file any
  reader answers with today is TikTok's 3.4 MB watermarked copy.
- **A lookup that can answer null is never read straight off in a page script.** `twitter.js` ends
  with the fallback for a video no article or image button was drawn for, and it read two:
  `videos[i].closest('[data-testid="videoComponent"]')`, then that one's immersive controls. A
  video in an ordinary timeline sits in no `videoComponent`, and one that does has play controls
  rather than immersive ones, so the first such video threw a TypeError that nothing caught - and
  an injected script that throws outside a handler stops there, taking the rest of the sweep and
  every video behind it with it. Run in the device's own Chromium over a page holding one timeline
  video and one immersive one: before, `threw TypeError: Cannot read properties of null (reading
  'querySelector')` and no buttons at all; after, the sweep runs to its last line and draws the one
  button the immersive video has earned. The legacy Facebook video's `data-store` is guarded the
  same way, though that one is inside the press handler and cost the press alone.
- **The bridge contract is checkable, and was checked.** 31 `MediaDownloaderBridge` calls across
  the seven scripts, every one matching a `PageScriptBridge` method by name **and** by argument
  count, with nothing dead on either side - no method no script calls, no call no method answers.
  Every other DOM lookup in the scripts that can answer null is guarded by the loop that walks it
  (`for(... && node; ...)`), which is why only `twitter.js` came out of the sweep.
- **Incognito is a WebView profile, and the profile cannot be thrown away.** An incognito page
  browses in one profile shared by every incognito page in the process, reached through
  `androidx.webkit`'s `ProfileStore`. It is **emptied** rather than deleted because
  `deleteProfile` refuses a profile that has been loaded in this process at all, and reaching its
  cookie jar is what loads it - so cookies and web storage are cleared when the last incognito page
  closes, which is the promise anyway. Who wants it is counted, the way a download's lock counts its
  runs: a tab holds its claim across losing and regaining its WebView to the live-page budget, and
  emptying on the first close would wipe the session of every other incognito tab still on screen.
  Measured on the device: a second incognito tab held the jar while the first closed, and a fresh
  incognito page after both closed saw nothing.
- **Whether incognito is private at all is the WebView's answer, not the SDK level's.** A second
  profile needs `WebViewFeature.MULTI_PROFILE`, which comes with the WebView, so an old or stripped
  WebView answers `SharedJar` on a new phone. The module says which (`MediaDownloader.incognitoPrivacy`)
  instead of calling a shared jar private; an incognito page is still kept out of the saved tabs and
  its session is still never flushed either way, because its `CookieSource.flush` does nothing -
  which is also what stops the browser's Instagram sign-in being written down for it.
  `WebViewCompat.setProfile` has to be the first thing done to a WebView (the platform refuses one
  that has navigated, run a script, or been given a profile already), so it is the first statement
  in `MediaBrowser.configure`, and a host's own already-used WebView keeps the ordinary profile and
  is told so. Measured on the device: `Separate`, with a cookie set in an incognito page invisible
  to an ordinary one and the other way round, and no incognito tab in `tabs.json`.
- **The configuration is read live where it can be, and fixed where it cannot.** `reconfigure`
  replaces a `@Volatile` config on the component; the collaborators that can honour a change ask it
  each time (`CheckSiteAccessUseCase` reads `SiteAccessSettings` through a lambda and re-normalises
  its host sets only when they change, `DownloadStorage` its folder name, `DownloadNotifier` its
  icon and activity, `FallbackCookieSource` its supplied cookie). Four settings are read once by
  something already in use and are **named** in the answer rather than silently dropped -
  `performanceMode` (parallelism captured when each downloader was built), `extraSources` (the
  scraper graph and the supported-host set), `guardCarrierProxies` (OkHttp copies the selector when
  a client is built - measured last round), `twitterApiKey` (no key means no X reader to hand a link
  to). One use case instance lives for the process, so a lifted block reaches the browser screens
  and the downloads already in flight; measured on the device through the public api.

## 7. Known gaps

- Instagram and Facebook scrapers are verified by unit tests on sample pages, not yet live: they
  need a signed-in session or real post links. **What was measured live in October 2026** is what
  Instagram refuses a stranger (above) and what the third-party resolver answers for it anyway -
  a reel, the same post as `/p/`, and a Threads photograph, all signed out, all through
  `LiveParseCheck`. What is **not** measured is what Instagram gives a session: the session is
  sent and the headers are asserted in `InstagramSessionTest`, but nothing here has yet read a reel
  with a real `sessionid`. `MEDIA_DOWNLOADER_LIVE=1` with a signed-in cookie in `instagramCookie`,
  or a device signed in to Instagram in the browser, is the check still owing.
- **An Instagram post read through the resolver has no title.** The api answers `title: null` for
  Instagram (it carries one for Threads), and the readers that do have the caption - the post page's
  og tags - found no media and so handed over nothing to carry it on. The file is still named
  sensibly, because a blank title falls back to `Instagram_<millis>` when the place is picked, but a
  list shows the post with no words. Filling it would cost the caption's own request or plumbing a
  title-only answer out of the preview reader; neither is done. IMDb was read live on the device in September 2026,
  from a video page, the trailers feed and the home page's rails. LinkedIn has been read live, through a `lnkd.in`
  link, since the shortener was added.
- **A `lnkd.in` link is read by following it, not by resolving it.** The address says nothing about
  what is behind it, so `isLinkedInPostLink` claims the whole host and the request (which follows
  redirects) lands on the page it was written for. LinkedIn wraps *every* outside link a post
  mentions in one of these, so the page is then asked for its own address (`og:url`): a page that
  is not a post is left alone, because its cover picture would otherwise be offered as the post's
  media. A short link that leads to a site the module could read on its own - a YouTube video, say
  - still fails, since nothing re-enters the pipeline with the resolved address; that would have to
  re-run the site check too.
- **At sixteen tabs a link that asks for a window does nothing.** `open` answers `null` and the
  strip can say so in advance (`canOpenMore`), but a page's own `target="_blank"` has nowhere to go
  and is simply refused - it does not fall back to loading in the tab it was tapped in, which would
  mean handing the page a throwaway WebView only to read the address out of it.
- **A restored tab is its history, not its scroll.** `WebView.saveState` carries the back/forward
  list and nothing else: a tab that gave its WebView up and is selected again loads its page afresh,
  so a feed scrolled a long way comes back at the top, and a form half filled in comes back empty.
  Only tabs past the live budget are restored this way; the tabs in use are never touched.
- **A tab kept between runs comes back as an address, not as where it was.** The page is reloaded
  from the top: its back/forward history, its scroll position and anything typed into it are gone,
  because the only state a WebView hands over is a `Bundle` that cannot be trusted to disk. A tab
  closed before the app was is not kept either - undo reaches back only within a run.
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
  the key); its page script is not. Signed *out*, the Facebook script was measured on the reels
  feed, a reel's own page and a watch page in September 2026.
- **A button per rule, not per video.** `facebook.js` draws from several rules in turn, and two of
  them hung a second button on a video that already had one: on `m.facebook.com/reel/<id>` (and
  `/watch/?v=<id>`) the address carries an id, so the permalink rule drew at a third of the card's
  height over the MVideo rule's button at 0.6 of it - the two red discs a reader sees stacked. It
  looked only at the video's own parent, which is below the container the earlier rules hang their
  button on. All three rules that can draw on a video - the permalink one, the `[role="article"]`
  one and the plain `<video>` one - now ask the card that owns it, its
  `MVideo`/`ServerMVideo`/`inlineVideo` container, and stand aside if that card already carries a
  button. The third was found signed in, where the page keeps its player somewhere else again and
  two discs came back over the one video.
  The card is asked rather than the whole post, so a post holding a picture beside its video keeps
  the picture's own button.
- **The reels tray's button opens the reel, and that cannot be helped.** On the signed-out video
  surface `m.facebook.com/watch` shows a row of covers, and each gets a button. A cover knows
  nothing - no player, no id, no link, because Facebook resolves the tap on its own server - so the
  button presses the tile and leaves a note in `sessionStorage`, and the script on the page that
  opens reads the note and hands the reel over by the id in the new address. Measured signed out on
  the device: one press, the right reel, and a sheet with its title, its artwork and HD 8.8 MB
  beside SD 2.4 MB.
  It was taken out once, in October 2026, on a report that the sheet came up empty; pressing the
  same button in the owner's own app the same day gave HD 3.57 MB, so the sheet was not the
  problem - the buttons drawn over Facebook's own dialog were, and those are handled above.
  The reel opening is what the owner minds, and going back after the handover was tried and does
  not work: the module cancels the search and forgets the media on every committed navigation
  (`MediaDetectionSession.onPageCommitted`), so the sheet the press was for dies with the page it
  was found on - measured, back 400ms after the handover and no sheet appeared at all. The reel's
  address is also the only place the id is ever written, so there is nowhere earlier to read it.
  Two things the shape forced: the button is drawn against the viewport from `document.body`,
  because the strip slides under a `transform` (which a `position: fixed` child is measured from)
  and sits in a box stacking below the fixed layer Facebook keeps its "Open Facebook"
  banner in - inside the tile it was visible and took no presses at all; and the handover waits
  1.2s, because the script arrives with the new page's first answer, while the app is still letting
  go of the page the press was made on, and a reel handed over then reached nothing. Drawn only on
  `/watch`, `/reel` and `/videos/`, so the stories tray on the feed - built exactly the same way -
  is still left alone. Such a button hangs off the body rather than its tile, so it carries
  `mksTileOf`: without it the sweep above would ask the body whether it is covered, and these are
  precisely the buttons that were drawn over the dialog.
  Stacking cannot keep such a button under Facebook's own "Get the full experience" dialog, and
  trying was a wrong turn. The dialog is `position: fixed` at `z-index: 1`, but it sits inside a box
  that stacks at zero earlier in the body than this button does, so a button low enough to go under
  it goes under the covers too and is never seen - measured, at `z-index: 0` the discs were still
  drawn over the dialog. It is back above everything, and what keeps it off a dialog is being told
  not to appear there.
- **A permit is taken once per quality, not once per file.** Sizing a quality took one and then
  asked for a second from inside the first, to size the sound that is a file of its own, and
  `kotlinx`'s `Semaphore` is not re-entrant: with as many such qualities as there are permits -
  four, or two on a low-end device - every permit was held by a coroutine waiting for one nobody
  could release. `awaitAll` never returned, the sheet span for ever with `isDescribingMedia` true,
  and a coroutine was leaked per offer. A video above 360p keeps its sound apart, so this was the
  ordinary shape of an answer rather than an odd one. Reproduced before fixing, and the test keeps
  it reproduced: four such qualities against two permits hang until a 30s timeout.
- **Reading the downloads does not initialise WorkManager.** The repository is handed its
  scheduler as a `Lazy`, so it is built the first time something actually schedules and never to
  answer `observeDownloads`. Building it is `WorkManager.getInstance`, and in a host that has
  removed `WorkManagerInitializer` - which is what implementing `Configuration.Provider` asks for -
  whichever call comes first is the one that initialises WorkManager. That used to be a host
  collecting `observeDownloads` at startup to count finished downloads, and on an Android 14 build
  whose framework has no `JobScheduler.forNamespace` it took the app down: WorkManager 2.12.0 calls
  that method on anything reporting SDK 34, with no fallback, so initialisation raised
  `NoSuchMethodError`. Seven crashes, one user, October 2026, before a download had been asked for
  at all. Reading the list is Room and nothing else, so now it stays that way and such a device
  gets its list. The guard is a test on the constructor's parameter types, because the three
  collaborators the read path never touches each need a `Context` and Kotlin will not let them be
  left out. Starting a download there still cannot work; the bullet below is what happens instead.
- **A scheduler that cannot be built is a refused download, not a dead app.** The same framework
  answers `WorkManager.getInstance` with a `LinkageError`, and an `Error` is not an `Exception`:
  it goes straight through `EnqueueDownloadUseCase`'s guard and the repository's `attempt`, and
  `MediaDownloader.download` has no guard at all. Deferring the build only moved the death from
  app start to the first press of `Download` - reproduced on a device by making that one call
  throw, where the app died on the press and lived once it refused. So `DownloadScheduler.of` is
  now the only way one is made, and it turns a `LinkageError` into
  `DownloadException.SchedulerUnavailable`, which every guard on the way out already reports.
  Nothing is swallowed: the platform's own error is the cause, and each download still fails,
  visibly, with the reason. Only a `LinkageError` is converted - the `IllegalStateException` a
  host gets for removing `WorkManagerInitializer` without supplying a `Configuration` is already
  an exception, already reported, and wrapping it would only hide the host's own mistake.
  `enqueue` inserts the row before it schedules, so the row is marked `Failed` with
  `Download.SCHEDULER_UNAVAILABLE_MESSAGE` as well; without that it sat in `Queued` for ever with
  nothing to run it, which is what the device showed.
- **Which rows a refused scheduler fails, and why stopping one is not refused.** The refusal can
  only come from the scheduler being *built*, and that happens once in a process, so in a loop over
  several downloads only the first start can raise it and nothing in the loop was scheduled. That
  is what lets `startAll` fail every id the caller has just moved to `Queued` in one statement -
  `resumeCollection` moves them all before it starts any - and `restoreUnfinished` fail every row
  it was about to restore, which is the one that matters in the field: 1.1.8 left a row in `Queued`
  on such a device each time it died between the insert and the job, and those rows would be
  restored, and raise this again, at every start for ever. It does not rethrow, so the orphan-file
  clean-up after it still runs, which it did not while this came out as an `Error`. Stopping is the
  other way about: `stopWhatCouldBeRunning` swallows the refusal, because a scheduler that was
  never built has never scheduled anything, so there is nothing to stop - and `delete` has removed
  the row by the time it stops the job, so refusing there left the files on disk, the notification
  up, and the host told that a download it can no longer see is still there. Starting is a download
  the caller asked for and did not get, and stays a failure. None of the three calls on a built
  scheduler is guarded: should `enqueueUniqueWork` itself ever raise a `LinkageError` it would
  escape as an `Error` again, and guarding it would cost the invariant above, which is worth more
  than a failure mode nothing has reported. The limit of all of it: a host that keeps
  `WorkManagerInitializer` has WorkManager initialised by `androidx.startup` before any module
  code runs, and on such a device it dies there, out of reach. The host this came from had
  removed it, which is why the module is in the path at all.
- **A failed page read answers WorkManager itself.** A playlist's entries are queued with only a
  page to read. `readItsPage` called `onFailed` and returned `null`, and the caller turned `null`
  into `Result.success()` - so the `Result` `onFailed` had decided on was thrown away. An ordinary
  failure puts the row back in `Queued` and asks for a retry; answering success left WorkManager
  believing the work was done with nothing scheduled, and `resume` would not take the row either,
  since `requeue` accepts only `Paused`, `Failed` and `WaitingForNetwork`. Read rather than
  reproduced: the worker wants WorkManager, which the unit tests have not got, and the device
  check runs real links, where a transient read failure cannot be asked for.
- **A part download looks at what is already there.** It deletes its parts and the length it cut
  them for the moment it joins them, and had no "already whole" test - only `downloadWhole` had
  one. A run that stopped after a file was finished left that file and nothing else, and the next
  run read the empty work dir as nothing done. The shape that hits it is a video whose sound is a
  second file: the picture is fetched first, and a pause or a job the system takes back in the
  seconds after leaves it whole and the sound missing, so a 500 MB picture was refetched on every
  one of five attempts.
- **Starting a collection replaces the one being added, which it did not.** The loop was launched
  and its `Job` dropped, so a fifty-item playlist followed by a three-item one queued all
  fifty-three rows and both counted into the same progress - "added 37 of 3", and a collection that
  said it was done and was not. Cancelling alone brings the other half of the fault: the cancelled
  loop reaches its `finally` after the new progress is in place and would announce *that* one as
  finished, so the `finally` ends only the adding that is still current. Both halves are tests.
- **A download's lock is dropped by counting, not by looking.** One lock per download kept an entry
  for every download the app ever ran. Removing it the moment it goes free is unsafe and is why it
  was never removed: between one run letting go and the next taking it the entry would be gone, and
  a third run asking then would be handed a new lock and run beside the second. The runs that want
  one are counted before any of them can see it free, and the entry goes at zero.
- **The carrier-proxy guard is put in the process's own place, and the rule is okhttp's, not ours.**
  A phone whose APN names a proxy rather than addressing one hands that name to every http client
  in the process; OkHttp refuses a hostname it cannot make canonical by throwing
  `IllegalArgumentException`, which is not an `IOException`, and on an enqueued call that comes out
  of its own dispatcher thread and kills the app.
  This module's own two clients have carried a guard since 1.1.1 and that was never enough: the
  crash reported from a host (65 events, `Dns$Request.<init>`, `Non défini ` and `mytelnet `) was
  its *own* client, which this module cannot reach. Handing each host a class to wire up makes
  every host discover the same thing and write the same code, so `initialize` installs it as the
  process default, under `MediaDownloaderConfig.guardCarrierProxies` for a host that would rather
  its global state were left alone.
  **Which names are dropped is asked of okhttp, because the rule is not one to copy.** This was
  twice written by hand here - first "an upper-case letter is enough", then "whitespace is the
  whole of it" - and both were measured from a handful of names and both were wrong. Put 22 names
  through okhttp and through the hand-written rule and they disagree on 12: `wap:8080`,
  `http://wap.mtn.com`, `wap@airtel`, `wap/proxy`, `wap?x`, `wap#x`, `wap%20x`, `[airtel]`, a
  backslash, a control character and a label over 63 bytes were all handed on to be refused - and
  the first two are exactly what someone writes into a proxy field that wanted a host. So the guard
  calls `HttpUrl.Builder.host`, which runs the canonicalisation `Dns.Request` runs; on the same 22
  it disagrees with okhttp on none.
  Three things it will not do, all tested. A client takes the default **when it is built**, so
  anything built before `initialize` - an sdk starting from a `ContentProvider` - is not covered.
  The installed selector holds the one it replaced rather than looking it up, because by then it is
  itself the answer and would ask itself for ever. And what that one throws is passed on rather
  than swallowed: answering a refusal with a direct connection was this module's own business while
  the guard sat on this module's clients, and is everyone's now, where it would walk a host past a
  proxy it is required to use.
- **A tray disc has no coordinates of its own, because every coordinate was wrong.** It was
  `position: fixed` with a left and top computed from the tile's `getBoundingClientRect`; that was
  changed to `position: absolute` with the same arithmetic, and the owner's next picture showed the
  same fault. Measuring his picture is what settled it: the page painted one css pixel as 1.17
  device pixels, while a css pixel of the discs' spacing was painted as 2.63 - the device's own
  density - so the three discs stood 2.25 times their tiles apart, out over the white beside the
  page. When a page is composited at a scale it was not laid out for - the zoom entry below, still
  not understood - what `getBoundingClientRect` reports and what the page paints disagree by a
  constant, so **any** placement computed from a rectangle is wrong by that constant, `fixed` or
  `absolute` alike.
  The disc now lives inside its own tile, at `right: 8px; bottom: 8px`, and the browser places it.
  There is no number for the script to get wrong, and it is carried by the strip's transform, the
  page's scale and every scroll for free. Facebook's `role="button"` wrapper is made `relative`
  when it is `static`, so it is the box the disc is measured against. Measured afterwards: four
  cards at 12, 159, 306 and 453 css px, each 135 wide, and four discs at 84, 231, 378 and 525 -
  `card.right - 63` every time - visible in five consecutive frames, and a press opened the reel
  the disc sat on. The old note that a disc inside a tile "took no presses at all" does not hold
  for one hung on the wrapper rather than on the strip.
  The covered question is asked of that wrapper, not of the `<img>` inside it: the middle of a
  reels cover is facebook's play circle, a sibling of the picture rather than part of it, so asking
  about the picture answered "covered" for every tile and took every disc away.
- **One timer owns a tray button, because two made it flash.** `mksTrayPlace` runs twice a second
  to keep each button beside the tile it belongs to, and the covered-button sweep ran on its own
  800ms beat; both wrote `display` on the same element and disagreed, so a button over the dialog
  appeared for 500ms and went for 800ms, over and over. That is the blinking the owner reported,
  and it is also why a disc was on the dialog at all: the placer did not know about the sweep's
  question and kept putting it back. The placer asks `mksCovered` itself now, and the sweep skips
  anything carrying `mksTileOf`. Measured after: six seconds with the dialog up and not one disc in
  any frame, eight seconds with it closed and a disc in every frame - the size of them varies by a
  few hundred pixels across frames, which is the `pulse` animation every button has had from the
  start, not a flash.
- **Only a raised layer counts as covering a card.** The sweep above asked what is at the middle
  of a card and took the button away when the answer was not the card's own. That is a different
  question from "is something drawn over this", and answering it cost the button on every opened
  reel. On `m.facebook.com/watch/?v=<id>` the player's chrome - the caption bar, the like and
  comment rail, the tap target that unmutes it - is laid out as a sibling of the `<video>` and
  covers the whole player, so the middle of the card belongs to the chrome and never to the card.
  Measured on the device, signed out, on a reel opened from the tray: the disc was created
  (`btnFound=redbtn`), `mksShowOrHide` put `display:none` on it a beat later, and it never came
  back - seven probe points over the card, every one of them answering `DIV.wbloks_1`, none of them
  the card's.
  What separates a dialog from the page's own chrome is how it is placed, and that is measurable.
  The "Get the full experience" sheet is raised over the page: `m fixed-container bottom`,
  `position: fixed`, `z-index: 1` on the feed, and `wbloks_25 … position: fixed, z-index: 1000` on
  a reel. The player's chrome is not raised at all - walking from it up to the ancestor it shares
  with the card gives `relative`, `relative`, `absolute`, and the shared ancestor is a few steps
  up rather than the body. So `mksCovered` now walks that stretch and reports covered only when a
  `fixed` or `sticky` box is on it. Measured both ways on the one page, with the function lifted
  out of the asset and run in the live page: with the sheet up, the playing video's disc stays
  hidden and two of four on-screen cards are judged covered; with the sheet dismissed, all eight
  are shown, the disc is back on the playing reel, and pressing it opened the sheet on HD 1.97 MB.
  This shipped broken in 1.1.2, where the cover check was added, and was reported after a host
  moved from 1.0.1 to 1.1.4.
- **A size test asked before the page has a size lets everything through.** The two picture rules
  skip anything narrower than 60% of the viewport, which is what keeps the stories tray and the
  rails beside it from being given buttons. But this script is put into the page with its first
  answer, long before the page is laid out, and `window.innerWidth` is then `0`: every tile is
  wider than 60% of nothing, and `Math.min(innerWidth, innerHeight)/5` - the floor a picture must
  clear at all - is `0` too. Measured on the device on the signed-in feed: four runs at
  `innerWidth 0` drew eight buttons, over a stories tray and over avatars of 16, 32 and 48 pixels,
  and the run that could finally measure drew only the five the feed should have. A button once
  drawn is never drawn again, so all eight stayed on the page. `mksIsStory` now answers "yes" when
  there is no width to measure against, which makes both rules wait for the run that can see the
  page; nothing is lost, because every later injection draws what that run finds.
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
  served from a host not yet listed is now caught by its length instead.
- **Redtube and youjizz, September 2026 on the Vivo.** Redtube reads from a pasted link - four
  qualities, 1080p at 180.8 MB - and its 240p downloaded at 25.5 MB. Two things were in the way and
  both were the reader's: an address that is nothing but an id (`redtube.net/191294011`) was not
  taken for a video's page, and the network writes its endpoints as a path alone (`/media/mp4?s=…`)
  rather than a url, so they are now read against the page they came from. youjizz reads as well,
  though its size cannot be measured. xvideos remains unreachable here; it runs the same player as
  xnxx, which is read, so it is expected to work where it can be reached.
- **KVS, the script most of these sites run, is read from its own fields.** `video_url` with
  `video_url_text` for the quality's name, and `video_alt_url`, `video_alt_url2`… beside it: the
  site names its qualities, so a page whose files carry no height still has them. Its requests also
  carry `kt_tcookie=1` and whatever cookies the browser holds for the site, because KVS hands its
  files only to a visitor whose player has loaded.
  It is still not enough everywhere: inxxx reads (title, one quality) and its file comes back as a
  page of some 150 kB. KVS ties a `get_file` link to the session that built the page, and the copy
  of this in the ViolationFix `url-parser` gets past that by carrying a captured `PHPSESSID` in its
  source - a session that will stop working, so it was not copied.
- **The five sites the ViolationFix `url-parser` covers, checked one by one on the Vivo.** From a
  pasted link: pornhub (4 qualities, 240p downloaded), xhamster and its `xhamster46.desi` mirror
  (5 qualities, 144p downloaded), xnxx (2 qualities, 240p downloaded) and inxxx (one quality; its
  size cannot be measured and the download failed here). brazzers is not readable and nothing is
  missing: its page says `"contentUrl": null` - the site is a subscription, and the page holds no
  video to take. Reaching inxxx needed `/v/` among the paths a video is filed under, and brazzers
  needed the linked data to be read from the page itself and not only from a `ld+json` block, which
  is where a site built on a JavaScript framework writes it.
- **xHamster, September 2026 on the Vivo.** Both `xhamster.com` and the `xhamster46.desi` mirror
  now read from a pasted link: five qualities, 1080p at ~820 MB and ~209 MB, and the 144p file
  downloaded and saved. Before this, a press during the pre-roll answered with thirty seconds of
  advert and could never correct itself.
- **Downloads, September 2026 on the Vivo with the VPN on.** Taken to a file on disk and then
  deleted: xhamster 144p (18.8 MB, HLS remuxed), xnxx 240p (8.4 MB), youporn (88.1 MB), tube8
  (193.4 MB), txxx (165.8 MB) and porntrex (85.3 MB); pornhub's 240p was taken on the SM-A26
  earlier. Only eporner failed, and not in the module: the `contentUrl` its linked data gives
  (`gvideo.eporner.com/<id>/<id>.mp4`) answers with something a browser cannot decode either, so
  there is nothing there to fetch.
  Two things to know when reading a failure here. A big file over a filtered network is reset
  repeatedly - the worker resumes from what is on disk and the row can still end as failed after
  its attempts run out, having downloaded 98% of the file; the log says
  `SocketException: Connection reset`, not a refusal. And a download started twice fails the second
  time, because the first spent the link.
- **September 2026, a third pass on the Vivo, with the phone's VPN on.** Read from a pasted link:
  pornhub (4 qualities, 1080p 130.7 MB), youporn through its `you-porn.com` mirror (4, 629.0 MB),
  tube8 through `tube8.es` (3, 720p 760.5 MB) and porntrex (3, 1080p 262.0 MB). Answered by the
  browser's button, because their pages carry nothing a reader can use: spankbang (4 qualities,
  1080p ~269 MB - its page answers a plain request with a 6 KB shell), txxx (15:28, 158.1 MB) and
  drtuber (5:03, 41.6 MB). hqporner keeps its player in another frame, and its own player errored
  here ("Oops.. Something went wrong"); its page's *Alternative player* then played the video, whose
  play control navigates straight to the file, and the button on that offered it at 56:01 and
  305.8 MB. Nothing is found there until something plays, which is the rule everywhere: a page that
  has asked for no media has none to give. xvideos and hdzog were reset outright even with the
  VPN on, and could not be reached at all.
- **September 2026, a second pass on a Vivo V2066 (Android 13), same filtered network:**
  pornhub and youporn (through its `you-porn.com` mirror) both answer a pasted link with four
  qualities at their real sizes - 1080p at 130.7 MB and 629.0 MB. Reaching that took three fixes
  the mirrors made plain: a page's linked data describes its cover beside its video, as an
  ImageObject with a `contentUrl` of its own, and the cover was being offered as the video (one
  quality, 132.4 KB, the video's running time); the mirrors write the player object with nothing in
  front of it, so it is now found by its own key; and they list no size beside an entry, naming the
  quality instead, behind a signed endpoint that has to be followed. hqporner keeps its player in
  another frame (`mydaddy.cc`) and carries nothing on its own page: it is a browser case, not a
  reader's. spankbang is behind a Cloudflare challenge from here, and xvideos is reset outright -
  neither was reachable to test.
- **September 2026, on a filtered network without a VPN:** pornhub (parser and page button), xnxx
  and eporner answer with their qualities. xHamster's page cannot be parsed at all - it hands its
  player encrypted urls - and, though its stream is plainly fetched once the video plays
  (`…xhpingcdn.com/…/_TPL_.av1.mp4.m3u8`, which the stream rules now name after the site moved off
  `xhcdn.com`), a press on its player now answers with the video - its own title and 17:35, not the
  thirty second pre-roll, and its download now completes: pressed on the playing video, the sheet
  offers 1080p (~610 MB) down to 144p (~17.8 MB) and the 144p file saved at 18.78 MB. What had been
  wrong was the codec, not the request - a plain fetch of that signed master answers 200 with no
  referer and no cookies, but the stream it names is AV1, which the mp4 remux cannot take. The
  H.264 twin beside it is read instead.

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

`ScenarioDeviceCheck` holds what `DownloadRepositoryImpl` does against the real scheduler, database
and files: a download queued with no connection and finished when it returns, delete of a finished
row and of one still fetching, a collection queued, paused and resumed as one unit, the
notification appearing and going with the row, and a link that answers 404. It lives on the device
because the unit tests reach that class only through `FakeDownloadRepository`, which records the
call and answers success - so they stay green whether or not the real one works, and every defect
found in it so far was invisible to them. It **toggles airplane mode**, so run it over USB rather
than wifi adb.

`SoundAndScriptDeviceCheck` holds the two that need the device itself rather than a reading of the
code: a video whose sound is a second file - interrupted, and paused between the two tracks - and
the fallback at the end of `twitter.js` run by the phone's own Chromium. Its dropped-connection
check serves both tracks from a socket on the device's loopback, because cutting a body off
mid-file on cue is the one thing a CDN cannot be asked to do; that is why the **test** manifest
alone allows cleartext. Pass `#aSoundLostToADroppedConnectionIsNotCalledComplete` as the class
argument to run that one on its own.

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
