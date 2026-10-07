# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android library, `:media_downloader` (package `com.markhoor.mediadownloader`), that turns a link
into downloadable media: link parsing for social sites, in-WebView media detection, and background
downloads (WorkManager) with HLS remuxed to MP4. `:app` (package `com.media.downloader`) is a small
Compose demo that consumes the library via `project(":media_downloader")`. minSdk 24, compileSdk 37,
Java 21.

**Read `media_downloader/MODULE-GUIDE.md` before changing parsing, detection or downloads.** Its §6
"Decisions & assumptions" records why things are the way they are, including many behaviours that
look like bugs but are deliberate. `media_downloader/HOST-GUIDE.md` documents the public API. Keep
both in step with code changes.

## Commands

```bash
./gradlew :media_downloader:assembleDebug
./gradlew :media_downloader:testDebugUnitTest
./gradlew :media_downloader:testDebugUnitTest --tests "*ParseLinkUseCaseTest"          # one class
./gradlew :media_downloader:testDebugUnitTest --tests "*ParseLinkUseCaseTest.someTest" # one test
./gradlew :media_downloader:lintDebug
./gradlew :app:installDebug                         # demo app
./gradlew :media_downloader:publishToMavenLocal     # for a local consumer
tools/push.sh                                       # push main to both homes (see below)
```

- Live scraper check against real sites (skipped unless the env var is set):
  `MEDIA_DOWNLOADER_LIVE=1 MEDIA_DOWNLOADER_LINKS="https://… https://…" TWITTER_API_KEY=… ./gradlew --no-daemon :media_downloader:testDebugUnitTest --tests "*LiveParseCheck" --rerun -i`
- Device checks (real downloads through worker/Room/notification; installs only the test APK):
  `ANDROID_SERIAL=<device> ./gradlew --no-daemon :media_downloader:connectedDebugAndroidTest`.
  Browser check only / low-end mode and the manual process-death procedure are in MODULE-GUIDE §8.
- The demo app reads the X/Twitter key from `local.properties` (`tweeload.apiKey=…`) into
  `BuildConfig.TWEELOAD_API_KEY`; without it only X links fail. Never put the key in source.

## Two homes

The library is published from two repositories. They hold the same work and the same tags; what
differs is whose name is on it, because each contributor list should show one owner and nobody
else.

| generated branch | remote | repository | coordinate | name on every commit |
|---|---|---|---|---|
| `for-tetsdks` | `origin` | `tetsdks/mediadownloader` | `com.github.tetsdks:mediadownloader` | `tetsdks <topedgetech111@gmail.com>` |
| `for-dev-husnain` | `dev-husnain` | `Dev-Husnain/MediaDownloaderLibrary` | `com.github.Dev-Husnain:MediaDownloaderLibrary` | `Hussnain Mehdi <hussnain.personal@gmail.com>` |

**You work on `main` and nothing else**, and neither repository is ever pushed the branch you work
on. **`tools/push.sh`** is the only push there is: for each home it throws that home's branch away,
builds it again from `main` with that home's name on every commit, and force-pushes it as that
repository's `main`. The two generated branches are **never developed on, never committed to by
hand and never merged back** - anything committed on one is gone at the next push. Because both are
re-authored, it does not matter which name this clone commits as. `main` deliberately tracks no
remote branch: it is never pushed as it stands, so an ahead/behind count against either repository
would only mislead.

`main` is written in **tetsdks** own name - it is the public face - so `for-tetsdks` only needs
re-authoring, while `for-dev-husnain` also swaps those names for its own in one commit: the README,
where the coordinate is copied from and the badge reads that repository's tags, and the pom
metadata in `media_downloader/build.gradle.kts`, so a consumer of one coordinate is never pointed
at the other repository. Nothing else differs, down to the tree. Documentation that deliberately
names *both* homes (this file, `RELEASE.md`) is left alone.

The rewrite keeps every original date, so a commit comes out with the hash it came out with last
time and a branch grows by exactly what `main` grew by; only each branch's own last commit is made
afresh, which is why the pushes are forced - and each repository's tags pin every commit JitPack
has been served, so a forced push cannot lose one. `tools/release.sh` does all of this with a
version and a tag on top; both share `tools/homes.sh`, where the two homes are described once.
Nothing in a build file needs the version: JitPack passes the coordinate in (`-Pgroup`/`-Pversion`)
from the tag.

## Releasing

