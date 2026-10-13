package com.markhoor.mediadownloader.data.scraper

import com.markhoor.mediadownloader.core.isDailymotionMetadataLink
import com.markhoor.mediadownloader.core.isDailymotionVideoLink
import com.markhoor.mediadownloader.core.isFacebookShareLink
import com.markhoor.mediadownloader.core.isFacebookVideoLink
import com.markhoor.mediadownloader.core.isImdbVideoPage
import com.markhoor.mediadownloader.core.isInstagramPostLink
import com.markhoor.mediadownloader.core.isLinkedInPostLink
import com.markhoor.mediadownloader.core.isPinterestPinLink
import com.markhoor.mediadownloader.core.isThreadsPostLink
import com.markhoor.mediadownloader.data.scraper.tube.TubeScraper
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
    private val imdb: SiteScraper,
    private val tikTok: SiteScraper,
    private val twitter: SiteScraper?,
    private val dailymotion: SiteScraper,
    private val pinterest: SiteScraper,
    private val tube: TubeScraper,
    private val getInDevice: SiteScraper,
    private val hostSupplied: List<HostSuppliedScraper> = emptyList(),
) {

    /**
     * Whether the link is one only the generic tube reader claims. It reads what a page says about
     * itself and cannot read a site that encrypts that, so the browser keeps watching the player's
     * own requests on these pages instead of standing aside for a parser that may come up empty.
     */
    fun readsBestEffort(url: String): Boolean = tube.reads(url)

    /** The hosts the app's own readers cover; the browser treats them as sites the parser reads. */
    val hostSuppliedHosts: Set<String> = hostSupplied.flatMapTo(mutableSetOf()) { it.hosts }

    /** The scrapers to race for [url]; empty when no parser understands it. */
    fun scrapersFor(url: String): List<SiteScraper> = ours(url).ifEmpty { theirs(url) }

    /**
     * The third-party resolver, on the sites it reads. **A backup, not a peer:** it is raced with
     * the readers above so its requests cost no waiting, and [ScraperRacer] takes its answer only
     * where every one of them came back empty. It reads a site less well than the site's own
     * reader does - no title on an Instagram post, and a Facebook link it cannot resolve is
     * answered with whatever the Watch feed is showing - so being quicker must not be enough.
     *
     * Only sites it was **measured** against are here. Dailymotion and IMDb it cannot read at all;
     * Instagram and Threads have it already, inside their own reader, which knows to prefer the
     * session; an adult site is never named to a third party, which is also why the tube reader
     * has no backup; and a host app's own reader is the authority on its own site.
     */
    fun backupsFor(url: String): List<SiteScraper> =
        if (hasBackup(url)) listOf(getInDevice) else emptyList()

    private fun hasBackup(url: String): Boolean = when {
        url.isFacebookVideoLink() || url.isFacebookShareLink() -> true
        url.isTikTokLink() -> true
        // X without a key has no reader of ours at all, so here the backup is the only one.
        url.isTwitterStatusLink() -> true
        url.isLinkedInPostLink() -> true
        url.isPinterestPinLink() -> true
        else -> false
    }

    private fun theirs(url: String): List<SiteScraper> = hostSupplied.filter { it.reads(url) }

    private fun ours(url: String): List<SiteScraper> = when {
        url.isFacebookVideoLink() -> listOf(facebookVideo)
        url.isFacebookShareLink() -> listOf(facebookShare)
        url.isInstagramPostLink() || url.isThreadsPostLink() -> listOf(instagram)
        url.isLinkedInPostLink() -> listOf(linkedIn)
        url.isTikTokLink() -> listOf(tikTok)
        url.isTwitterStatusLink() -> listOfNotNull(twitter)
        url.isDailymotionVideoLink() || url.isDailymotionMetadataLink() -> listOf(dailymotion)
        url.isPinterestPinLink() -> listOf(pinterest)
        url.isImdbVideoPage() -> listOf(imdb)
        tube.reads(url) -> listOf(tube)
        else -> emptyList()
    }
}
