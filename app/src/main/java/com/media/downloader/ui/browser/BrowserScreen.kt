package com.media.downloader.ui.browser

import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.presentation.browser.BrowserTabUiState
import com.markhoor.mediadownloader.presentation.browser.BrowserTabs
import com.markhoor.mediadownloader.presentation.browser.BrowserTabsUiState
import com.markhoor.mediadownloader.presentation.browser.BrowserViewModel
import com.markhoor.mediadownloader.presentation.browser.PageMediaState
import com.media.downloader.ui.common.QualityPickerSheet
import java.net.URLEncoder

/** The red the module's own in-page buttons use, so both read as the same affordance. */
private val DownloadButtonColor = Color(0xFFE53935)

/** Where a new tab starts. */
const val BrowserHome = "https://www.dailymotion.com"

/**
 * The module's browser in a Compose screen, with its tabs.
 *
 * The WebViews belong to the module: it builds them, configures them, attaches its own detecting
 * clients and the JavaScript bridge, and destroys them. **Never** set
 * `webViewClient`/`webChromeClient` on one here - that replaces the module's clients and silently
 * ends all media detection. Own callbacks go in the parameters of `tabs`/`createBrowser`/`attach`,
 * which forward everything; this demo needs none.
 *
 * `uiState` always describes the tab on screen, so this screen is written as though there were one
 * page - which is the point of [BrowserTabs.activeBrowser].
 */
@Composable
fun BrowserScreen(
    tabs: BrowserTabs,
    vm: BrowserViewModel,
    sheetOpen: Boolean,
    onSheetDismiss: () -> Unit,
    onDownload: (MediaModel, MediaQualityModel) -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val tabState by tabs.state.collectAsStateWithLifecycle()
    val browser by tabs.activeBrowser.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }
    var showGrid by rememberSaveable { mutableStateOf(false) }

    // Follow the page. Without this the field keeps whatever was typed last, so after following a
    // link - or switching tabs - it names a page you are not on, and Reload looks like it went
    // somewhere else. An empty tab empties it: there is no page to name.
    LaunchedEffect(state.url, tabState.activeId) {
        address = state.url
    }

    if (showGrid) {
        TabGrid(
            tabs = tabState,
            onSelect = {
                tabs.select(it)
                showGrid = false
            },
            onClose = { tabs.close(it) },
            onNew = {
                tabs.open()
                showGrid = false
            },
            onDone = { showGrid = false },
        )
    } else Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabStrip(
                tabs = tabState,
                onSelect = tabs::select,
                onClose = { tabs.close(it) },
                onNew = { tabs.open() },
                onRestore = { tabs.restoreLastClosed() },
                onGrid = {
                    // The card for the tab on screen is the one from when it was last left:
                    // take a fresh picture as the switcher opens.
                    tabs.capturePreview()
                    showGrid = true
                },
            )

            Row(
                // No imePadding here: it would pad *below* the address bar and push the page off
                // the screen. The whole content is lifted above the keyboard in DemoRoot instead.
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Text("Address or search") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val page = browser ?: return@TextButton
                    // load() refuses anything that isn't a url (a typed search has spaces), so
                    // whatever was typed is searched for instead.
                    if (!page.load(address)) {
                        page.load("https://www.google.com/search?q=" + URLEncoder.encode(address, "UTF-8"))
                    }
                }) { Text("Go") }
                TextButton(onClick = { browser?.reload() }) { Text("Reload") }
            }

            if (state.isLoading) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (state.media is PageMediaState.Searching) {
                // The press has been taken and the library is reading the page. It can take a few
                // seconds, and with nothing moving on screen the only reading is that the tap missed.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when (state.siteAccess) {
                                SiteAccess.Allowed -> "Downloadable"
                                SiteAccess.Unsupported -> "Not supported"
                                SiteAccess.Blocked -> "Blocked"
                            },
                        )
                    },
                )
                if (state.media is PageMediaState.Searching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Looking for media…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            val page = browser
            if (page == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("No tabs open.")
                    Button(onClick = { tabs.open() }) { Text("New tab") }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    // One container for every tab, and the selected tab's WebView put inside it.
                    // A node per browser instead - AndroidView keeps whichever view it was first
                    // given, so each tab needs its own - tears the whole thing down and builds it
                    // again on every switch, which is a frame of nothing on screen.
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context -> FrameLayout(context) },
                        update = { container ->
                            val web = page.webView
                            if (web.parent !== container) {
                                container.removeAllViews()
                                (web.parent as? ViewGroup)?.removeView(web)
                                container.addView(
                                    web,
                                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
                                )
                            }
                        },
                    )
                    // A WebView that has painted nothing draws a blank white rectangle - what a
                    // new tab, and a tab whose page is being put back, would otherwise open on.
                    // The module says when there is a page to show; until then this is the screen.
                    if (!state.isPageVisible) {
                        NewTabPage(isOpening = state.isLoading || tabState.active?.url?.isNotBlank() == true)
                    }
                }
            }
        }

        // Shown only where the page script did not already draw its own button on the media.
        // Deliberately loud: it floats over whatever the page is showing - a banner, a bright
        // advert - and in the theme's own colours it disappeared into a purple promo strip.
        if (state.showDownloadButton) {
            ExtendedFloatingActionButton(
                onClick = { vm.onDownloadButtonClick() },
                containerColor = DownloadButtonColor,
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Text("↓  Download", fontWeight = FontWeight.Bold)
            }
        }
    }

    val found = state.media as? PageMediaState.Found
    if (sheetOpen && found != null) {
        QualityPickerSheet(
            media = found.media,
            isDescribing = found.isDescribing,
            onPick = { quality ->
                onDownload(found.media, quality)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )
    }
}

