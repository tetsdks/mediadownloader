package com.media.downloader.ui.browser

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.presentation.browser.BrowserViewModel
import com.markhoor.mediadownloader.presentation.browser.MediaBrowser
import com.markhoor.mediadownloader.presentation.browser.PageMediaState
import com.media.downloader.ui.common.QualityPickerSheet
import java.net.URLEncoder

/** The red the module's own in-page buttons use, so both read as the same affordance. */
private val DownloadButtonColor = Color(0xFFE53935)

/**
 * The module's browser in a Compose screen.
 *
 * The WebView belongs to the module: it configures it, attaches its own detecting clients and the
 * JavaScript bridge, and destroys it. **Never** set `webViewClient`/`webChromeClient` on it here -
 * that replaces the module's clients and silently ends all media detection. Own callbacks go in the
 * parameters of `createBrowser`/`attach`, which forward everything; this demo needs none.
 */
@Composable
fun BrowserScreen(
    browser: MediaBrowser,
    vm: BrowserViewModel,
    sheetOpen: Boolean,
    onSheetDismiss: () -> Unit,
    onDownload: (MediaModel, MediaQualityModel) -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }

    // Follow the page. Without this the field keeps whatever was typed last, so after following a
    // link it names a page you are no longer on - and Reload looks like it went somewhere else.
    LaunchedEffect(state.url) {
        if (state.url.isNotBlank()) address = state.url
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
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
                    // load() refuses anything that isn't a url (a typed search has spaces).
                    if (!browser.load(address)) {
                        browser.load("https://duckduckgo.com/?q=" + URLEncoder.encode(address, "UTF-8"))
                    }
                }) { Text("Go") }
                TextButton(onClick = { browser.reload() }) { Text("Reload") }
            }

            if (state.isLoading) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
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
                    Text("Looking for media…", style = MaterialTheme.typography.bodySmall)
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    // Coming back to this tab re-adds the same WebView, so unparent it first.
                    (browser.webView.parent as? ViewGroup)?.removeView(browser.webView)
                    browser.webView
                },
            )
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
