package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.core.Constants
import com.markhoor.mediadownloader.MediaDownloaderConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


// Carried over from the app's tests of the old url-parser deny and allow lists, so every case
// they pinned keeps holding against the module. "Blocked" is asked in generic mode, where only
// the block list refuses; "supported" in strict mode, the mode that ships.
private val genericAccess = CheckSiteAccessUseCase(strictSupportedSitesOnly = false, extraBlockedHosts = emptySet())
private val strictAccess = CheckSiteAccessUseCase(strictSupportedSitesOnly = true, extraBlockedHosts = emptySet())

private fun String.isSiteLinkBlocked(): Boolean = genericAccess(this) == SiteAccess.Blocked

private fun isSupportedSite(url: String): Boolean = strictAccess(url) == SiteAccess.Allowed

/**
 * The allow list behind `STRICT_SUPPORTED_SITES_ONLY`.
 *
 * Tested here rather than through the button: `canShowDownloadIconForUrl` also answers false for
 * the five sites whose own script draws their buttons, so a test through it would pass for the
 * wrong reason.
 *
 * Note that `isSiteLinkBlocked` is only half itself in a jvm test - its youtube branch parses
 * with `android.net.Uri`, which is a stub here and throws into a catch. The restricted matcher,
 * which is the half that matters below, is pure jvm and runs for real.
 */
class SupportedSitesTest {

    /* Shipping decision, one character wide. Its sibling file records that a flag like this has
     * been flipped by edits from outside the session before. */
    @Test
    fun strictModeIsWhatShips() {
        assertTrue(MediaDownloaderConfig().strictSupportedSitesOnly)
    }

    /**
     * Every host the app's own code names has to be on the list, or the code behind it is dead
     * in the shipping build - the parser sites, the sniffing sites, the per-site scripts.
     * Collected from the `isHostOf(...)` literals in UrlExtensions plus the threads and reddit
     * hosts their own matchers name.
     */
    @Test
    fun everyHostTheAppNamesIsSupported() {
        listOf(
            "9gag.com", "bitchute.com", "dailymotion.com", "facebook.com", "fb.watch",
            "imdb.com", "instagram.com", "linkedin.com", "moj.sharechat.com", "mojapp.in",
            "pexels.com", "pin.it", "pinterest.com", "redd.it", "reddit.com", "rumble.com",
            "snackvideo.com", "ted.com", "threads.com", "threads.net", "tiktok.com",
            "tumblr.com", "twitch.tv", "twitter.com", "vimeo.com", "x.com",
        ).forEach { assertTrue("named by the app but not supported: $it", isSupportedSite("https://$it/")) }
    }

    /**
     * Each entry has to be a registrable host and nothing else. A typo like
     * "https://www.vimeo.com/" would sit in the set matching nothing at all, and no other test
     * would notice.
     */
    @Test
    fun everyEntryIsShapedLikeAHost() {
        Constants.Hosts.SUPPORTED.forEach { entry ->
            assertFalse("has a scheme: $entry", entry.contains("://"))
            assertFalse("has a path: $entry", entry.contains("/"))
            assertFalse("has www: $entry", entry.startsWith("www."))
            assertTrue("not lowercase: $entry", entry == entry.lowercase())
            assertTrue("no dot: $entry", entry.contains("."))
            assertTrue("does not match itself: $entry", isSupportedSite("https://$entry/"))
        }
    }

    /* The shapes a real url arrives in. */
    @Test
    fun subdomainsAndPathsAreSupported() {
        listOf(
            "https://www.reddit.com/r/aww/",
            "https://m.facebook.com/watch",
            "https://old.reddit.com/",
            "https://en.wikipedia.org/wiki/Cat",
            "https://upload.wikimedia.org/a/b.jpg",
            "https://player.vimeo.com/video/1",
            "reddit.com",
            "https://www.bbc.co.uk/news?x=1#y",
        ).forEach { assertTrue("not supported: $it", isSupportedSite(it)) }
    }

    /**
     * The one the owner asked about by name: twitter is x.com now, and "sex.com" ends with the
     * characters "x.com". A substring test would have put an adult site on this list. The host is
     * walked one label at a time, and a suffix only ever starts after a dot.
     */
    @Test
    fun aHostThatMerelyEndsInAListedOneIsNotSupported() {
        listOf(
            "https://sex.com/",
            "https://www.sex.com/a/status/1",
            "https://notreddit.com/",
            "https://reddit.com.evil.net/",
            "https://fakevimeo.com/",
            "https://ted.com.phish.io/",
        ).forEach { assertFalse("wrongly supported: $it", isSupportedSite(it)) }

        assertTrue("x.com must be supported", isSupportedSite("https://x.com/a/status/1"))
        assertTrue("sex.com must be blocked", "https://sex.com/".isSiteLinkBlocked())
    }

    /* Nothing that is not a page, and nothing nobody listed. */
    @Test
    fun unlistedAndUnparseableAreNotSupported() {
        listOf(
            "", "about:blank", "data:text/html,x", "https://stackoverflow.com/",
            "https://example.com/", "https://www.youtube.com/watch?v=1", "https://youtu.be/1",
            "https://xhamster.com/videos/1", "https://pornhub.com/",
            "sex.com/?ref=https://x.com",
        ).forEach { assertFalse("wrongly supported: $it", isSupportedSite(it)) }
    }

    /**
     * The two lists are edited by different people at different times, and they can collide: a
     * keyword added to the deny list can make a site on this one unreachable. analog.com already
     * sits one edge guard away from the "anal" rule.
     */
    @Test
    fun noSupportedSiteIsBlocked() {
        Constants.Hosts.SUPPORTED.forEach { entry ->
            assertFalse("supported but blocked: $entry", "https://$entry/".isSiteLinkBlocked())
        }
    }
}
