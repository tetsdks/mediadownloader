# MediaDownloaderLibrary

An Android library that turns a link into media and downloads it: link parsing, in-browser media
detection, background downloads with progress, pause/resume/retry, and HLS streams remuxed to MP4.

This repository holds both the library (`:media_downloader`) and a small Compose demo app (`:app`)
that uses it.

| | |
|---|---|
| Package | `com.markhoor.mediadownloader` |
| minSdk | 24 |
| compileSdk | 37 |
| Java | 21 |
| DI framework | none - nothing to register in Koin/Hilt |
| Threads | every public call is safe from any thread |

## What it does

- **Link parsing.** A pasted or shared link becomes a title, a thumbnail and every quality with its
  size. Read directly for Facebook, Instagram, Threads, TikTok, X/Twitter, Pinterest (incl.
  `pin.it`), Dailymotion and LinkedIn.
- **Browser detection.** Attach a WebView and the module finds the media the page is playing -
  page scripts, request sniffing, parser pages - and draws its own small download buttons in the
  page where it can.
- **Downloads.** Background downloads through WorkManager with a progress notification, surviving
  the app being closed, killed or the phone restarting. Pause, resume, retry and delete. HLS
  (including AES-128) is remuxed into MP4, separate audio merged.
- **It says what went wrong.** A lost connection shows "Waiting for a connection" rather than the
  notification vanishing; media that was never there to take - a private or deleted post, a spent
  link - fails at once as `isMediaGone` instead of four pointless retries; a folder it cannot write
  to is refused *before* the download starts, with the one thing the host can do about it.
- **Site policy.** Adult sites and YouTube are blocked by default, and each category is lifted only
  by its own switch (`allowYouTube` / `allowAdultSites`). An optional supported-sites list limits
  downloads to sites that are known to work.

## Documentation

- **[Host Guide (web)](https://claude.ai/artifact/Butg3mNjfgXuFX2MSjmftE)** - the whole public API,
  models, errors, ViewModels and copy-ready recipes, as a readable page.
- [`media_downloader/HOST-GUIDE.md`](media_downloader/HOST-GUIDE.md) - the same guide in the repo.
- [`media_downloader/MODULE-GUIDE.md`](media_downloader/MODULE-GUIDE.md) - how it works inside, and
  why. Read this before changing parsing, detection or downloads.

## Adding it to a project

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.Dev-Husnain:MediaDownloaderLibrary:0.1.0")
}
```

The consuming module must compile at Java 21 (`compileOptions { sourceCompatibility =
JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }`).

## Quick start

**1. Initialize once, in `Application.onCreate`.** Not optional: WorkManager can start a download in
a fresh process, before any screen exists.

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MediaDownloader.initialize(
            this,
            MediaDownloaderConfig(
                strictSupportedSitesOnly = true,
                twitterApiKey = BuildConfig.TWEELOAD_API_KEY.ifBlank { null },
                notificationActivity = MainActivity::class.java,
            ),
        )
    }
}
```

**2. Read a link.**

```kotlin
MediaDownloader.parse(sharedText)
    .onSuccess { media -> showQualities(media) }
    .onFailure { error -> /* every failure is a MediaParseException */ }
```

**3. Download one of its qualities, and follow it.**

```kotlin
val id = MediaDownloader.download(media, media.qualities.first()).getOrNull() ?: return

MediaDownloader.observeDownload(id).collect { download ->
    render(download?.state, download?.progressPercent)
}
```

There are also ready-made ViewModels - `LinkParseViewModel`, `BrowserViewModel`,
`DownloadsViewModel` - each with a `Factory`, if you would rather not hold the state yourself. The
demo app uses those.

## What the host is responsible for

The library **declares** its permissions (they merge into your manifest) but never **requests**
them:

| | When | If refused |
|---|---|---|
| `POST_NOTIFICATIONS` | Android 13+ | downloads still run, silently |
| `WRITE_EXTERNAL_STORAGE` | Android 9 and 10 only | `download` refuses with `StorageNotWritable` |

Android 10 also needs `android:requestLegacyExternalStorage="true"` on your `<application>` - only
you can set that, and `MediaDownloader.storageRefusalOrNull()` tells you when it is missing rather
than asking the user for a permission they already granted.

Do not install a `WorkerFactory` that answers for every class name: the module's own worker would be
built wrong.

## The demo app

`:app` is a small Compose demo of everything above: paste or share a link and pick a quality, browse
with the module's detection running, and a downloads list with live progress and pause/resume/delete.

To run it, put your X/Twitter api key in `local.properties` (git-ignored, optional - without it only
X links fail):

```properties
tweeload.apiKey=YOUR_KEY
```

then `./gradlew :app:installDebug`.

## Building and publishing

```bash
./gradlew :media_downloader:assembleRelease      # the AAR
./gradlew :media_downloader:test                 # unit tests
./gradlew :media_downloader:publishToMavenLocal  # for a local consumer
```

Releases are cut by tagging. JitPack builds the tag and, because the repository publishes a
single artifact, serves it under the repository's own name -
`com.github.Dev-Husnain:MediaDownloaderLibrary:<tag>` - not the module path. The build log at
`jitpack.io/com/github/Dev-Husnain/MediaDownloaderLibrary/<tag>/build.log` always prints the
coordinate it actually served.

## License

All rights reserved. Contact the author for use outside this repository.
