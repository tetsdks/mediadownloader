package com.media.downloader.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.markhoor.mediadownloader.MediaDownloader
import com.markhoor.mediadownloader.domain.models.DownloadModel
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.presentation.downloads.DownloadsUiState
import com.markhoor.mediadownloader.presentation.downloads.DownloadsViewModel
import com.media.downloader.ui.common.MediaThumbnail
import com.media.downloader.ui.common.formatBytes
import kotlinx.coroutines.launch

/** Every download, live, with the actions its current state allows. */
@Composable
fun DownloadsScreen(vm: DownloadsViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    /** Which playlists are open. A playlist is one row until the reader asks for its videos. */
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }

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
                // A playlist is one row wherever its videos are: split across the two sections
                // it said "0 of 6 saved" while its videos were being saved, because each one left
                // the section as it finished.
                val all = current.inProgress + current.completed
                val collections = all.mapNotNull { it.collectionTitle }.distinct()
                    .map { title -> title to all.filter { it.collectionTitle == title } }
                // Not finished until every video is: a paused or failed one still belongs with the
                // downloads, not under "Completed".
                val stillGoing = collections.filter { (_, members) ->
                    members.any { it.state != DownloadState.Completed }
                }
                val allDone = collections.filterNot { it in stillGoing }
                val onToggle: (String) -> Unit = { title ->
                    expanded = if (title in expanded) expanded - title else expanded + title
                }

                // A heading only where something sits under it: a playlist keeps its finished
                // videos, so "Completed" can otherwise stand over nothing.
                val goingAlone = current.inProgress.filter { it.collectionTitle == null }
                val doneAlone = current.completed.filter { it.collectionTitle == null }

                if (stillGoing.isNotEmpty() || goingAlone.isNotEmpty()) {
                    item { SectionTitle("In progress") }
                    collectionRows(stillGoing, vm, expanded, onToggle)
                    items(goingAlone, key = { it.id }) { DownloadRow(it, vm) }
                }
                if (allDone.isNotEmpty() || doneAlone.isNotEmpty()) {
                    item { SectionTitle("Completed") }
                    collectionRows(allDone, vm, expanded, onToggle)
                    items(doneAlone, key = { it.id }) { DownloadRow(it, vm) }
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
                if (download.state.canPause) {
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
    // Every byte is here; the file is being written. There is nothing to pause and nothing to count.
    DownloadState.Finishing -> "Finishing…"
    DownloadState.Paused -> "Paused"
    DownloadState.WaitingForNetwork -> "Waiting for a connection"
    // The name can change at completion, so it is read from the completed model.
    DownloadState.Completed -> "Saved as ${download.fileName}"
    DownloadState.Failed ->
        if (download.isMediaGone) "This post is private or no longer available" else "Download failed"
}

/**
 * Each playlist as one row that opens.
 *
 * Downloads carry the collection they were queued with, so grouping them is the module's answer
 * rather than a guess from names or folders.
 */
private fun LazyListScope.collectionRows(
    collections: List<Pair<String, List<DownloadModel>>>,
    vm: DownloadsViewModel,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
) {
    collections.forEach { (title, members) ->
        item(key = "collection:$title") {
            CollectionRow(
                title = title,
                members = members,
                isOpen = title in expanded,
                onToggle = { onToggle(title) },
                vm = vm,
            )
        }
    }
}

/** A playlist as one row: what it adds up to, and its videos when the reader opens it. */
@Composable
private fun CollectionRow(
    title: String,
    members: List<DownloadModel>,
    isOpen: Boolean,
    onToggle: () -> Unit,
    vm: DownloadsViewModel,
) {
    val done = members.count { it.state == DownloadState.Completed }
    val failed = members.count { it.state == DownloadState.Failed }
    val paused = members.count { it.state == DownloadState.Paused }
    val downloaded = members.sumOf { it.downloadedBytes }
    // Only what is known: one video with no size yet must not make the whole playlist's unknown.
    val total = members.mapNotNull { it.totalBytes }.takeIf { it.size == members.size }?.sum()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaThumbnail(members.firstOrNull()?.thumbnailUrl, size = 56.dp)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    "$done of ${members.size} saved" +
                        (if (paused > 0) " - $paused paused" else "") +
                        (if (failed > 0) " - $failed failed" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (downloaded.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text(
                        formatBytes(downloaded) + " / " + formatBytes(total),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            Text(if (isOpen) "▾" else "▸", Modifier.padding(horizontal = 8.dp))
        }

        // One press for the lot: the reader queued them in one press too.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val scope = rememberCoroutineScope()
            if (members.any { it.state.canPause }) {
                TextButton(onClick = {
                    scope.launch { MediaDownloader.pauseCollection(title) }
                }) { Text("Pause all") }
            }
            if (members.any { it.state == DownloadState.Paused || it.state == DownloadState.Failed }) {
                TextButton(onClick = {
                    scope.launch { MediaDownloader.resumeCollection(title) }
                }) { Text("Resume all") }
            }
        }

        if (isOpen) {
            Column(
                modifier = Modifier.padding(start = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                members.forEach { member -> DownloadRow(member, vm) }
            }
        }
    }
}
