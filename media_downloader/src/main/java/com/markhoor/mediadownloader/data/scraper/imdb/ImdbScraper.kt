package com.markhoor.mediadownloader.data.scraper.imdb

import com.markhoor.mediadownloader.core.Constants.Imdb
import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.data.network.CookieSource
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper

/**
 * A video's own page on IMDb, which lists every height the video was encoded at.
 *
 * Read rather than sniffed. The browser used to answer a press here from whatever the player had
 * been heard asking for, which is nothing at all when the file is already in the cache - the reader
 * pressed, the disc spun and the sheet never opened - and even when it was heard it was the one
 * height the player had chosen. The page names all four.
 *
 * IMDb answers a request that carries no session with `202` and an empty page, so the browser's own
 * cookies are sent with it. That makes this a reader for a page the reader has been to: a link
 * pasted on a device that has never opened IMDb is answered with nothing, the same as before. The
 * files themselves are signed urls and need no cookies, so a download outlives the session.
 */
internal class ImdbScraper(
    private val fetcher: HttpFetcher,
    private val cookies: CookieSource,
) : SiteScraper() {

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val page = fetcher.getText(url, headersFor(url)).getOrThrow().ifBlank { return null }
        val qualities = ImdbVideoParser.qualitiesOf(page).ifEmpty { return null }
        return ScrapedMediaDto(
            qualities = qualities,
            title = ImdbVideoParser.titleOf(page),
            thumbnailUrl = ImdbVideoParser.thumbnailOf(page),
            durationMillis = ImdbVideoParser.durationMillisOf(page),
        )
    }

    private fun headersFor(url: String): Map<String, String> = buildMap {
        put(Network.HEADER_USER_AGENT, Imdb.USER_AGENT)
        put("Accept", Network.ACCEPT_HTML)
        put("Accept-Language", Network.ACCEPT_LANGUAGE)
        cookies.cookiesFor(url)?.takeIf { it.isNotBlank() }?.let { put(Network.HEADER_COOKIE, it) }
    }
}
