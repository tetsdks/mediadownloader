package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.domain.models.SiteAccess
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
 * isSiteLinkBlocked runs on the main thread for every url the WebView reports, so
 * extractNormalizedHost() now skips IDN.toUnicode for plain ASCII hosts and isRestricted
 * memoises the last verdict. Both are meant to be behaviour-preserving - this pins that,
 * because getting it wrong means either blocking legitimate sites or letting restricted
 * ones through.
 */
class RestrictedUrlMatcherTest {

    /* YouTube is handled by the separate isYoutubeUrl(), which parses with Android's
     * Uri and so cannot run in a JVM unit test. It is untouched by the change under
     * test, so this covers the RestrictedUrlMatcher path only - that one uses
     * java.net.URI and does run here. */
    private val blocked = listOf(
        "https://www.xhamster.com/videos/abc",
        "https://xvideos.com/video123",
        "https://www.brazzers.com/",
        "https://xnxx.com/some/path",
        "https://pornhub.com/view",
        "https://stripchat.com/model",
        /* Numbered and dashed mirrors. The list names xhamster2, 3, 15 and 45 one at a time, so
         * anything it has not caught up with used to walk straight through. */
        "https://xhamster19.com/videos/abc",
        "https://xhamster21.desi/",
        "https://xhamster-mirror.com/",
        "https://xvideos9.com/video1",
        "https://pornhub3.org/view",
        "https://brazzers12.com/",
    )

    private val allowed = listOf(
        "https://www.dailymotion.com/video/x8da0md",
        "https://www.google.com/",
        "https://www.facebook.com/watch/?v=123",
        "https://www.instagram.com/reel/abc/",
        "https://www.pinterest.com/pin/123/",
        "https://example.com/",
        /* Must not be caught by the mirror rule: "tube" is inside tubitv, "wicked" inside
         * wickedlocal, and neither is a mirror of anything. (YouTube itself is blocked, but by
         * the YouTube list - see youtubeIsBlockedByItsOwnList.) */
        "https://www.tubitv.com/movies/1",
        "https://www.wickedlocal.com/story/1",
        "https://tubefilter.com/2024/01/01/news/",
        "https://www.tiktok.com/@user/video/123",
    )

    @Test
    fun restrictedSitesAreStillBlocked() {
        blocked.forEach {
            assertTrue("expected blocked: $it", it.isSiteLinkBlocked())
        }
    }

    @Test
    fun ordinarySitesAreNotBlocked() {
        allowed.forEach {
            assertFalse("expected allowed: $it", it.isSiteLinkBlocked())
        }
    }

    @Test
    fun memoisationDoesNotLeakAVerdictAcrossDifferentUrls() {
        // Alternating proves the cached verdict is keyed on the url, not just reused.
        repeat(3) {
            assertTrue("https://xvideos.com/a".isSiteLinkBlocked())
            assertFalse("https://www.dailymotion.com/video/x1".isSiteLinkBlocked())
            assertTrue("https://www.xhamster.com/b".isSiteLinkBlocked())
            assertFalse("https://example.com/".isSiteLinkBlocked())
        }
    }

    @Test
    fun repeatedIdenticalUrlIsStable() {
        repeat(5) {
            assertTrue("https://xnxx.com/x".isSiteLinkBlocked())
        }
        repeat(5) {
            assertFalse("https://www.google.com/".isSiteLinkBlocked())
        }
    }

    @Test
    fun subdomainsOfRestrictedHostsAreBlocked() {
        assertTrue("https://de.xhamster.com/videos/1".isSiteLinkBlocked())
        assertTrue("https://cdn.xvideos.com/a".isSiteLinkBlocked())
    }

    /** The module blocks YouTube by name; the old matcher left it to a check the JVM could not run. */
    @Test
    fun youtubeIsBlockedByItsOwnList() {
        listOf("https://www.youtube.com/watch?v=abc", "https://youtu.be/abc", "https://m.youtube.com/shorts/a")
            .forEach { assertTrue("expected blocked: $it", it.isSiteLinkBlocked()) }
    }
}
