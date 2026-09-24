package com.media.downloader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.markhoor.mediadownloader.MediaDownloader
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.StorageRefusal
import com.markhoor.mediadownloader.presentation.browser.BrowserEvent
import com.markhoor.mediadownloader.presentation.browser.BrowserViewModel
import com.markhoor.mediadownloader.presentation.browser.MediaBrowser
import com.markhoor.mediadownloader.presentation.downloads.DownloadsEvent
import com.markhoor.mediadownloader.presentation.downloads.DownloadsViewModel
import com.markhoor.mediadownloader.presentation.linkparse.LinkParseViewModel
import com.media.downloader.ui.browser.BrowserScreen
import com.media.downloader.ui.common.downloadFailureMessage
import com.media.downloader.ui.common.storageRefusalMessage
import com.media.downloader.ui.downloads.DownloadsScreen
import com.media.downloader.ui.link.LinkScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "MediaDownloaderDemo"

enum class DemoTab(val label: String) { Link("Link"), Browser("Browser"), Downloads("Downloads") }

/**
 * Holds everything the three screens share: the ViewModels, the browser, the one download gate, and
 * the two event streams. Both `events` flows are buffered single-consumer channels, so they are
 * collected here - once - rather than in a screen that comes and goes.
 */
@Composable
fun DemoRoot(
    sharedLink: String?,
    notificationDownloadId: Long?,
    onIntentHandled: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableStateOf(DemoTab.Link) }

    val linkVm: LinkParseViewModel = viewModel(factory = LinkParseViewModel.Factory)
    val downloadsVm: DownloadsViewModel = viewModel(factory = DownloadsViewModel.Factory)
    val browserVm: BrowserViewModel = viewModel(factory = BrowserViewModel.Factory)

    // Made only when the browser tab is first opened, and kept across tab switches so the page,
    // its history and the module's detection session survive.
    var browser by remember { mutableStateOf<MediaBrowser?>(null) }
    var browserSheetOpen by remember { mutableStateOf(false) }

    val gate = rememberDownloadGate(scope, snackbar, downloadsVm) { tab = DemoTab.Downloads }

    LaunchedEffect(tab) {
        if (tab == DemoTab.Browser && browser == null) {
            browser = browserVm.createBrowser(context, lifecycleOwner).also {
                it.load("https://www.dailymotion.com")
            }
        }
    }

    LaunchedEffect(Unit) {
        downloadsVm.events.collect { event ->
            when (event) {
                is DownloadsEvent.Started -> snackbar.showSnackbar("Download started")
                is DownloadsEvent.NotStarted -> snackbar.showSnackbar(downloadFailureMessage(event.error))
                is DownloadsEvent.ActionFailed -> snackbar.showSnackbar(downloadFailureMessage(event.error))
            }
        }
    }

    LaunchedEffect(Unit) {
        browserVm.events.collect { event ->
            when (event) {
                BrowserEvent.ShowMedia -> {
                    tab = DemoTab.Browser
                    browserSheetOpen = true
                }

                BrowserEvent.HideMedia -> browserSheetOpen = false

                BrowserEvent.NothingFound -> {
                    browserSheetOpen = false
                    snackbar.showSnackbar("Nothing to download on this page")
                }

                // The WebView is dead and can never be used again. Dropping it here unparents it,
                // and the effect above makes a fresh one.
                BrowserEvent.BrowserCrashed -> {
                    browser = null
                    snackbar.showSnackbar("The browser ran out of memory and was restarted")
                }
            }
        }
    }

    LaunchedEffect(sharedLink) {
        sharedLink?.let {
            tab = DemoTab.Link
            linkVm.parse(it)
            onIntentHandled()
        }
    }
    LaunchedEffect(notificationDownloadId) {
        notificationDownloadId?.let {
            tab = DemoTab.Downloads
            onIntentHandled()
        }
    }

    BackHandler(enabled = tab != DemoTab.Link) {
        val wentBack = tab == DemoTab.Browser && browser?.goBack() == true
        if (!wentBack) tab = DemoTab.Link
    }

    Scaffold(
        // The whole scaffold sits above the keyboard, bottom bar included. Padding only the content
        // would leave the bar's reserved space stranded behind the keyboard as a dead band.
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                DemoTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {},
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                DemoTab.Link -> LinkScreen(
                    vm = linkVm,
                    onDownload = gate::download,
                    onOpenInBrowser = { url ->
                        tab = DemoTab.Browser
                        scope.launch { browser?.load(url) }
                    },
                )

                DemoTab.Browser -> {
                    val current = browser
                    if (current == null) {
                        Text("Starting the browser…", Modifier.padding(24.dp))
                    } else {
                        BrowserScreen(
                            browser = current,
                            vm = browserVm,
                            sheetOpen = browserSheetOpen,
                            onSheetDismiss = { browserSheetOpen = false },
                            onDownload = gate::download,
                        )
                    }
                }

                DemoTab.Downloads -> DownloadsScreen(downloadsVm)
            }
        }
    }
}

/** What both screens call. Nothing else starts a download. */
class DownloadGate internal constructor(private val start: (MediaModel, MediaQualityModel) -> Unit) {
    fun download(media: MediaModel, quality: MediaQualityModel) = start(media, quality)
}

/**
 * Asks for what it needs, in the order that makes sense to a person: notifications at the moment of
 * the first download (refusing only means a silent download), then the module's own storage check
 * **before** starting - which is decided by writing to the folder, not by reading a permission.
 */
@Composable
private fun rememberDownloadGate(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    downloadsVm: DownloadsViewModel,
    onStarted: () -> Unit,
): DownloadGate {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<MediaModel, MediaQualityModel>?>(null) }

    val notificationLauncher = rememberLauncherForActivityResult(RequestPermission()) { }
    val storageLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        val queued = pending
        pending = null
        scope.launch {
            if (granted && queued != null) {
                downloadsVm.download(queued.first, queued.second)
                onStarted()
            } else {
                snackbar.showSnackbar(storageRefusalMessage(StorageRefusal.PermissionNotGranted))
            }
        }
    }

    return remember(downloadsVm) {
        DownloadGate { media, quality ->
            scope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                when (MediaDownloader.storageRefusalOrNull()?.refusal) {
                    null -> {
                        downloadsVm.download(media, quality)
                        onStarted()
                    }

                    StorageRefusal.PermissionNotGranted -> {
                        pending = media to quality
                        storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }

                    // Our manifest is missing requestLegacyExternalStorage. The user already
                    // granted the permission and cannot fix this, so they are never asked again.
                    StorageRefusal.LegacyStorageDisabled -> {
                        Log.e(TAG, MediaDownloader.storageRefusalOrNull()?.message.orEmpty())
                        snackbar.showSnackbar(storageRefusalMessage(StorageRefusal.LegacyStorageDisabled))
                    }

                    StorageRefusal.StorageUnavailable ->
                        snackbar.showSnackbar(storageRefusalMessage(StorageRefusal.StorageUnavailable))
                }
            }
        }
    }
}
