package com.markhoor.mediadownloader.data.scraper

import com.markhoor.mediadownloader.core.isDailymotionMetadataLink
import com.markhoor.mediadownloader.core.isDailymotionVideoLink
import com.markhoor.mediadownloader.core.isFacebookShareLink
import com.markhoor.mediadownloader.core.isFacebookVideoLink
import com.markhoor.mediadownloader.core.isInstagramPostLink
import com.markhoor.mediadownloader.core.isLinkedInPostLink
import com.markhoor.mediadownloader.core.isPinterestPinLink
import com.markhoor.mediadownloader.core.isPornhubVideoLink
import com.markhoor.mediadownloader.core.isThreadsPostLink
import com.markhoor.mediadownloader.core.isTikTokLink
import com.markhoor.mediadownloader.core.isTwitterStatusLink

/**
 * Which scrapers can read a link, most specific rule first. An adult site reaches this only when
 * the host app allowed the category; site access refuses the link before any scraper is asked
 * otherwise, which is why one appearing here changes nothing for an app that left it off.
 *
 * @param twitter `null` when the host app supplied no tweeload key; X links then have no parser.
 * @param hostSupplied readers the host app brought, asked only for links none of the above claims -
 *   nothing an app supplies can quietly stand in for Instagram or TikTok.
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
    private val pornhub: SiteScraper,
    private val getInDevice: SiteScraper,
    private val hostSupplied: List<HostSuppliedScraper> = emptyList(),
) {

    /** The hosts the app's own readers cover; the browser treats them as sites the parser reads. */
    val hostSuppliedHosts: Set<String> = hostSupplied.flatMapTo(mutableSetOf()) { it.hosts }

    /** The scrapers to race for [url]; empty when no parser understands it. */
    fun scrapersFor(url: String): List<SiteScraper> = ours(url).ifEmpty { theirs(url) }

    private fun theirs(url: String): List<SiteScraper> = hostSupplied.filter { it.reads(url) }

    private fun ours(url: String): List<SiteScraper> = when {
        url.isFacebookVideoLink() -> listOf(facebookVideo, getInDevice)
        url.isFacebookShareLink() -> listOf(facebookShare, getInDevice)
        url.isInstagramPostLink() || url.isThreadsPostLink() -> listOf(instagram)
        url.isLinkedInPostLink() -> listOf(linkedIn)
        url.isTikTokLink() -> listOf(tikTok, getInDevice)
        url.isTwitterStatusLink() -> listOfNotNull(twitter)
        url.isDailymotionVideoLink() || url.isDailymotionMetadataLink() -> listOf(dailymotion)
        url.isPinterestPinLink() -> listOf(pinterest)
        url.isPornhubVideoLink() -> listOf(pornhub)
        else -> emptyList()
    }
}
