package com.media.downloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.markhoor.mediadownloader.MediaDownloader
import com.media.downloader.ui.DemoRoot
import com.media.downloader.ui.theme.MediaDownloaderLibraryTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val sharedLink = MutableStateFlow<String?>(null)
    private val notificationDownloadId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        enableEdgeToEdge()
        setContent {
            MediaDownloaderLibraryTheme {
                val link by sharedLink.collectAsStateWithLifecycle()
                val id by notificationDownloadId.collectAsStateWithLifecycle()
                DemoRoot(
                    sharedLink = link,
                    notificationDownloadId = id,
                    onIntentHandled = {
                        sharedLink.value = null
                        notificationDownloadId.value = null
                    },
                )
            }
        }
    }

    /** A notification tap arrives here: the module sends it with FLAG_ACTIVITY_SINGLE_TOP. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        intent ?: return
        intent.getLongExtra(MediaDownloader.EXTRA_DOWNLOAD_ID, NO_ID).takeIf { it != NO_ID }?.let { id ->
            notificationDownloadId.value = id
            // Removed so a rotation does not act on it a second time.
            intent.removeExtra(MediaDownloader.EXTRA_DOWNLOAD_ID)
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            // parse() accepts text with a url in it, so whatever the other app shared goes straight in.
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedLink.value = it }
            intent.removeExtra(Intent.EXTRA_TEXT)
        }
    }

    private companion object {
        const val NO_ID = -1L
    }
}
