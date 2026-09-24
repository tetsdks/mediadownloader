package com.media.downloader.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.markhoor.mediadownloader.domain.models.MediaModel
import com.markhoor.mediadownloader.domain.models.MediaQualityModel

/**
 * The qualities of one piece of media, for both the link screen and the browser.
 *
 * [isDescribing] is the browser's case: the module offers what it has found while it is still
 * reading titles and sizes, so the list is already usable and fills in as it goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityPickerSheet(
    media: MediaModel,
    isDescribing: Boolean,
    onPick: (MediaQualityModel) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            if (isDescribing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MediaThumbnail(media.thumbnailUrl)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        text = media.title.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                    )
                    media.durationMillis?.let { millis ->
                        Text(
                            text = "%d:%02d".format(millis / 60_000, (millis / 1_000) % 60),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            // Shown in the order the module gave them: best first.
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(media.qualities) { quality ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(quality.label.ifBlank { quality.type.name })
                            Text(
                                text = qualitySizeLabel(quality),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = { onPick(quality) }) { Text("Download") }
                    }
                }
            }
        }
    }
}