/** What a tab shows before its page has drawn anything, in place of the WebView's blank white. */
@Composable
private fun NewTabPage(isOpening: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isOpening) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Text("Opening…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        } else {
            Text("New tab", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Type an address above, or search for something.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** One chip per tab, the way a phone browser's strip reads: name, what it is doing, and a cross. */
@Composable
private fun TabStrip(
    tabs: BrowserTabsUiState,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
    onRestore: () -> Unit,
    onGrid: () -> Unit,
) {
    // The chips scroll; the actions do not, or they are pushed off the edge by the third tab.
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.tabs.forEach { tab ->
                FilterChip(
                    selected = tab.isActive,
                    onClick = { onSelect(tab.id) },
                    label = {
                        Text(
                            text = tab.label.ifBlank { "New tab" }.take(20),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingIcon = if (tab.isLoading) {
                        { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                    trailingIcon = {
                        TextButton(onClick = { onClose(tab.id) }, contentPadding = PaddingValues(4.dp)) {
                            Text("✕", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                )
            }
            if (tabs.canRestoreClosed) {
                TextButton(onClick = onRestore) { Text("Undo close") }
            }
        }
        TextButton(onClick = onNew, enabled = tabs.canOpenMore, contentPadding = PaddingValues(6.dp)) {
            Text("＋")
        }
        TextButton(onClick = onGrid, contentPadding = PaddingValues(6.dp)) { Text("▦ ${tabs.count}") }
    }
}

/**
 * The tab switcher: a card per tab, each with the picture the module took of its page. A tab that
 * has never been drawn has none yet, which is why the card says so rather than showing nothing.
 */
@Composable
private fun TabGrid(
    tabs: BrowserTabsUiState,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onNew, enabled = tabs.canOpenMore) { Text("＋ New tab") }
            Text(
                text = "${tabs.count} open",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDone) { Text("Done") }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tabs.tabs, key = { it.id }) { tab ->
                TabCard(tab = tab, onSelect = onSelect, onClose = onClose)
            }
        }
    }
}

@Composable
private fun TabCard(tab: BrowserTabUiState, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(tab.id) },
        border = if (tab.isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tab.favicon?.let { icon ->
                Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(
                text = tab.label.ifBlank { "New tab" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            TextButton(onClick = { onClose(tab.id) }, contentPadding = PaddingValues(4.dp)) {
                Text("✕", style = MaterialTheme.typography.bodySmall)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val picture = tab.preview
            if (picture != null) {
                Image(
                    bitmap = picture.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            } else {
                Text(
                    text = "No preview yet",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