Releases are cut by git tag; JitPack builds only the library (`jitpack.yml`: JDK 21,
`publishToMavenLocal -x test`) and serves it as
`com.github.<owner>:<repo>:<tag>` (the repo name, not the module path). **There is no version to
edit in a build file** - the tag is the version (`git describe` only fills it in for local
publishes). Cut one with `tools/release.sh <version> "what changed"`: it builds and tests, rewrites
the version the docs tell people to copy, then tags and pushes **both** repositories, each with its
own README. `RELEASE.md` has the whole procedure.

## Architecture (library)

Clean layering: `presentation → domain ← data`. `domain/` is pure Kotlin and imports neither.

- **Entry point:** `MediaDownloader` (object) + `MediaDownloaderConfig`. `initialize()` must run in
  `Application.onCreate` (WorkManager can start a download in a fresh process); it is idempotent and
  the first config wins. Every collaborator is constructed lazily by hand in
  `di/MediaDownloaderComponent` — **no DI framework** inside the module, deliberately.
- **Link parsing:** `ParseLinkUseCase` → `MediaParserRepositoryImpl` → `ScraperResolver` picks
  `SiteScraper`s per host (one package per site under `data/scraper/`), `ScraperRacer` races
  fallbacks and cancels losers. HLS masters are expanded into qualities (`data/hls/`), sizes probed
  (`MediaSizeProbe`). All HTTP goes through Ktor (`HttpFetcher` never throws).
- **Site policy:** `RestrictedSites` only names a host's category; `CheckSiteAccessUseCase` alone
  decides Allowed/Blocked/Unsupported, and every other path (sniffer, `StreamLocator`) asks it via
  `blocksHostOf`. Adult and YouTube are blocked by default, each lifted only by its own switch.
  Hosts are judged on the normalised host, never by substring.
- **Browser detection:** `MediaBrowser` / `BrowserViewModel` attach to a WebView;
  `MediaDetectionSession` combines request sniffing (`SniffPolicy`), `StreamLocator`, and page
  scripts. The scripts are plain JS in `src/main/assets/media_downloader/*.js` and call
  `PageScriptBridge` methods **by exact name and argument count** — a mismatch is a silent JS
  TypeError that stops the rest of the script, so change both sides together.
- **Downloads:** `EnqueueDownloadUseCase` → `DownloadRepositoryImpl` → Room (`DownloadEntity`, the
  single source of truth for progress) + `DownloadScheduler`/`DownloadWorker`. `DownloadEngine`
  dispatches to `DirectFileDownloader` (incl. parallel ranges) or `HlsStreamDownloader` (AES-128,
  remux via `StreamRemuxer`/MediaMuxer, separate audio merged). State changes only through
  conditional DB writes so concurrent callers can't both win. `DownloadStorage` handles folders,
  naming, publishing and media scan; storage problems are refused *before* enqueue.
- **Presentation:** three public ViewModels (`LinkParseViewModel`, `BrowserViewModel`,
  `DownloadsViewModel`), each with a `Factory`. The module has no screens; hosts own all UI.

## Conventions (enforced in this module)

- Only the host-facing API is `public`; everything else is `internal`. Dependencies named in public
  signatures are `api(...)` in the Gradle file; everything else `implementation`.
- **Every extension function lives in `core/Extensions.kt`** (grouped by region) and **every
  constant in `core/Constants.kt`** (grouped by object). Elsewhere, helpers are private functions;
  only compiled regexes may stay private beside the parser that uses them.
- Naming: domain `…Model`, network `…Dto`, Room `…Entity`, UI `…UiState`, impls `…RepositoryImpl`;
  enums/exceptions carry no layer suffix; no stacked suffixes.
- No `lateinit`, no `!!`, no `GlobalScope`, no `runBlocking` outside tests. Shared state is
  immutable, `@Volatile`, atomic or locked. IO dispatcher is injected.
- Errors: scrapers may throw, `SiteScraper.scrape` converts to `Result.failure` in one place; parse
  failures surface as `MediaParseException`. `CancellationException` is always rethrown, never wrapped.
- **Adding a site:** a `SiteScraper` in `data/scraper/<site>/` (plus a pure `…Parser` object if the
  parsing deserves tests), its link check in the *Site links* region of `Extensions.kt`, endpoints/
  headers in `Constants.kt`, a line in `ScraperResolver`, and wiring in `MediaDownloaderComponent`.
- Unit tests use JUnit 4, kotlinx-coroutines-test and Ktor `MockEngine`; `FakeMediaServer` and
  `FakeDownloadRepository` are the shared fakes. `isReturnDefaultValues = true` so `android.util.Log`
  doesn't break JVM tests.

