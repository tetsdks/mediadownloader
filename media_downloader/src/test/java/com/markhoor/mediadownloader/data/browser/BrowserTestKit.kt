package com.markhoor.mediadownloader.data.browser

import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.tube.TubeScraper
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScraperResolver
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.policy.RestrictedCategory
import com.markhoor.mediadownloader.domain.usecase.CheckSiteAccessUseCase

/** A scraper that never finds anything; only which links reach it matters here. */
internal class SilentScraper : SiteScraper() {
    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = null
}

/** The generic tube reader, over a server that serves nothing unless a test says otherwise. */
internal fun testTube(
    files: Map<String, String> = emptyMap(),
    isRestrictedSite: (String) -> Boolean = { false },
) = TubeScraper(
    HttpFetcher(FakeMediaServer(files.mapValues { it.value.toByteArray() }).client, HttpClientFactory.json),
    cookies = { null },
    isRestrictedSite = isRestrictedSite,
)

internal fun testResolver() = ScraperResolver(
    facebookVideo = SilentScraper(),
    facebookShare = SilentScraper(),
    instagram = SilentScraper(),
    linkedIn = SilentScraper(),
    tikTok = SilentScraper(),
    tube = testTube(),
    twitter = SilentScraper(),
    dailymotion = SilentScraper(),
    pinterest = SilentScraper(),
    getInDevice = SilentScraper(),
)

internal fun testAccess(strict: Boolean = true, allowed: Set<RestrictedCategory> = emptySet()) =
    CheckSiteAccessUseCase(strictSupportedSitesOnly = strict, extraBlockedHosts = emptySet(), allowedRestrictions = allowed)

internal fun testPolicy(strict: Boolean = true, allowed: Set<RestrictedCategory> = emptySet()) =
    SniffPolicy(testAccess(strict, allowed), testResolver())
