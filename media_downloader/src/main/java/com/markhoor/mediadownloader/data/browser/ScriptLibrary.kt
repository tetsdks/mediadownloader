package com.markhoor.mediadownloader.data.browser

import com.markhoor.mediadownloader.core.Constants.Browser
import com.markhoor.mediadownloader.data.device.DeviceProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** The scripts put into pages, one per site that needs its own and one for every other site. */
internal enum class PageScript(val fileName: String) {
    Facebook("facebook.js"),
    Instagram("instagram.js"),
    Twitter("twitter.js"),
    Threads("threads.js"),
    TikTok("tiktok.js"),
    Generic("generic.js"),
}

/**
 * The page scripts, read from the module's assets.
 *
 * Each is read once and kept, off the main thread; [release] drops them all when the system is short
 * of memory and they are read again when next needed. Every script is preceded by a prelude
 * telling it how hard it may work the device.
 */
internal class ScriptLibrary(
    /** Reads one asset file, by its path under the assets folder. */
    private val readAsset: (String) -> String,
    private val profile: DeviceProfile,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val cache = ConcurrentHashMap<PageScript, String>()

    /**
     * [script], ready to run. [singleMediaPage] and [parserSite] matter only to the generic
     * script: whether the page is one media's own page, and whether the parser reads this site.
     */
    suspend fun build(
        script: PageScript,
        singleMediaPage: Boolean = false,
        parserSite: Boolean = false,
        postPath: String = "",
    ): String {
        val body = cache[script] ?: withContext(ioDispatcher) {
            readAsset("${Browser.SCRIPT_FOLDER}/${script.fileName}")
        }.also { cache[script] = it }
        return prelude(script, singleMediaPage, parserSite, postPath) + body
    }

    fun release() {
        cache.clear()
    }

    private fun prelude(
        script: PageScript,
        singleMediaPage: Boolean,
        parserSite: Boolean,
        postPath: String,
    ): String = buildString {
        append("window.mksLowEnd=").append(profile.isLowEnd).append(';')
        append("window.mksScanScale=").append(if (profile.isLowEnd) Browser.SCAN_SCALE_LOW_END else 1.0).append(';')
        if (script == PageScript.Generic) {
            append("window.mksSingle=").append(singleMediaPage).append(';')
            append("window.mksParserSite=").append(parserSite).append(';')
            append("window.mksPostPath='").append(postPath).append("';")
        }
        append('\n')
    }
}
