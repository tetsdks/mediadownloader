package com.media.downloader.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.markhoor.mediadownloader.domain.models.DownloadModel
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.presentation.downloads.DownloadsUiState
import com.markhoor.mediadownloader.presentation.downloads.DownloadsViewModel
import com.media.downloader.ui.common.MediaThumbnail
import com.media.downloader.ui.common.formatBytes

/** Every download, live, with the actions its current state allows. */
@Composable
fun DownloadsScreen(vm: DownloadsViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    when (val current = state) {
        DownloadsUiState.Loading -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }

        is DownloadsUiState.Content -> if (current.isEmpty) {
            Text("Nothing downloaded yet.", Modifier.padding(24.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (current.inProgress.isNotEmpty()) {
                    item { SectionTitle("In progress") }
                    items(current.inProgress, key = { it.id }) { DownloadRow(it, vm) }
                }
                if (current.completed.isNotEmpty()) {
                    item { SectionTitle("Completed") }
                    items(current.completed, key = { it.id }) { DownloadRow(it, vm) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        HorizontalDivider()
    }
}

@Composable
private fun DownloadRow(download: DownloadModel, vm: DownloadsViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MediaThumbnail(download.thumbnailUrl, size = 56.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(download.title.ifBlank { download.fileName }, maxLines = 1)
            Text(statusLine(download), style = MaterialTheme.typography.bodySmall)

            val percent = download.progressPercent
            if (download.state != DownloadState.Completed && download.state != DownloadState.Failed) {
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                } else {
                    // The size is not known yet; the bar says "working" rather than a wrong number.
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                Text(
                    text = formatBytes(download.downloadedBytes) + " / " +
                        (download.totalBytes?.let(::formatBytes) ?: "?"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (download.state.isActive) {
                    TextButton(onClick = { vm.pause(download.id) }) { Text("Pause") }
                }
                if (download.state == DownloadState.Paused) {
                    TextButton(onClick = { vm.resume(download.id) }) { Text("Resume") }
                }
                // No retry when the media was never there: asking again is told the same thing.
                if (download.state == DownloadState.Failed && !download.isMediaGone) {
                    TextButton(onClick = { vm.resume(download.id) }) { Text("Retry") }
                }
                TextButton(onClick = { vm.delete(download.id) }) { Text("Delete") }
                if (download.state == DownloadState.Completed) {
                    TextButton(onClick = { vm.delete(download.id, deleteFile = true) }) { Text("Delete + file") }
                }
            }
        }
    }
}

private fun statusLine(download: DownloadModel): String = when (download.state) {
    DownloadState.Queued -> "Queued"
    DownloadState.Downloading -> "${download.progressPercent ?: 0}%"
    DownloadState.Paused -> "Paused"
    DownloadState.WaitingForNetwork -> "Waiting for a connection"
    // The name can change at completion, so it is read from the completed model.
    DownloadState.Completed -> "Saved as ${download.fileName}"
    DownloadState.Failed ->
        if (download.isMediaGone) "This post is private or no longer available" else "Download failed"
}
