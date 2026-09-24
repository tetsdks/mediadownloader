package com.markhoor.mediadownloader.presentation.browser

import android.webkit.JavascriptInterface
import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.core.Constants.Facebook
import com.markhoor.mediadownloader.core.isHttpUrl
import com.markhoor.mediadownloader.core.looksLikeImageUrl
import com.markhoor.mediadownloader.domain.models.MediaType
import com.markhoor.mediadownloader.domain.models.PageSignal
import com.markhoor.mediadownloader.domain.models.ScriptMessage
import com.markhoor.mediadownloader.domain.models.ScriptSource
import com.markhoor.mediadownloader.domain.repo.MediaDetector

/**
 * What the page scripts call. Every method a script calls must exist here with that exact name and
 * number of arguments: a missing one is a TypeError that silently stops the rest of the script.
 * Methods are called on Chromium's bridge thread; each only turns its arguments into a
 * [ScriptMessage] and hands it on.
 */
internal class PageScriptBridge(private val detector: MediaDetector) {

    private val twitterStatusId = Regex("""status/(\d+)""")

    // region Generic script

    @JavascriptInterface
    fun genericButtonsDrawn(count: Int) = send(ScriptMessage.ButtonsDrawn(count))

    // Three signatures, not defaults: the bridge exposes only what is declared, and scripts call all three.
    @JavascriptInterface
    fun feedCardChanged(title: String?, thumbnail: String?) = feedCardChanged(title, thumbnail, null, false)

    @JavascriptInterface
    fun feedCardChanged(title: String?, thumbnail: String?, mediaUrl: String?) =
        feedCardChanged(title, thumbnail, mediaUrl, false)

    @JavascriptInterface
    fun feedCardChanged(title: String?, thumbnail: String?, mediaUrl: String?, isImage: Boolean) =
        send(ScriptMessage.CardChanged(title?.take(Download.MAX_STORED_TITLE_LENGTH), thumb(thumbnail), link(mediaUrl), isImage))

    @JavascriptInterface
    fun genericMediaRequested(
        pageUrl: String?,
        title: String?,
        thumbnail: String?,
        mediaUrl: String?,
        playing: Boolean,
        isImage: Boolean,
    ) = send(
        ScriptMessage.MediaRequested(
            link(pageUrl), title?.take(Download.MAX_STORED_TITLE_LENGTH), thumb(thumbnail), link(mediaUrl), playing, isImage,
        ),
    )

    // endregion

    // region Facebook

