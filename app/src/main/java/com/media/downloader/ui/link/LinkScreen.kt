package com.media.downloader.ui.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.markhoor.mediadownloader.MediaDownloader
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel
import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.presentation.linkparse.LinkParseUiState
import com.markhoor.mediadownloader.presentation.linkparse.LinkParseViewModel
import com.media.downloader.ui.common.MediaThumbnail
import com.media.downloader.ui.common.canTryInBrowser
import com.media.downloader.ui.common.parseFailureMessage
import com.media.downloader.ui.common.qualitySizeLabel

/** Paste or share a link, read what is behind it, download one of its qualities. */
@Composable
fun LinkScreen(
    vm: LinkParseViewModel,
    onDownload: (MediaModel, MediaQualityModel) -> Unit,
    onOpenInBrowser: (String) -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }

    // Cheap and main-thread safe by contract, so it can follow every keystroke.
    val access = remember(text) { if (text.isBlank()) null else MediaDownloader.siteAccess(text) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Paste a link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        access?.let {
            Text(
                text = when (it) {
                    SiteAccess.Allowed -> "Supported - the parser can read this"
                    SiteAccess.Unsupported -> "Not on the supported list - try the browser tab"
                    SiteAccess.Blocked -> "Downloads aren't allowed from this site"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.parse(text) }, enabled = text.isNotBlank()) { Text("Get media") }
            OutlinedButton(onClick = { text = ""; vm.reset() }) { Text("Clear") }
        }

        HorizontalDivider()

        when (val current = state) {
            LinkParseUiState.Idle -> Text(
                "Paste a Facebook, Instagram, Pinterest, Dailymotion, TikTok or X link - or share one into this app.",
                style = MaterialTheme.typography.bodyMedium,
            )

            is LinkParseUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text(current.url, maxLines = 1)
            }

            is LinkParseUiState.Success -> MediaResult(current.media, onDownload)

            is LinkParseUiState.Failure -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(parseFailureMessage(current.error), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A blocked site gets no way around it, deliberately.
                    if (canTryInBrowser(current.error)) {
                        TextButton(onClick = { onOpenInBrowser(current.error.url) }) { Text("Open in browser") }
                    }
                    TextButton(onClick = { vm.parse(text) }) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun MediaResult(media: MediaModel, onDownload: (MediaModel, MediaQualityModel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MediaThumbnail(media.thumbnailUrl, size = 72.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(media.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                Text("${media.qualities.size} to choose from", style = MaterialTheme.typography.bodySmall)
            }
        }
        // In the order the module returned them: best first.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(media.qualities) { quality ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(quality.label.ifBlank { quality.type.name })
                        Text(qualitySizeLabel(quality), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { onDownload(media, quality) }) { Text("Download") }
                }
            }
        }
    }
}
