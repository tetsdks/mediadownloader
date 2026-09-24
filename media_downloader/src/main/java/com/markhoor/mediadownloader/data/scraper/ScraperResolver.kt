package com.markhoor.mediadownloader.data.scraper

import com.markhoor.mediadownloader.core.isDailymotionMetadataLink
import com.markhoor.mediadownloader.core.isDailymotionVideoLink
import com.markhoor.mediadownloader.core.isFacebookShareLink
import com.markhoor.mediadownloader.core.isFacebookVideoLink
import com.markhoor.mediadownloader.core.isInstagramPostLink
import com.markhoor.mediadownloader.core.isLinkedInPostLink
import com.markhoor.mediadownloader.core.isPinterestPinLink
import com.markhoor.mediadownloader.core.isThreadsPostLink
import com.markhoor.mediadownloader.core.isTikTokLink
import com.markhoor.mediadownloader.core.isTwitterStatusLink

/**
 * Which scrapers can read a link, most specific rule first. Adult sites have no scrapers at all:
 * they never get this far, because site access refuses them before any link is read.
 *
 * @param twitter `null` when the host app supplied no tweeload key; X links then have no parser.
 */
internal class ScraperResolver(
    private val facebookVideo: SiteScraper,
    private val facebookShare: SiteScraper,
    private val instagram: SiteScraper,
    private val linkedIn: SiteScraper,
    private val tikTok: SiteScraper,
    private val twitter: SiteScraper?,
    private val dailymotion: SiteScraper,
    private val pinterest: SiteScraper,
    private val getInDevice: SiteScraper,
) {

    /** The scrapers to race for [url]; empty when no parser understands it. */
    fun scrapersFor(url: String): List<SiteScraper> = when {
        url.isFacebookVideoLink() -> listOf(facebookVideo, getInDevice)
        url.isFacebookShareLink() -> listOf(facebookShare, getInDevice)
        url.isInstagramPostLink() || url.isThreadsPostLink() -> listOf(instagram)
        url.isLinkedInPostLink() -> listOf(linkedIn)
        url.isTikTokLink() -> listOf(tikTok, getInDevice)
        url.isTwitterStatusLink() -> listOfNotNull(twitter)
        url.isDailymotionVideoLink() || url.isDailymotionMetadataLink() -> listOf(dailymotion)
        url.isPinterestPinLink() -> listOf(pinterest)
        else -> emptyList()
    }
}
