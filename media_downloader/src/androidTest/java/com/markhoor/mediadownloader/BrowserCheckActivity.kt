package com.markhoor.mediadownloader

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity

/** A screen holding one WebView, as a host's browser screen would. */
class BrowserCheckActivity : ComponentActivity() {

    val webView: WebView by lazy { WebView(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(webView)
    }
}