    /** A video, with its id when the script found one: the reel is then read by the parser too. */
    @JavascriptInterface
    fun fbVideoFound(urlOne: String?, urlTwo: String?) = send(
        ScriptMessage.MediaFound(
            source = ScriptSource.Facebook,
            type = MediaType.Video,
            mediaUrl = link(urlOne),
            // The script cuts the id from `video_id=` to the end of the url, query and all.
            postUrl = link(urlTwo)?.trim()?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() }
                ?.let { Facebook.REEL_URL + it },
        ),
    )

    @JavascriptInterface
    fun processVideo(urlOne: String?, postText: String?) = send(
        ScriptMessage.MediaFound(ScriptSource.Facebook, MediaType.Video, link(urlOne), title = textOrNull(postText)),
    )

    @JavascriptInterface
    fun processImage(urlOne: String?, postText: String?) = send(
        ScriptMessage.MediaFound(ScriptSource.Facebook, MediaType.Image, link(urlOne), title = textOrNull(postText)),
    )

    // endregion

    // region Instagram

    @JavascriptInterface
    fun instaVideoFound(urlOne: String?) = send(
        ScriptMessage.MediaFound(ScriptSource.Instagram, MediaType.Video, mediaUrl = null, postUrl = link(urlOne)),
    )

    /** A video whose element holds a real file rather than a blob: that file, with its poster. */
    @JavascriptInterface
    fun runV(urlOne: String?, urlTwo: String?, postText: String?) {
        val mediaUrl = link(urlOne)?.takeIf { it.isHttpUrl() } ?: return
        send(
            ScriptMessage.MediaFound(
                source = ScriptSource.Instagram,
                type = MediaType.Video,
                mediaUrl = mediaUrl,
                thumbnail = thumb(urlTwo)?.takeIf { it.isHttpUrl() },
                title = textOrNull(postText),
            ),
        )
    }

    @JavascriptInterface
    fun onInstaImageFound(imageUrl: String?, postText: String?) = send(
        ScriptMessage.MediaFound(ScriptSource.Instagram, MediaType.Image, link(imageUrl), title = textOrNull(postText)),
    )

    // endregion

    // region X / Twitter

    @JavascriptInterface
    fun drinkTwitter(urlOne: String?) {
        val url = link(urlOne) ?: return
        val id = twitterStatusId.find(url)?.groupValues?.getOrNull(1)
        sendTweet(url, id)
    }

    /**
     * A picture on a post. The script hands over the picture's own url, which names no status; the
     * old code sent it to the parser as if it were a post and nothing came back, so it is offered as
     * the image it is.
     */
    @JavascriptInterface
    fun runITwitter(urlOne: String?) {
        val url = link(urlOne) ?: return
        if (url.contains("status/")) {
            sendTweet(url, url.substringAfter("status/").substringBefore('/').takeIf { id -> id.all { it.isDigit() } })
        } else if (url.isHttpUrl()) {
            send(ScriptMessage.MediaFound(ScriptSource.Twitter, MediaType.Image, mediaUrl = url))
        }
    }

    private fun sendTweet(url: String?, id: String?) {
        val postUrl = if (id.isNullOrEmpty()) url?.substringBeforeLast('/') else url?.substringBefore("status/") + "status/$id"
        send(ScriptMessage.MediaFound(ScriptSource.Twitter, MediaType.Video, mediaUrl = null, postUrl = postUrl))
    }

    // endregion

    // region Threads

    /**
     * A post's media. A feed video carries a blob or no url until played, so only a real http file
     * counts; otherwise the post is handed to the parser. The fullscreen viewer has no caption, so
     * the account names the download there.
     */
    @JavascriptInterface
    fun threadsMediaFound(urlOne: String?, pagePostUrl: String?, postTitle: String?, postThumb: String?) {
        val postUrl = link(pagePostUrl)
        val directUrl = link(urlOne)?.takeIf { it.isHttpUrl() }
        if (directUrl == null && postUrl.isNullOrBlank()) return
        val title = textOrNull(postTitle)
            ?: postUrl?.substringAfter("/@", "")?.substringBefore('/')?.takeIf { it.isNotBlank() }
        val isVideo = directUrl == null ||
            listOf(".mp4", "video/", "video_dashinit", "blob:").any { directUrl.contains(it, ignoreCase = true) } ||
            (directUrl.contains("cdninstagram.com") && !directUrl.looksLikeImageUrl())
        send(
            ScriptMessage.MediaFound(
                source = ScriptSource.Threads,
                type = if (isVideo) MediaType.Video else MediaType.Image,
                mediaUrl = directUrl,
                postUrl = postUrl?.takeIf { it.isNotBlank() },
                thumbnail = thumb(postThumb)?.takeIf { it.isHttpUrl() },
                title = title,
            ),
        )
    }

    // endregion

    // region Signals the scripts send when their buttons are in place; nothing to do, but they must exist.

    @JavascriptInterface
    fun walkSuc() = Unit

    @JavascriptInterface
    fun fbNewStyle() = Unit

    @JavascriptInterface
    fun drinkSuc() = Unit

    // endregion

    private fun send(message: ScriptMessage) = detector.onSignal(PageSignal.Script(message))

    /**
     * A script sends blank rather than null for no text; blank must not become a file name. Text is
     * the page's, so it is cut to a title's length: a page can hand over megabytes.
     */
    private fun textOrNull(text: String?): String? =
        text?.take(Download.MAX_STORED_TITLE_LENGTH)?.trim()?.takeIf { it.isNotEmpty() }

    /** A url longer than a real link is a page's data: url; cut short it would be a wrong one, so it is dropped. */
    private fun link(url: String?): String? = url?.takeIf { it.length <= Browser.MAX_SNIFFED_URL_LENGTH }

    /** A thumbnail may be a small inline image; one past what a download keeps is dropped. */
    private fun thumb(url: String?): String? = url?.takeIf { it.length <= Download.MAX_STORED_URL_LENGTH }
}