## Two repos, one licence boundary

This repo is **Apache-2.0 and must stay free of GPL code**. YouTube support lives in a *separate*
repo because NewPipeExtractor is GPLv3 and GPL obligations travel with distribution:

| | repo | licence | coordinate |
|---|---|---|---|
| library | `tetsdks/mediadownloader` + `Dev-Husnain/MediaDownloaderLibrary` (this one, both homes) | Apache-2.0 | `com.github.tetsdks:mediadownloader:1.1.0` |
| YouTube add-on | `Dev-Husnain/MediaDownloaderYouTube` (`D:\Other Data\DownloaderLibByHussnain\MediaDownloaderYouTube`) | GPL-3.0 | `com.github.Dev-Husnain:MediaDownloaderYouTube:0.1.0` |

The dependency only ever points **add-on → library**, never back. Nothing in this repo may name
`NewPipeExtractor` or `MediaDownloaderYouTube` in a build file; a `pre-push` hook in `.git/hooks`
(not versioned — re-create it after a fresh clone) refuses any ref whose tree does. YouTube is also
blocked at runtime unless the host flips `allowYouTube`, and Play Store policy forbids shipping it,
so it is a learning/experiment path only.

## Host extension points (how the add-on plugs in)

The library never learns about YouTube; a host registers a reader in `MediaDownloaderConfig`:

- `MediaSource` — `hosts`, `handles(url)`, `read(url): MediaModel?`. Wrapped by
  `HostSuppliedScraper` into an ordinary internal `SiteScraper`, so a host-supplied site behaves
  exactly like a built-in one.
- `MediaCollectionSource` — `handlesCollection(url)`, `readCollection(url): MediaCollectionModel?`
  for playlists/albums.
- `MediaDownloader.read(text): Result<ParsedLink>` asks collection sources first, then falls back to
  `ParseLinkUseCase`; hosts call this one method for a single link *or* a playlist.
- `downloadCollection(collection, preferredQuality)` queues **every** entry at once with a blank
  `mediaUrl`; `DownloadWorker.readItsPage` resolves each row's real URL when its turn comes (fresher
  URLs, survives process death, and the not-yet-started items are visible as `Queued`).
  `collectionProgress()`, `pauseCollection(title)`, `resumeCollection(title)` treat a playlist as one
  unit; rows are grouped by `collectionTitle`.
- `DownloadState.Finishing` exists so a remux/merge after 100% no longer looks pausable
  (`isActive`/`canPause`).

## Branches in this repo

- `main` — published, Apache-2.0, no YouTube. **The only branch worked on.**
- `for-tetsdks`, `for-dev-husnain` — not branches to work on: `tools/push.sh` throws each away
  and builds it again from `main` every time (see *Two homes*). Anything committed on one by hand
  is lost at the next push.
- `youtube-demo` — `main` + the demo wired to the *published* add-on, for hands-on YouTube testing.
  Local only; the hook blocks pushing it.
- `archive/youtube-in-library` (tag `archive/youtube-in-library-2026-09`) — the old shape, when the
  YouTube scraper still lived inside the library. Kept for reference, never merged.

All of them build from a clean checkout. After switching branches in Android Studio use
**File → Reload All from Disk**; a stale open buffer has silently overwritten
`gradle/libs.versions.toml` here before.

## Working agreements

- Each repository's history carries one name only - `tetsdks` on `tetsdks/mediadownloader`,
  **Hussnain Mehdi** on `Dev-Husnain/MediaDownloaderLibrary` - and the script puts it there, so
  what this clone commits as is its own business. Dev-Husnain is pushed over SSH remote
  `github-dev-husnain`, tetsdks over HTTPS.
  **No AI/assistant attribution or co-author lines in commit messages.** GitHub reads a
  `Co-authored-by` trailer and credits whoever it names in the repository's contributor list, so
  one such line puts a second name on a repository meant to carry one. `tools/homes.sh` strips
  those trailers from every commit it generates, which is a guard, not a licence to write them.
  A repository's **tags** count the same way, so each home's tags point at its own commits: a tag
  made on one home's branch is never pushed to the other.
- Releases are cut by tag in both repos; JitPack caches a tag, so a bad publish needs a *new* tag.
- The `newdownloader` app still carries its own copy of the module and is bumped to the published
  library by its owner — **do not change it from here**.
- Testing the published pair: bump to a fresh version for every local round
  (`publishToMavenLocal`), because Gradle happily serves a stale mavenLocal artifact and makes a
  real fix look like it did nothing.
