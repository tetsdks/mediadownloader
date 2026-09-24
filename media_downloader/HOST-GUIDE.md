# media_downloader — Host Guide

How to use the `media_downloader` module from an app ("the host"): setup, every public class,
function and model, and ready-to-copy recipes.

For how the module works inside, and why, see [`MODULE-GUIDE.md`](MODULE-GUIDE.md). This guide
covers only what a host can see and use.

---

## Contents

1. [What the module gives you](#1-what-the-module-gives-you)
2. [Adding it to a project](#2-adding-it-to-a-project)
3. [Initializing](#3-initializing)
4. [`MediaDownloader` — the entry point](#4-mediadownloader--the-entry-point)
5. [Models](#5-models)
6. [Errors](#6-errors)
7. [ViewModels for screens](#7-viewmodels-for-screens)
8. [Recipes](#8-recipes)
9. [Behaviour the host should know](#9-behaviour-the-host-should-know)
10. [Customizing](#10-customizing)
11. [Troubleshooting](#11-troubleshooting)
12. [Public API at a glance](#12-public-api-at-a-glance)

---

## 1. What the module gives you

| Area | What the module does | What stays with the host |
|---|---|---|
| **Link parsing** | Turns a pasted or shared link into media: title, thumbnail, every quality with a label and size | The screen that shows it |
| **Browser detection** | Finds media on the pages a WebView shows: page scripts, request sniffing, parser pages, its own in-page buttons | The WebView's layout, address bar, tabs, history |
| **Downloads** | Background download with a notification, pause/resume/retry/delete, resume after the app is killed or the phone restarts, HLS streams (incl. AES-128) remuxed to MP4 | The downloads screen |
| **Progress** | Every download's state and progress as a `Flow` | Rendering it |
| **Site policy** | A block list (adult sites, YouTube) that always applies, plus an optional supported-sites list | Choosing strict or open mode |

The module has **no UI of its own** except its notifications and the small download buttons it
draws inside web pages. It sends **no analytics**; the host observes states and reports what it wants.

**Package:** `com.markhoor.mediadownloader` · **minSdk** 24 · **compileSdk** 37 · Java 21 · Kotlin.
No DI framework: nothing to register in Koin/Hilt.

---

## 2. Adding it to a project

### 2.1 Gradle

**1. Copy the folder** `media_downloader/` into the project root, next to `app/`.

**2. `settings.gradle.kts`:**

```kotlin
include(":media_downloader")
```

**3. App `build.gradle.kts`:**

```kotlin
dependencies {
    implementation(project(":media_downloader"))
}
```

**4. Plugins.** The module uses three Gradle plugins. If the root `build.gradle.kts` doesn't
declare them yet, add them there (`apply false`):

```kotlin
// root build.gradle.kts
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
```

AGP 9 compiles Kotlin by itself, so the module needs no `org.jetbrains.kotlin.android` plugin.
On an older AGP (8.x), add `id("org.jetbrains.kotlin.android")` to the module's plugins as well.
The KSP version must match the project's Kotlin version.

**5. The module's own `build.gradle.kts`.** It ships written against this project's version
catalog (`libs.…`). In a project without that catalog, **replace the file with this one**, which
uses the same libraries with direct coordinates:

```kotlin
// media_downloader/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.markhoor.mediadownloader"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            // Library code logs through android.util.Log; tests should fail on assertions, not on that.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Network: Ktor on OkHttp, with JSON
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Room (downloads database) - the compiler runs through KSP
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Tests - only needed to run the module's own tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-client-mock:3.6.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.activity:activity:1.13.0")
}
```

Kotlin coroutines (`kotlinx-coroutines-core` / `-android` 1.11.0) come in through these libraries
and need no line of their own.

**The libraries, one per line**, if the host only wants to see what the module brings or align
versions with its own:

| Library | Version | Used for |
|---|---|---|
| `io.ktor:ktor-client-core` | 3.6.0 | HTTP client |
| `io.ktor:ktor-client-okhttp` | 3.6.0 | Ktor engine (OkHttp) |
| `io.ktor:ktor-client-content-negotiation` | 3.6.0 | JSON bodies |
| `io.ktor:ktor-serialization-kotlinx-json` | 3.6.0 | JSON bodies |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | Parsing site answers |
| `androidx.core:core-ktx` | 1.19.0 | Notifications, context helpers |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.11.0 | The module's ViewModels |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.11.0 | Lifecycle-bound browser |
| `androidx.work:work-runtime-ktx` | 2.11.2 | Background downloads |
| `androidx.room:room-ktx` | 2.8.5 | Downloads database |
| `androidx.room:room-compiler` (ksp) | 2.8.5 | Room code generation |

| Plugin | Version |
|---|---|
| `com.android.library` | 9.4.0 |
| `org.jetbrains.kotlin.plugin.serialization` | 2.4.20 (= the project's Kotlin) |
| `com.google.devtools.ksp` | 2.3.11 |

If the host already uses one of these libraries at another version, Gradle picks the higher one.
Keep **Ktor's four artifacts on one version**, and Room's two on one version.

**Using a version catalog instead?** Add these to `gradle/libs.versions.toml` and keep the module's
shipped `build.gradle.kts` as it is:

```toml
[versions]
agp = "9.4.0"
kotlin = "2.4.20"
ksp = "2.3.11"
ktorClientAndroid = "3.6.0"
kotlinxSerializationJson = "1.11.0"
coreKtx = "1.19.0"
lifecycleViewmodel = "2.11.0"
workRuntime = "2.11.2"
roomKtx = "2.8.5"
junit = "4.13.2"
kotlinxCoroutines = "1.11.0"
junitVersion = "1.3.0"
espressoCore = "3.7.0"
activity = "1.13.0"

[libraries]
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktorClientAndroid" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktorClientAndroid" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktorClientAndroid" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktorClientAndroid" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktorClientAndroid" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-viewmodel-ktx = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "lifecycleViewmodel" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycleViewmodel" }
androidx-work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "workRuntime" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "roomKtx" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "roomKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
androidx-activity = { group = "androidx.activity", name = "activity", version.ref = "activity" }

[bundles]
ktor-app = ["ktor-client-core", "ktor-client-content-negotiation", "ktor-serialization-kotlinx-json", "ktor-client-okhttp"]

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

R8 rules ship with the module (`consumer-rules.pro`); the host adds nothing.

### 2.2 Manifest — merged automatically

The module's manifest adds these to the host's; **no manifest edits are needed**:

| Entry | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Parsing and downloading; waiting for a connection |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | A download keeps running in the background |
| `POST_NOTIFICATIONS` | Download notifications (Android 13+) |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="29"`) | Saving into `Download/` on Android 9 and 10 |
| `SystemForegroundService` with `foregroundServiceType="dataSync"` | WorkManager's foreground service, required from Android 14 |

**One host attribute is needed for Android 10:** files are written with plain file access into
the public `Download/` folder, which on API 29 requires

```xml
<application android:requestLegacyExternalStorage="true" ... >
```

### 2.3 Runtime permissions the host asks for

The module **declares** permissions but never **requests** them; asking is a UI decision.

| Permission | When | If refused |
|---|---|---|
| `POST_NOTIFICATIONS` | Android 13+ | Downloads still run; no notification is shown |
| `WRITE_EXTERNAL_STORAGE` | Android 9 and 10 only | `download` refuses with `DownloadException.StorageNotWritable`; ask `storageRefusalOrNull()` first (§6.2) |

Ask for them before the first download, e.g. when the user taps "Download".

---

## 3. Initializing

Call `initialize` **once, in `Application.onCreate`**. That's not optional: WorkManager can start a
download in a fresh process (after a reboot, or after Android killed the app) before any screen
exists, and the download worker needs the module to be initialized.

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

`initialize` is cheap and safe on the main thread. It only creates the module. The database,
WorkManager, the network client and the device check are built on a background thread straight
after, and unfinished downloads are handed back to WorkManager.

Calling it again does nothing: **the first call's config is kept**. It throws
`IllegalArgumentException` if the config cannot work (see below).

### 3.1 `MediaDownloaderConfig`

```kotlin
data class MediaDownloaderConfig(
    val strictSupportedSitesOnly: Boolean = true,
    val extraBlockedHosts: Set<String> = emptySet(),
    val twitterApiKey: String? = null,
    val downloadFolderName: String = "All Video Downloader",
    @DrawableRes val notificationIcon: Int = android.R.drawable.stat_sys_download,
    val performanceMode: PerformanceMode = PerformanceMode.Auto,
    val notificationActivity: Class<out Activity>? = null,
    val allowYouTube: Boolean = false,
    val allowAdultSites: Boolean = false,
)
```

| Field | Default | Meaning |
|---|---|---|
| `strictSupportedSitesOnly` | `true` | `true`: downloads are offered only on the built-in supported sites (§9.3). `false`: on any site where media is found. Blocked sites are refused **either way**. |
| `extraBlockedHosts` | empty | Hosts to refuse **in addition to** the built-in block list, e.g. `setOf("example.com")` (subdomains included). Always refused; the allow switches below never lift them. |
| `twitterApiKey` | `null` | The tweeload api key for X/Twitter links. Without it X links fail with `LinkNotRecognised`. Keep it out of source control (§8.9). |
| `downloadFolderName` | `"All Video Downloader"` | The folder under the public `Download/` directory. **Must be one folder name**, not blank, no `/`, or `initialize` throws. |
| `notificationIcon` | system download icon | Small icon of the download notifications; use a monochrome drawable. **Must not be 0**, or `initialize` throws. |
| `performanceMode` | `Auto` | How hard the module may work the device (§3.2). |
| `notificationActivity` | `null` | The screen a notification tap opens, with `MediaDownloader.EXTRA_DOWNLOAD_ID`. When `null`, the launcher activity is used. Set it when your launcher is a splash screen that would not pass the extra on. |
| `allowYouTube` | `false` | Offer downloads from YouTube (its pages and player hosts). **Google Play removes apps that download from YouTube: keep `false` in any build published there.** See §9.3. |
| `allowAdultSites` | `false` | Offer downloads from adult sites (the built-in list, their mirrors, hosts named after them). **Play's sexual-content policy doesn't allow this: keep `false` in any build published there.** See §9.3. |

`toString()` never prints the api key; it shows `twitterApiKey=set` or `none`.

**A Play Store build and a build for elsewhere.** Keep the switches out of remote config; set them
per build, so the Play build cannot turn them on. For example, with a product flavor:

```kotlin
// app/build.gradle.kts
android {
    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "ALLOW_YOUTUBE", "false")
            buildConfigField("boolean", "ALLOW_ADULT_SITES", "false")
        }
        create("direct") {
            dimension = "store"
            buildConfigField("boolean", "ALLOW_YOUTUBE", "true")
            buildConfigField("boolean", "ALLOW_ADULT_SITES", "true")
        }
    }
}
```

```kotlin
MediaDownloaderConfig(
    allowYouTube = BuildConfig.ALLOW_YOUTUBE,
    allowAdultSites = BuildConfig.ALLOW_ADULT_SITES,
)
```

### 3.2 `PerformanceMode`

```kotlin
enum class PerformanceMode { Auto, Standard, LowEnd }
```

| Value | Behaviour |
|---|---|
| `Auto` | Low-end treatment when Android reports a low-RAM device, the app heap is ≤ 128 MB, total memory ≤ 3 GB, or the CPU has ≤ 4 cores; full speed otherwise. |
| `Standard` | Full parallelism (8 parts per file, 8 stream pieces), normal page-scan pace, whatever the device. |
| `LowEnd` | Half the parallelism, slower page scans, smaller response caps, no button animation, whatever the device. |

---

## 4. `MediaDownloader` — the entry point

```kotlin
object MediaDownloader
```

Every member is **safe to call from any thread**. Suspend functions switch to a background
thread themselves, so calling them from `lifecycleScope` / `viewModelScope` on the main thread is
fine. Before `initialize`:
- functions returning `Result` fail with `MediaDownloaderNotInitializedException`;
- the others throw it.

### 4.1 Constants and state

| Member | Type | Description |
|---|---|---|
| `EXTRA_DOWNLOAD_ID` | `const val String` | Intent extra (a `Long`) on the intent a download notification opens the app with. Value: `"com.markhoor.mediadownloader.DOWNLOAD_ID"`. |
| `isInitialized` | `Boolean` | Whether `initialize` has run. |
| `initialize(context, config)` | `Unit` | See §3. |

### 4.2 Links

```kotlin
fun siteAccess(url: String): SiteAccess
fun isAllowed(url: String): Boolean
suspend fun parse(text: String): Result<MediaModel>
```

| Function | Returns | Notes |
|---|---|---|
| `siteAccess(url)` | `Allowed`, `Blocked` or `Unsupported` | Whether downloads may be offered for that url's site under the current config. Cheap; fine on the main thread. |
| `isAllowed(url)` | `Boolean` | Shorthand for `siteAccess(url) == SiteAccess.Allowed`. Use it to decide whether to show a "download" affordance for a link. |
| `parse(text)` | `Result<MediaModel>` | Reads the media behind a link. `text` may be a bare url or text containing one (a share from another app). On failure the exception is always a `MediaParseException` (§6.1). Every quality is labelled and, where possible, sized. |

Sites the parser reads directly (paste or share a link): **Facebook, Instagram, Threads, TikTok,
X/Twitter (needs the key), Pinterest (incl. `pin.it`), Dailymotion, LinkedIn**. Other supported
sites are handled by the browser (§7.3).

### 4.3 Downloads

```kotlin
suspend fun download(request: DownloadRequest): Result<Long>
suspend fun download(
    media: MediaModel,
    quality: MediaQualityModel,
    fileName: String? = null,
    siteFolder: String? = null,
): Result<Long>

fun observeDownloads(): Flow<List<DownloadModel>>
fun observeDownload(id: Long): Flow<DownloadModel?>

suspend fun pause(id: Long): Result<Unit>
suspend fun resume(id: Long): Result<Unit>
suspend fun delete(id: Long, deleteFile: Boolean = false): Result<Unit>
```

| Function | Returns | Behaviour and failures |
|---|---|---|
| `download(request)` | the new download's **id** | Starts it in the background with a progress notification. Fails with `DownloadException.SiteBlocked` if the page, the media or the sound url is on a blocked site, and with `DownloadException.InvalidMediaUrl` if the url is not http(s). An unsupported (but not blocked) site is **not** refused here: whether to offer a download was decided earlier. |
| `download(media, quality, fileName, siteFolder)` | id | Convenience for one of a `MediaModel`'s qualities. `fileName`: the name without extension (the title when `null`). `siteFolder`: see `DownloadRequest.siteFolder`. |
| `observeDownloads()` | `Flow<List<DownloadModel>>` | Every download, **newest first**, re-emitted on every change (progress about once a second while running). Only emits when something actually changed. |
| `observeDownload(id)` | `Flow<DownloadModel?>` | One download; emits `null` once it is deleted. |
| `pause(id)` | `Result<Unit>` | Pauses a `Queued`, `Downloading` or `WaitingForNetwork` download. Parts already fetched are kept. Fails with `NotFound`, or `InvalidState` if it is already paused, completed or failed. |
| `resume(id)` | `Result<Unit>` | Carries on a `Paused` download, retries a `Failed` one from where it stopped, or retries a `WaitingForNetwork` one now. Fails with `NotFound` or `InvalidState` (e.g. a completed one). |
| `delete(id, deleteFile)` | `Result<Unit>` | Stops and removes the download and its temporary files. With `deleteFile = true`, a **completed** download's file is deleted too. Fails with `NotFound`. |

The observe functions throw `MediaDownloaderNotInitializedException` when called before
`initialize`. They open the database on a background thread when collected, so creating a
ViewModel that holds one on the main thread is fine.

Concurrent calls are safe: when two callers pause/resume/delete the same download at once, exactly
one wins and the other gets `InvalidState` or `NotFound`.

---

## 5. Models

All public models are Kotlin `data class`es / `enum`s in `com.markhoor.mediadownloader.domain.models`.

### 5.1 `MediaModel` — media found behind a link or on a page

```kotlin
data class MediaModel(
    val title: String,
    val thumbnailUrl: String?,
    val qualities: List<MediaQualityModel>,
    val sourceUrl: String,
    val durationMillis: Long? = null,
)
```

| Field | Meaning |
|---|---|
| `title` | The post's own name, already cleaned: entities decoded, site suffixes (" \| Facebook") and page decoration removed. May be empty when the site gives none; show your own fallback. |
| `thumbnailUrl` | Artwork to show, when the site has any: an `https` image or a small `data:image/` url. Load it with Glide/Coil as usual. |
| `qualities` | Every downloadable version, **in the order to list them** (best first for streams). |
| `sourceUrl` | The page or link it was found on. |
| `durationMillis` | Running time, when the site says. |

### 5.2 `MediaQualityModel` — one downloadable version

```kotlin
data class MediaQualityModel(
    val url: String,
    val label: String,
    val type: MediaType,
    val sizeBytes: Long? = null,
    val audioUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
)
```

| Field | Meaning |
|---|---|
| `url` | The file or HLS playlist. Opaque to the host: pass it back in a download, don't open it yourself. |
| `label` | What to call it: `1080p`, `720p`, `HD`, `SD`, `Watermark`, `Image`, `Media_2` (carousel item), `Page 2`… |
| `type` | `Video`, `Image` or `Audio`. |
| `sizeBytes` | Size when known. For a stream it is an estimate (show it as `~12 MB`); `null` when unknown. |
| `audioUrl` | Separate sound for a stream that keeps it apart; the download merges it. |
| `headers` | Request headers the download needs (`Referer`, `User-Agent`, cookies). Keep them with the quality; `download(media, quality)` passes them on. |

### 5.3 `MediaType`

```kotlin
enum class MediaType { Video, Image, Audio }
```

### 5.4 `DownloadRequest` — what to download

Use `MediaDownloader.download(media, quality)` in most cases. Build a request directly when the
media did not come from the module (your own content, e.g. Shorts) or to add a download paused.

```kotlin
data class DownloadRequest(
    val mediaUrl: String,
    val type: MediaType,
    val title: String,
    val sourceUrl: String,
    val thumbnailUrl: String? = null,
    val qualityLabel: String = "",
    val audioUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val fileName: String? = null,
    val startPaused: Boolean = false,
    val siteFolder: String? = null,
    val expectedSizeBytes: Long? = null,
) {
    companion object {
        fun of(media: MediaModel, quality: MediaQualityModel, fileName: String? = null, siteFolder: String? = null): DownloadRequest
    }
}
```

| Field | Meaning |
|---|---|
| `mediaUrl` | The file or HLS playlist (`http`/`https`). Whether it is a stream is detected; a "file" that turns out to be a playlist is downloaded as a stream. |
| `type` | Picks the extension when the url has none; the final extension is corrected from the file's first bytes. |
| `title` | Shown in the notification and the model; stored up to 1,000 characters. |
| `sourceUrl` | The page; it picks the site folder and is checked against the block list. |
| `thumbnailUrl` | Kept only if it is an image (an `.m3u8`/video url is dropped). |
| `qualityLabel` | Stored for display. |
| `audioUrl`, `headers` | As in `MediaQualityModel`. |
| `fileName` | The name without extension; the title when `null`. Unsafe characters are replaced, it is cut to 50 characters, a trailing media extension is dropped, and a free name is picked (`name (1).mp4`). |
| `startPaused` | Add it as `Paused`; start it with `resume`. Used to carry downloads over from an older app version. |
| `siteFolder` | A folder under `Websites/` to save in, overriding the one picked from `sourceUrl`. One folder name, never a path. |
| `expectedSizeBytes` | The size the user was shown before downloading (for a stream, an estimate). Progress starts from it, so the download shows the same size as the quality list until the real size is known. `DownloadRequest.of` / `download(media, quality)` fill it from `quality.sizeBytes`. |

### 5.5 `DownloadModel` — one download, as the host shows it

```kotlin
data class DownloadModel(
    val id: Long,
    val title: String,
    val fileName: String,
    val filePath: String,
    val sourceUrl: String,
    val thumbnailUrl: String?,
    val type: MediaType,
    val qualityLabel: String,
    val state: DownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val errorMessage: String?,
    val createdAtMillis: Long,
    val isMediaGone: Boolean,
) {
    val progressPercent: Int?   // 0–100, or null while the size is unknown
}
```

| Field | Meaning |
|---|---|
| `id` | Stable id; use it for `pause/resume/delete/observeDownload`. |
| `fileName`, `filePath` | Where the file is, or will be once complete. The name can change at completion (extension corrected, or a free name taken), so read it again from the completed model. |
| `downloadedBytes`, `totalBytes` | Progress. `totalBytes` is `null` when unknown; for a stream it is an estimate that firms up. |
| `errorMessage` | Why the last attempt failed, while `Failed` or retrying. Technical text; show your own message. |
| `isMediaGone` | The download failed because the media was never there to take - a private or deleted post, a link already spent - rather than because something went wrong on the way. Say so instead of offering a retry: trying again is told the same thing. |
| `progressPercent` | Convenience: 0–100 or `null`. |

### 5.6 `DownloadState`

```kotlin
enum class DownloadState { Queued, Downloading, Paused, WaitingForNetwork, Completed, Failed;
    val isActive: Boolean }
```

| State | Meaning | Allowed actions |
|---|---|---|
| `Queued` | Waiting for its turn or for the system to run it | pause, delete |
| `Downloading` | Running | pause, delete |
| `WaitingForNetwork` | Lost its connection; continues by itself when one is back | pause, resume (retry now), delete |
| `Paused` | Paused by the user (or imported paused) | resume, delete |
| `Completed` | File published | delete (optionally with the file) |
| `Failed` | Gave up after 5 failures, or the media can never be saved (protected stream) | resume (retry), delete |

`isActive` is `true` for `Queued`, `Downloading` and `WaitingForNetwork`.

### 5.7 `SiteAccess`

```kotlin
enum class SiteAccess { Allowed, Blocked, Unsupported }
```

| Value | Meaning |
|---|---|
| `Allowed` | Downloads may be offered. |
| `Blocked` | YouTube or an adult site the config does not allow (both refused by default), or one of `extraBlockedHosts` (always). |
| `Unsupported` | Not on the supported list while `strictSupportedSitesOnly` is on, or not a web page. |

---

## 6. Errors

Every failure comes back as `Result.failure(...)` with one of these, so a `when` covers all cases.

### 6.1 `MediaParseException` (from `parse` and `LinkParseViewModel`)

```kotlin
sealed class MediaParseException(val url: String, message: String, cause: Throwable?) : Exception
```

| Subclass | When | Suggested host reaction |
|---|---|---|
| `SiteBlocked` | YouTube or an adult site not allowed by the config, or an extra blocked host | "Downloads aren't allowed from this site" |
| `SiteUnsupported` | Not a supported site in strict mode, or not a web link | Open it in the browser, or "Not supported" |
| `LinkNotRecognised` | An allowed site, but not a post (a feed, a profile, a home page), or X without a key | Open it in the browser, where the page can be scanned |
| `MediaNotFound` | Understood, but no media could be read (private, deleted, site changed); `cause` has the detail | "Couldn't find media", or open in the browser |

### 6.2 `DownloadException` (from `download`, `pause`, `resume`, `delete`)

```kotlin
sealed class DownloadException(message: String) : Exception
```

| Subclass | Properties | When |
|---|---|---|
| `SiteBlocked` | `url` | Page, media or sound url on a blocked site |
| `InvalidMediaUrl` | `url` | Not http(s), or absurdly long |
| `NotFound` | `id` | No such download (never made, or deleted) |
| `InvalidState` | `id`, `state` | The action doesn't apply in this state (e.g. resuming a completed download) |
| `StorageNotWritable` | `path`, `refusal` | The download folder can't be written. `refusal` says what to do: `PermissionNotGranted` (ask for `WRITE_EXTERNAL_STORAGE`), `LegacyStorageDisabled` (add `requestLegacyExternalStorage="true"`, §2.2), `StorageUnavailable` (nothing to ask for) |

`StorageNotWritable` is decided by **writing to** the folder, not by reading the permission - on
Android 10 a granted permission with legacy storage off still can't write there, and a permission
check would report everything fine and then fail. Ask `storageRefusalOrNull()` before offering a
download, so the user gets a permission prompt instead of a failed download:


**Only `PermissionNotGranted` is the user's to act on.** `LegacyStorageDisabled` means they
already granted the permission and the manifest is missing the flag - read from the system with
`Environment.isExternalStorageLegacy()`, not guessed - so a permission prompt there asks them for
something they have done and cannot fix. Tell them downloads aren't working and fix the manifest;
the module also writes the reason to the log under `MediaDownloader`. The exception's `message` is
written for you, not for them - never show it in the UI.
```kotlin
MediaDownloader.storageRefusalOrNull()?.let { refused ->
    when (refused.refusal) {
        StorageRefusal.PermissionNotGranted -> askForStoragePermission()
        StorageRefusal.LegacyStorageDisabled -> Log.e(TAG, refused.message.orEmpty()) // your bug; never a prompt
        StorageRefusal.StorageUnavailable -> toast("Storage isn't available")
    }
    return
}
```

Database or scheduler errors, which are rare, come back as their own exception type, also inside
`Result.failure`.

### 6.3 `MediaDownloaderNotInitializedException`

An `IllegalStateException` for any call made before `initialize`. Returned as `Result.failure`
by `Result` functions, thrown by the others.

---

## 7. ViewModels for screens

Three ready-made AndroidX ViewModels. Each is obtained through its `Factory` (the constructors are
internal). Creating one on the main thread builds nothing heavy: dependencies are built on a
background thread on first use.

### 7.1 `LinkParseViewModel` — a paste/share screen

```kotlin
class LinkParseViewModel : ViewModel {
    val uiState: StateFlow<LinkParseUiState>
    fun parse(text: String)
    fun reset()
    companion object { val Factory: ViewModelProvider.Factory }
}

sealed interface LinkParseUiState {
    data object Idle
    data class Loading(val url: String)
    data class Success(val media: MediaModel)
    data class Failure(val error: MediaParseException)
}
```

- `parse(text)` starts reading; a newer call cancels an older one, so an old answer never replaces
  a newer one.
- `reset()` returns to `Idle` and drops any link still being read.

```kotlin
private val parseViewModel: LinkParseViewModel by viewModels { LinkParseViewModel.Factory }

lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        parseViewModel.uiState.collect { state ->
            when (state) {
                LinkParseUiState.Idle -> hideSheet()
                is LinkParseUiState.Loading -> showSpinner(state.url)
                is LinkParseUiState.Success -> showQualities(state.media)
                is LinkParseUiState.Failure -> when (state.error) {
                    is MediaParseException.SiteBlocked -> toast("Not allowed on this site")
                    else -> openInBrowser(state.error.url)
                }
            }
        }
    }
}
pasteButton.setOnClickListener { parseViewModel.parse(clipboardText) }
```

### 7.2 `DownloadsViewModel` — a downloads screen

```kotlin
class DownloadsViewModel : ViewModel {
    val uiState: StateFlow<DownloadsUiState>
    val events: Flow<DownloadsEvent>
    fun download(request: DownloadRequest)
    fun download(media: MediaModel, quality: MediaQualityModel, fileName: String? = null)
    fun pause(id: Long)
    fun resume(id: Long)
    fun delete(id: Long, deleteFile: Boolean = false)
    companion object { val Factory: ViewModelProvider.Factory }
}

sealed interface DownloadsUiState {
    data object Loading
    data class Content(val inProgress: List<DownloadModel>, val completed: List<DownloadModel>) {
        val isEmpty: Boolean
    }
}

sealed interface DownloadsEvent {
    data class Started(val id: Long)
    data class NotStarted(val error: Throwable)            // usually a DownloadException
    data class ActionFailed(val id: Long, val error: Throwable)
}
```

- `inProgress` holds everything not completed (running, waiting, paused, failed), newest first.
  `completed` holds finished downloads, newest first.
- A successful action shows up in `uiState` by itself; only failures come as `events`.
- `uiState` keeps collecting for 5 s after the screen stops, so a rotation doesn't restart it.
- `events` is a one-shot stream: **collect it once**, from the screen.

### 7.3 `BrowserViewModel` + `MediaBrowser` — a browser screen

```kotlin
class BrowserViewModel : ViewModel {
    val uiState: StateFlow<BrowserUiState>
    val events: Flow<BrowserEvent>
    @MainThread fun attach(webView: WebView, lifecycleOwner: LifecycleOwner,
                           webViewClient: WebViewClient? = null, webChromeClient: WebChromeClient? = null): MediaBrowser
    @MainThread fun createBrowser(context: Context, lifecycleOwner: LifecycleOwner,
                                  webViewClient: WebViewClient? = null, webChromeClient: WebChromeClient? = null): MediaBrowser
    fun onDownloadButtonClick()
    companion object { val Factory: ViewModelProvider.Factory }
}
```

| Member | Description |
|---|---|
| `attach(webView, …)` | Drives **your own** WebView (from your layout). The module installs its own `WebViewClient`/`WebChromeClient`, forwards **every** callback to the ones you pass, and turns on JavaScript and DOM storage. **Don't call `webView.webViewClient = …` yourself afterwards**; pass your clients here instead. A browser attached before is detached first. |
| `createBrowser(context, …)` | The module creates the WebView; add `MediaBrowser.webView` to your layout. It's destroyed when `lifecycleOwner` is. |
| `onDownloadButtonClick()` | Your floating download button was pressed; the page's media is shown when there is any. Same as `MediaBrowser.onDownloadButtonClick()`. |
| `uiState` | Page and media state, below. |
| `events` | One-shot moments, below. **Collect once**, from the screen. |

What was found survives a configuration change; the WebView doesn't belong to the ViewModel.
With `attach`, the browser detaches when `lifecycleOwner` is destroyed. For a fragment, pass
`viewLifecycleOwner`.

**`MediaBrowser`**: the handle `attach`/`createBrowser` return. Every method is main-thread only,
and after `detach()` the actions do nothing and return `false`.

| Member | Description |
|---|---|
| `webView: WebView` | The WebView it drives. |
| `isAttached: Boolean` | Whether it still drives it. |
| `canGoBack`, `canGoForward` | Navigation state. |
| `load(url): Boolean` | Opens an http(s) url; `false` for anything else (encode search text first). |
| `reload()`, `stopLoading()`, `goBack()`, `goForward()` | Return `false` when not possible or detached. `goBack()` returning `false` means "no page to go back to": close the browser. |
| `onDownloadButtonClick()` | As above. |
| `detach()` | Lets go of the WebView: your own clients are put back, the script bridge is removed, and a module-created WebView is destroyed. Safe to call more than once. |

**`BrowserUiState`**:

```kotlin
data class BrowserUiState(
    val url: String, val title: String, val progress: Int,
    val canGoBack: Boolean, val canGoForward: Boolean,
    val siteAccess: SiteAccess,
    val media: PageMediaState,
    val showDownloadButton: Boolean,
) { val isLoading: Boolean }   // progress in 1..99

sealed interface PageMediaState {
    data object None                                        // nothing found or asked for
    data object Searching                                   // the user asked; still looking
    data class Found(val media: MediaModel, val isDescribing: Boolean)
}
```

- `showDownloadButton`: **your** floating button belongs on screen now. There is media, the site
  allows it, and no in-page button already sits on the media. Bind your button's visibility to it.
- `Found.isDescribing`: name, qualities or sizes are still arriving. What's shown can already be
  downloaded; update the sheet as the state changes.

**`BrowserEvent`**:

| Event | What the host does |
|---|---|
| `ShowMedia` | Open your download sheet and fill it from `uiState.media`. The user tapped your button or an in-page button. |
| `HideMedia` | Close the sheet: what was looked for couldn't be read. |
| `NothingFound` | Nothing downloadable turned up in time (25 s). Say so **if the sheet is still open**, and close it. |
| `BrowserCrashed` | Android killed the WebView's renderer (usually for memory). That WebView can't be used again: remove it and attach or create a new one. The app didn't crash. |

Buttons on the page: on most sites the module draws its own small download button on each video
or picture. Those taps arrive as `ShowMedia` too, so the host handles every tap the same way.

---

## 8. Recipes

### 8.1 Paste or share a link → pick a quality → download

```kotlin
lifecycleScope.launch {
    MediaDownloader.parse(sharedText)
        .onSuccess { media ->
            showSheet(media) { chosen: MediaQualityModel, renamed: String? ->
                lifecycleScope.launch {
                    MediaDownloader.download(media, chosen, fileName = renamed)
                        .onSuccess { id -> watchProgress(id) }
                        .onFailure { toast(it.message ?: "Could not start") }
                }
            }
        }
        .onFailure { error ->
            when (error) {
                is MediaParseException.SiteBlocked -> toast("Downloads aren't allowed from this site")
                else -> openInBrowser(sharedText)          // let the browser scan the page
            }
        }
}
```

Show `quality.label` plus the size (`~` prefix when it's an estimate for a stream), and
`media.title` / `media.thumbnailUrl` in the header.

### 8.2 A browser screen

```kotlin
class BrowserFragment : Fragment(R.layout.fragment_browser) {
    private val browserViewModel: BrowserViewModel by viewModels { BrowserViewModel.Factory }
    private var browser: MediaBrowser? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        browser = browserViewModel.attach(
            webView = binding.webView,
            lifecycleOwner = viewLifecycleOwner,
            webViewClient = object : WebViewClient() {        // your own callbacks, forwarded
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    history.record(url, view.title)
                }
            },
        )
        browser?.load("https://www.dailymotion.com")

        binding.downloadFab.setOnClickListener { browser?.onDownloadButtonClick() }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    browserViewModel.uiState.collect { state ->
                        binding.address.setText(state.url)
                        binding.progress.isVisible = state.isLoading
                        binding.downloadFab.isVisible = state.showDownloadButton
                    }
                }
                launch {
                    browserViewModel.events.collect { event ->
                        when (event) {
                            BrowserEvent.ShowMedia -> openSheet(browserViewModel.uiState)   // render PageMediaState
                            BrowserEvent.HideMedia -> closeSheet()
                            BrowserEvent.NothingFound -> if (sheetIsOpen) { toast("Nothing to download here"); closeSheet() }
                            BrowserEvent.BrowserCrashed -> requireActivity().recreate()
                        }
                    }
                }
            }
        }
    }

    fun onBackPressed() { if (browser?.goBack() != true) closeBrowser() }
}
```

Typed text is not a url. Build a search url with the text **URL-encoded**
(`URLEncoder.encode(text, "UTF-8")`); `load` refuses anything with spaces.

### 8.3 A progress list

```kotlin
MediaDownloader.observeDownloads()
    .map { all -> all.filter { it.state != DownloadState.Completed } }
    .collect { rows -> adapter.submitList(rows) }

// row actions
fun onPlayPause(row: DownloadModel) = lifecycleScope.launch {
    val result = if (row.state.isActive) MediaDownloader.pause(row.id) else MediaDownloader.resume(row.id)
    result.onFailure { toast(it.message.orEmpty()) }
}
fun onDelete(row: DownloadModel) = lifecycleScope.launch { MediaDownloader.delete(row.id) }
```

Render `row.progressPercent` (indeterminate when `null`), `row.downloadedBytes / row.totalBytes`,
and the state. The module retries by itself: a `WaitingForNetwork` row continues when the
connection is back.

### 8.4 Following one download (a progress sheet)

```kotlin
lifecycleScope.launch {
    MediaDownloader.observeDownload(id).filterNotNull().collectLatest { d ->
        when (d.state) {
            DownloadState.Completed -> showDone(d.filePath)
            DownloadState.Failed -> showFailed()
            DownloadState.WaitingForNetwork -> showNoInternet()
            else -> showProgress(d.progressPercent, d.downloadedBytes, d.totalBytes)
        }
    }
}
```

### 8.5 Handling a notification tap

The notification opens `notificationActivity` (or the launcher) with the download's id:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); handle(intent) }
override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

private fun handle(intent: Intent?) {
    val id = intent?.getLongExtra(MediaDownloader.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
    if (id == -1L) return
    intent?.removeExtra(MediaDownloader.EXTRA_DOWNLOAD_ID)          // not handled twice on rotation
    lifecycleScope.launch {
        val download = MediaDownloader.observeDownload(id).first() ?: return@launch
        if (download.state == DownloadState.Completed) openFile(download.filePath) else openProgressTab()
    }
}
```

The intent uses `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`, so a running activity gets
`onNewIntent`.

### 8.6 Showing a download button only where it's allowed

```kotlin
if (MediaDownloader.isAllowed(copiedText)) showCopiedLinkPrompt(copiedText)
```

### 8.7 Your own media (e.g. an in-app Shorts feed)

```kotlin
MediaDownloader.download(
    DownloadRequest(
        mediaUrl = short.videoUrl,
        type = MediaType.Video,
        title = short.title,
        sourceUrl = short.videoUrl,
        siteFolder = "Other",          // saved in Download/<root>/Websites/Other/
    ),
)
```

### 8.8 Carrying downloads over from an older app version

Add each unfinished old download **paused**, so nothing starts using data unasked (old signed
links may also have expired):

```kotlin
MediaDownloader.download(
    DownloadRequest(mediaUrl = old.url, type = MediaType.Video, title = old.title,
                    sourceUrl = old.page, startPaused = true),
)
```

The user resumes them from the progress list.

### 8.9 Supplying the X/Twitter key without committing it

`local.properties` (git-ignored):

```properties
tweeload.apiKey=YOUR_KEY
```

App `build.gradle.kts`:

```kotlin
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
android {
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("String", "TWEELOAD_API_KEY", "\"${localProps.getProperty("tweeload.apiKey", "")}\"")
    }
}
```

Then `twitterApiKey = BuildConfig.TWEELOAD_API_KEY.ifBlank { null }`.

### 8.10 Analytics from outside the module

```kotlin
val last = mutableMapOf<Long, DownloadState>()
MediaDownloader.observeDownloads().collect { all ->
    all.forEach { d ->
        val before = last.put(d.id, d.state)
        if (before != null && before != d.state) when (d.state) {
            DownloadState.Completed -> analytics.log("download_completed")
            DownloadState.Failed -> analytics.log("download_failed")
            else -> Unit
        }
    }
}
```

Run it from an application-scoped coroutine on `Dispatchers.IO`.

---

## 9. Behaviour the host should know

### 9.1 Where files go

```
Download/<downloadFolderName>/Websites/<site folder>/<name>.<ext>
```

- **Site folder** from the page's host: `TikTok`, `Facebook`, `Twitter X`, `Instagram`,
  `Daily Motion`, `Threads`; any other site → `Website`; or `DownloadRequest.siteFolder`.
- **Name** from `fileName` or the title: made safe, cut to 50 characters, and made unique
  (`name (1).mp4`).
- **Extension** from the file's first bytes (a `.png` link that serves a JPEG is saved `.jpg`).
- A file is written under a hidden temporary name next to its final place and **renamed when
  complete**, so galleries never list a half file. It is then announced to the media scanner, so
  galleries and players list it.
- Scratch parts live in the app's private `files/media_downloader/<id>/` and are deleted when the
  download completes or is deleted.

### 9.2 Notifications

- Channel `media_downloader_downloads`, named "Downloads", low importance (silent).
- Running: progress (or "Preparing download…"). Afterwards: "Download complete", "Download
  failed", "This video is private or no longer available", or "Waiting for a connection". A tap
  opens the host (§8.5).
- "This video is private or no longer available" is shown instead of "Download failed" when the
  media was never there to take - the site answered with a page or a placeholder picture, or
  refused the request (any 4xx but 408 and 429). Those **fail at once**, without the four further
  attempts a retryable failure gets, because asking again is told the same thing.
- Without notification permission nothing is shown and downloads still run.
- Texts are resources the host can override (§10).

### 9.3 Site policy

- **Blocked by default:** YouTube (its pages and player hosts such as `googlevideo.com`) and a
  built-in list of adult sites (including numbered mirrors and adult words in the domain). Each
  category is lifted by its own switch, `allowYouTube` / `allowAdultSites`, and by nothing else.
- **Blocked, always:** `extraBlockedHosts`.
- A blocked site gets no page script, no sniffing, no button, no parse, and no download, whatever
  the path (paste, share, browser, or a direct `download` call with a blocked page, media or sound
  url). One switch changes all of those paths at once.
- **An allowed category counts as supported** in strict mode, so `allowYouTube = true` works there
  too. Other unlisted sites still need open mode.
- What an allowed site gets is detection, not a site parser: the module has no YouTube or adult-site
  scrapers, so those sites work through the browser (page scripts and request sniffing), and a
  pasted link fails with `LinkNotRecognised`. YouTube serves most of its video through its own
  streaming protocol rather than plain files, so expect few YouTube videos to be downloadable even
  when allowed.
- **Strict mode** (`strictSupportedSitesOnly = true`): downloads are offered only on the supported
  list: about 100 sites, including Facebook, Instagram, X, TikTok, Threads, Pinterest, LinkedIn,
  Reddit, Tumblr, Dailymotion, Vimeo, Rumble, Odysee, Bitchute, Twitch, TED, IMDb, Streamable,
  archive.org, Imgur, Giphy, Pexels, 9GAG, major news, sports and education sites. The exact list
  is `Constants.Hosts.SUPPORTED`. Other sites still **browse** normally; they just get no button.
- **Open mode** (`false`): any non-blocked site where media is found.
- Hosts are matched as hosts, never substrings: `x.com` is X, `sex.com` is not.
- A link that carries a blocked site inside it (e.g. `…?ref=https://<blocked site>/…`) is blocked too.

### 9.4 Downloads in the background

- One WorkManager job per download, constrained to a network connection. **Nothing to configure**
  if the host uses WorkManager's default setup.
- Lost connection → `WaitingForNetwork`, then continues as soon as it's back, and the
  notification says "Waiting for a connection" rather than disappearing. Started while already
  offline, it says the same thing straight away instead of showing nothing until the connection
  returns. Either way the module clears it the moment the download runs.
- Other failures are retried with a growing pause; after 5 the download is `Failed` and can be
  resumed by the user. **Media that was never there to take is not retried at all** - a body that
  is a page or a placeholder picture, or a refusal from the server (any 4xx but 408 and 429) -
  because asking again is told the same thing. Those come back as `isMediaGone` (§5.5).
- Streams: the best encode of a master playlist, separate sound merged, byte-range and AES-128
  streams supported, remuxed into MP4. SAMPLE-AES/DRM streams fail at once with a clear message.
- Resume picks up from the bytes/parts already on disk, including after the app was killed or the
  phone restarted.
- If the app is force-stopped, Android cancels its jobs; the download continues at the next launch.

### 9.5 WorkManager and custom factories

If the host sets its own `Configuration.Provider` with a custom `WorkerFactory`, that factory must
**return `null` for worker classes it doesn't know**, so WorkManager builds the module's
`DownloadWorker` itself. Alternatively use `DelegatingWorkerFactory`. A factory that answers for
every class name breaks downloads.

### 9.6 Threads, memory and crashes

- No module call blocks the main thread; `initialize` and the ViewModel factories do no disk or
  network work there.
- Failures inside background work are logged under the tag `MediaDownloader`, never thrown into
  the host.
- Page and api responses are streamed and capped. Titles, urls and headers coming from pages are
  length-limited. On memory pressure (`onTrimMemory`) cached page scripts are released.
- A killed WebView renderer is reported as `BrowserEvent.BrowserCrashed` instead of crashing the app.

---

## 10. Customizing

| What | How |
|---|---|
| Strict/open mode, extra blocked hosts, allowing YouTube or adult sites, X key, folder name, notification icon, performance, notification target | `MediaDownloaderConfig` (§3.1) |
| Per-download folder | `DownloadRequest.siteFolder` / `download(…, siteFolder = …)` |
| Notification texts | Override these string resources in the host's `res/values*/strings.xml` (same names win, and translations work): `media_downloader_channel_name`, `media_downloader_channel_description`, `media_downloader_downloading`, `media_downloader_preparing`, `media_downloader_waiting_for_network`, `media_downloader_completed`, `media_downloader_failed`, `media_downloader_progress` (`%1$s of %2$s`), `media_downloader_untitled` |
| Your own floating button | Bind to `BrowserUiState.showDownloadButton`, call `onDownloadButtonClick()` |
| Your own WebView callbacks | Pass them to `attach`/`createBrowser`; they receive every callback |

Not customizable at runtime: the built-in block list itself (only lifted per category by the two
switches, or extended with `extraBlockedHosts`), and the supported list (change
`Constants.Hosts.SUPPORTED` in the module).

---

## 11. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `MediaDownloaderNotInitializedException` | A call before `initialize` | Call `initialize` in `Application.onCreate` (not an Activity) |
| Downloads never start after a reboot | `initialize` not in `Application.onCreate`; the worker fails with that message in logcat | Move it there |
| Downloads fail on Android 10 | No `requestLegacyExternalStorage`, or storage permission refused. `download` now says which, in `StorageNotWritable.refusal` | §2.2, §2.3, §6.2 |
| No notifications | `POST_NOTIFICATIONS` not granted (Android 13+) | Ask for it; downloads run regardless |
| Notification tap opens the splash and the id is lost | Launcher is a splash screen | Set `notificationActivity` |
| X links fail with `LinkNotRecognised` | No `twitterApiKey` | §8.9 |
| A site shows no button | Unsupported in strict mode, blocked, or the video hasn't started. Some players only request their file on play | Check `siteAccess(url)`; press play |
| My `WebViewClient` stopped getting callbacks | Set on the WebView after `attach` | Pass it to `attach` instead |
| Typed search does nothing in the browser | Text with spaces passed to `load` | URL-encode it into a search url (§8.2) |
| Downloads built by the wrong worker / crash in a factory | A custom `WorkerFactory` answering every class | §9.5 |
| Logs | Failures the module cannot return are logged under tag `MediaDownloader` | `adb logcat -s MediaDownloader` |

---

## 12. Public API at a glance

Everything a host can reference. Anything not listed here is `internal` to the module.

**`com.markhoor.mediadownloader`**
- `object MediaDownloader`
  - `EXTRA_DOWNLOAD_ID`, `isInitialized`, `initialize(context, config)`
  - Links: `siteAccess(url)`, `isAllowed(url)`, `parse(text)`
  - Downloads: `download(request)`, `download(media, quality, fileName?, siteFolder?)`,
    `observeDownloads()`, `observeDownload(id)`, `pause(id)`, `resume(id)`, `delete(id, deleteFile)`
- `data class MediaDownloaderConfig`
- `enum class PerformanceMode { Auto, Standard, LowEnd }`

**`com.markhoor.mediadownloader.domain.models`**
- `MediaModel`, `MediaQualityModel`, `enum MediaType`
- `DownloadRequest` (+ `DownloadRequest.of`), `DownloadModel`, `enum DownloadState`
- `enum SiteAccess`
- `sealed MediaParseException`: `SiteBlocked`, `SiteUnsupported`, `LinkNotRecognised`, `MediaNotFound`
- `sealed DownloadException`: `SiteBlocked`, `InvalidMediaUrl`, `NotFound`, `InvalidState`, `StorageNotWritable`
- `enum StorageRefusal`: `PermissionNotGranted`, `LegacyStorageDisabled`, `StorageUnavailable`
- `MediaDownloaderNotInitializedException`

**`com.markhoor.mediadownloader.presentation.linkparse`**
- `LinkParseViewModel` (`Factory`, `uiState`, `parse`, `reset`)
- `sealed LinkParseUiState`: `Idle`, `Loading`, `Success`, `Failure`

**`com.markhoor.mediadownloader.presentation.downloads`**
- `DownloadsViewModel` (`Factory`, `uiState`, `events`, `download`, `pause`, `resume`, `delete`)
- `sealed DownloadsUiState`: `Loading`, `Content`
- `sealed DownloadsEvent`: `Started`, `NotStarted`, `ActionFailed`

**`com.markhoor.mediadownloader.presentation.browser`**
- `BrowserViewModel` (`Factory`, `uiState`, `events`, `attach`, `createBrowser`, `onDownloadButtonClick`)
- `MediaBrowser` (`webView`, `isAttached`, `canGoBack`, `canGoForward`, `load`, `reload`,
  `stopLoading`, `goBack`, `goForward`, `onDownloadButtonClick`, `detach`)
- `BrowserUiState`, `sealed PageMediaState`: `None`, `Searching`, `Found`
- `sealed BrowserEvent`: `ShowMedia`, `HideMedia`, `NothingFound`, `BrowserCrashed`

**Resources:** the `media_downloader_*` strings (§10).
**Manifest:** permissions and the foreground service type (§2.2), merged automatically.
