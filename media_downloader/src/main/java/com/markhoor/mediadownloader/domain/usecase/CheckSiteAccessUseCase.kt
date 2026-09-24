package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.core.Constants.Hosts
import com.markhoor.mediadownloader.core.embeddedUrls
import com.markhoor.mediadownloader.core.isUnderAnyOf
import com.markhoor.mediadownloader.core.normalizedHost
import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.domain.policy.RestrictedCategory
import com.markhoor.mediadownloader.domain.policy.RestrictedSites

/**
 * The one answer to "may this url be downloaded from", used by the browser, the parser, the
 * download and the public api alike.
 *
 * Blocking is checked first and wins over strict mode. A page is blocked when it is one of the host
 * app's [extraBlockedHosts] - always - or a restricted site ([RestrictedSites]) whose category is
 * not in [allowedRestrictions]. It covers the url itself and every url written inside it, because
 * arbitrary text arrives from paste and share: `example.com/go?ref=https://inxxx.com/v/abc` leads
 * with a harmless host but carries a blocked one.
 *
 * A restricted category the host allowed counts as supported in strict mode: allowing YouTube and
 * then refusing it as "not on the list" would make the switch do nothing.
 *
 * @param strictSupportedSitesOnly when on, only the supported sites are allowed.
 * @param extraBlockedHosts hosts the app blocks on top of the built-in list, in every configuration.
 * @param allowedRestrictions restricted categories the app has chosen to allow; none by default.
 */
internal class CheckSiteAccessUseCase(
    private val strictSupportedSitesOnly: Boolean,
    extraBlockedHosts: Set<String>,
    private val allowedRestrictions: Set<RestrictedCategory> = emptySet(),
) {

    private val extraBlockedHosts: Set<String> =
        extraBlockedHosts.mapNotNull { it.normalizedHost() }.toSet()

    operator fun invoke(url: String): SiteAccess {
        val host = url.normalizedHost() ?: return SiteAccess.Unsupported
        if (isBlockedHost(host) || carriesBlockedUrl(url)) return SiteAccess.Blocked
        val supported = host.isUnderAnyOf(Hosts.SUPPORTED) || RestrictedSites.categoryOf(host) != null
        if (strictSupportedSitesOnly && !supported) return SiteAccess.Unsupported
        return SiteAccess.Allowed
    }

    /**
     * Whether the host of [url] itself - a media file or a request the page made - is blocked.
     * Only the host counts: a CDN request often carries a referrer in its query, and a link to
     * YouTube there says nothing about the file being fetched.
     */
    fun blocksHostOf(url: String): Boolean = url.normalizedHost()?.let(::isBlockedHost) == true

    private fun isBlockedHost(host: String): Boolean =
        host.isUnderAnyOf(extraBlockedHosts) ||
            RestrictedSites.categoryOf(host)?.let { it !in allowedRestrictions } == true

    private fun carriesBlockedUrl(url: String): Boolean =
        url.embeddedUrls().any { embedded -> embedded.normalizedHost()?.let(::isBlockedHost) == true }
}
