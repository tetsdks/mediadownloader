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
 * The last net under the deny list: a host that names what the site is.
 *
 * A named list of domains cannot keep up - a new one opens faster than anyone can add it - so a
 * host whose own name says what it is gets turned away without being listed. The risk is the
 * other direction, and it is a real one: "anal" opens "analytics", "sex" sits inside "essex", and
 * blocking google-analytics.com or essex.gov.uk would be a far worse bug than missing one adult
 * domain. Every host below was measured against the rule; the ones that must keep working are
 * listed first, because they are what the strictness of each word was chosen for.
 */
class AdultHostWordsTest {

    /* The words are read on the host only. These urls say one of them in a path or a query,
     * which says nothing about whose site it is. */
    @Test
    fun aWordInThePathDoesNotBlockTheSite() {
        listOf(
            "https://examplevideos.com/vidssex",
            "https://etcvide.com/sex",
            "https://www.example.com/search?q=porn",
            "https://news.example.com/article/anal-blood-test-results",
        ).forEach { assertFalse("blocked on its path: $it", it.isSiteLinkBlocked()) }
    }

    @Test
    fun realSitesKeepWorking() {
        listOf(
            /* "anal" - the sharpest case. Every one of these opens with it or contains it. */
            "https://www.google-analytics.com/collect",
            "https://analytics.google.com/",
            "https://www.analog.com/en/index.html",
            "https://www.canalplus.fr/",
            "https://www.panalpina.com/",
            "https://www.analyticsvidhya.com/blog/",
            /* "sex" inside an English place name. */
            "https://www.essex.gov.uk/",
            "https://www.sussex.ac.uk/",
            "https://www.middlesex.edu/",
            "https://www.essexlive.news/",
            /* "escort" as a car and as a tour. */
            "https://fordescortclub.com/",
            "https://www.escortedtours.com/",
            /* And the sites this app is for. x.com is the one to watch: it is a substring of
             * sex.com, netflix.com and flix.com, which is how the twitter script once reached a
             * porn page. */
            "https://x.com/someone/status/123",
            "https://twitter.com/someone/status/123",
            "https://api.twitter.com/1.1/x",
            "https://www.netflix.com/title/123",
            "https://www.facebook.com/reel/123",
            "https://www.instagram.com/reel/abc/",
            "https://www.threads.com/@a/post/b",
            "https://www.tiktok.com/@user/video/123",
            "https://www.dailymotion.com/video/x8da0md",
            "https://www.linkedin.com/posts/abc",
            "https://www.pinterest.com/pin/123/",
            "https://www.imdb.com/title/tt0111161/",
            "https://www.ted.com/talks/abc",
            "https://9gag.com/gag/abc",
            "https://vimeo.com/123456",
            "https://rumble.com/v1abc-title.html",
            "https://www.twitch.tv/videos/123",
            "https://someblog.tumblr.com/post/123",
            "https://www.snackvideo.com/@a/video/123",
            "https://mojapp.in/@a/video/123",
            "https://www.pexels.com/photo/123/",
            "https://www.bbc.com/news",
            "https://en.wikipedia.org/wiki/Sussex",
        ).forEach { assertFalse("wrongly blocked: $it", it.isSiteLinkBlocked()) }
    }

    @Test
    fun hostsThatNameThemselvesAreBlocked() {
        listOf(
            "https://pornhub.com/view_video.php?viewkey=abc",
            "https://xxxtube.net/v/1",
            "https://zzzporn.net/v/1",
            "https://nudepics.net/v/1",
            "https://milfzone.tv/v/1",
            "https://boobsgallery.com/v/1",
            "https://hentaihaven.org/v/1",
            "https://nsfw.example-tube.net/v/1",
            "https://eroticfilms.tv/v/1",
            "https://camgirls.live/v/1",
            /* "sex" with a clear left edge, which is where a site name starts. */
            "https://sexvideos.com/v/1",
            "https://video-sex.net/v/1",
            "https://www.sex.com/",
            /* "anal" and "escort" standing alone. */
            "https://anal.com/v/1",
            "https://anal-videos.net/v/1",
            "https://xxx-anal.tv/v/1",
            "https://escort-directory.net/",
            /* Still covered by the older rules, which run first. */
            "https://xhamster19.com/videos/1",
            "https://www.brazzers.com/video/123",
        ).forEach { assertTrue("not blocked: $it", it.isSiteLinkBlocked()) }
    }

    /**
     * The brand names, which are words here for one reason: a mirror whose suffix is a word.
     *
     * The mirror rule only follows digits or a dash - xhamster19 and xhamster-mirror are caught
     * by it, xhamsterlive is not, because "live" is neither. These names are coined strings that
     * no ordinary domain contains, so unlike "sex" and "anal" they need no edge guard.
     */
    @Test
    fun brandMirrorsWithWordsAreBlocked() {
        listOf(
            "https://xhamsterlive.com/",
            "https://xhamsterhd.net/v/1",
            "https://xnxxhd.tv/v/1",
            "https://brazzersnetwork.com/",
            "https://spankwire.com/v/1",
            "https://spankbang.party/v/1",
            "https://chaturbate.com/",
            "https://bongacams.com/",
            "https://camsoda.com/",
            "https://stripchat.global/",
            "https://bdsmstreak.com/",
            /* "xvideos" has a left edge, for the same reason "sex" does - see below. */
            "https://xvideos99.com/v/1",
            "https://hd-xvideos.net/v/1",
        ).forEach { assertTrue("not blocked: $it", it.isSiteLinkBlocked()) }
    }

    /**
     * What the left edge on "xvideos" is paying for.
     *
     * "xvideos" is "x" + "videos", and a domain ending in "videos" is ordinary - foxvideos and
     * maxvideos are the same shape as analog.com is for "anal". Matching it anywhere would have
     * blocked all of these; the cost of the edge is that freexvideos.com is missed, which is the
     * same trade this rule already accepts for freesexvids.com.
     */
    @Test
    fun ordinaryDomainsEndingInVideosKeepWorking() {
        listOf(
            "https://foxvideos.com/",
            "https://maxvideos.com/",
            "https://phoenixvideos.co.uk/",
            "https://www.remixvideos.net/",
        ).forEach { assertFalse("wrongly blocked: $it", it.isSiteLinkBlocked()) }
    }

    /**
     * The host is the string's own host, not one quoted inside it.
     *
     * The host was read by finding the first "https://..." anywhere in the string. An outer host
     * written without a scheme therefore lost to a url sitting in its own query, and
     * "sex.com/?ref=https://x.com" answered "x.com" and was not blocked. This reaches the
     * matcher through the paste screen and the copied-link sheet, both of which take whatever is
     * on the clipboard.
     */
    @Test
    fun theOuterHostWins() {
        listOf(
            "sex.com/?ref=https://x.com",
            "pornhub.com/?next=https://www.google.com/",
            "xhamster19.com#https://vimeo.com/1",
        ).forEach { assertTrue("read the inner url instead: $it", it.isSiteLinkBlocked()) }

        /* And the share case still works: prose in front leaves nothing parseable at the start,
         * so the embedded url is still the answer. */
        listOf(
            "look at this https://x.com/someone/status/1",
            "check it out: https://www.bbc.com/news",
        ).forEach { assertFalse("wrongly blocked: $it", it.isSiteLinkBlocked()) }
    }
}
