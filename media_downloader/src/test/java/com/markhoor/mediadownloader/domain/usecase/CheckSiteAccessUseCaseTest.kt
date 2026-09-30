package com.markhoor.mediadownloader.domain.usecase

import com.markhoor.mediadownloader.core.Constants.Hosts
import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.domain.policy.RestrictedCategory
import com.markhoor.mediadownloader.domain.policy.RestrictedSites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckSiteAccessUseCaseTest {

    private val strict = CheckSiteAccessUseCase(strictSupportedSitesOnly = true, extraBlockedHosts = emptySet())
    private val generic = CheckSiteAccessUseCase(strictSupportedSitesOnly = false, extraBlockedHosts = emptySet())

    private fun assertAccess(expected: SiteAccess, check: CheckSiteAccessUseCase, urls: List<String>) =
        urls.forEach { assertEquals(it, expected, check(it)) }

    // region Blocked, in every configuration

    @Test
    fun `listed adult sites and their subdomains are blocked`() = listOf(strict, generic).forEach { check ->
        assertAccess(
            SiteAccess.Blocked, check,
            listOf(
                "https://www.xhamster.com/videos/abc", "https://xvideos.com/video123",
                "https://www.brazzers.com/video/123", "https://xnxx.com/some/path",
                "https://xnxx.health/video-abc/", "https://inxxx.com/v/abc",
                "https://www.pornhub.com/view_video.php?viewkey=abc", "https://stripchat.com/model",
                "https://xhamster.desi/videos/123", "https://de.xhamster.com/videos/1",
                "https://cdn.xvideos.com/a",
                // Tubes whose names carry no adult word, and xHamster's stream CDN.
                "https://www.youjizz.com/videos/a-1.html", "https://motherless.com/ABC123",
                "https://thisvid.com/videos/a/", "https://www.empflix.com/a/b-1",
                "https://hdzog.com/videos/1/a/", "https://video-am-a.xhpingcdn.com/key/_TPL_.av1.mp4.m3u8",
            ),
        )
    }

    @Test
    fun `numbered and dashed mirrors are blocked`() = assertAccess(
        SiteAccess.Blocked, generic,
        listOf(
            "https://xhamster19.com/videos/abc", "https://xhamster21.desi/",
            "https://xhamster-mirror.com/", "https://xvideos9.com/video1",
            "https://pornhub3.org/view", "https://brazzers12.com/", "https://xnxx7.com/video-abc/",
        ),
    )

    @Test
    fun `hosts that name themselves are blocked`() = assertAccess(
        SiteAccess.Blocked, generic,
        listOf(
            "https://xxxtube.net/v/1", "https://zzzporn.net/v/1", "https://nudepics.net/v/1",
            "https://milfzone.tv/v/1", "https://boobsgallery.com/v/1", "https://hentaihaven.org/v/1",
            "https://nsfw.example-tube.net/v/1", "https://eroticfilms.tv/v/1",
            "https://camgirls.live/v/1", "https://sexvideos.com/v/1", "https://video-sex.net/v/1",
            "https://www.sex.com/", "https://anal.com/v/1", "https://anal-videos.net/v/1",
            "https://escort-directory.net/", "https://xhamsterlive.com/", "https://xnxxhd.tv/v/1",
            "https://brazzersnetwork.com/", "https://spankbang.party/v/1", "https://chaturbate.com/",
            "https://bongacams.com/", "https://camsoda.com/", "https://stripchat.global/",
            "https://bdsmstreak.com/", "https://xvideos99.com/v/1", "https://hd-xvideos.net/v/1",
        ),
    )

    @Test
    fun `youtube is blocked`() = listOf(strict, generic).forEach { check ->
        assertAccess(
            SiteAccess.Blocked, check,
            listOf(
                "https://www.youtube.com/watch?v=abc", "https://m.youtube.com/shorts/1",
                "https://youtu.be/1", "https://www.youtube-nocookie.com/embed/1",
            ),
        )
    }

    @Test
    fun `a blocked url written inside another url is blocked`() = listOf(strict, generic).forEach { check ->
        assertAccess(
            SiteAccess.Blocked, check,
            listOf(
                "https://example.com/go?ref=https://inxxx.com/v/abc",
                "https://www.reddit.com/?u=https://www.pornhub.com/view_video.php?viewkey=abc",
                "sex.com/?ref=https://x.com",
                "pornhub.com/?next=https://www.google.com/",
                "xhamster19.com#https://vimeo.com/1",
            ),
        )
    }

    @Test
    fun `hosts the app adds are blocked on top of the built-in list`() {
        val check = CheckSiteAccessUseCase(strictSupportedSitesOnly = true, extraBlockedHosts = setOf("https://www.Reddit.com/"))
        assertEquals(SiteAccess.Blocked, check("https://old.reddit.com/r/aww"))
        assertEquals(SiteAccess.Allowed, check("https://www.pinterest.com/pin/1/"))
    }

    // endregion

    // region Never blocked by mistake

    @Test
    fun `a word in a path or query does not block the site`() = assertAccess(
        SiteAccess.Allowed, generic,
        listOf(
            "https://examplevideos.com/vidssex", "https://etcvide.com/sex",
            "https://www.example.com/search?q=porn",
            "https://news.example.com/article/anal-blood-test-results",
        ),
    )

    @Test
    fun `real sites that contain an adult word keep working`() = assertAccess(
        SiteAccess.Allowed, generic,
        listOf(
            "https://www.google-analytics.com/collect", "https://analytics.google.com/",
            "https://www.analog.com/en/index.html", "https://www.canalplus.fr/",
            "https://www.panalpina.com/", "https://www.analyticsvidhya.com/blog/",
            "https://www.essex.gov.uk/", "https://www.sussex.ac.uk/", "https://www.middlesex.edu/",
            "https://www.essexlive.news/", "https://fordescortclub.com/",
            "https://www.escortedtours.com/", "https://foxvideos.com/", "https://maxvideos.com/",
            "https://phoenixvideos.co.uk/", "https://www.remixvideos.net/",
            "https://www.wickedlocal.com/story/1", "https://tubefilter.com/2024/01/01/news/",
        ),
    )

    @Test
    fun `lookalikes of supported sites are not mistaken for them`() {
        assertAccess(
            SiteAccess.Unsupported, strict,
            listOf(
                "https://notreddit.com/", "https://reddit.com.evil.net/", "https://fakevimeo.com/",
                "https://ted.com.phish.io/",
            ),
        )
        assertEquals(SiteAccess.Allowed, strict("https://x.com/a/status/1"))
        assertEquals(SiteAccess.Blocked, strict("https://www.sex.com/a/status/1"))
    }

    @Test
    fun `the share case reads the url inside the text`() = assertAccess(
        SiteAccess.Allowed, strict,
        listOf("look at this https://x.com/someone/status/1", "check it out: https://www.bbc.com/news"),
    )

    // endregion

    // region Strict and generic modes

    @Test
    fun `strict mode allows only supported sites`() {
        assertAccess(
            SiteAccess.Allowed, strict,
            listOf(
                "https://www.reddit.com/r/aww/", "https://m.facebook.com/watch",
                "https://en.wikipedia.org/wiki/Cat", "https://upload.wikimedia.org/a/b.jpg",
                "https://player.vimeo.com/video/1", "reddit.com", "https://www.bbc.co.uk/news?x=1#y",
            ),
        )
        assertAccess(
            SiteAccess.Unsupported, strict,
            listOf("https://stackoverflow.com/questions", "https://example.com/"),
        )
    }

    @Test
    fun `generic mode allows any site that is not blocked`() {
        assertEquals(SiteAccess.Allowed, generic("https://stackoverflow.com/questions"))
        assertEquals(SiteAccess.Allowed, generic("https://www.reddit.com/r/videos"))
    }

    @Test
    fun `something that is not a web page is unsupported in both modes`() =
        listOf(strict, generic).forEach { check ->
            assertAccess(SiteAccess.Unsupported, check, listOf("", "about:blank", "data:text/html,x"))
        }

    // endregion

    // region The lists themselves

    @Test
    fun `every supported entry is shaped like a host`() = Hosts.SUPPORTED.forEach { entry ->
        assertFalse("has a scheme: $entry", entry.contains("://"))
        assertFalse("has a path: $entry", entry.contains("/"))
        assertFalse("has www: $entry", entry.startsWith("www."))
        assertTrue("not lowercase: $entry", entry == entry.lowercase())
        assertTrue("no dot: $entry", entry.contains("."))
        assertEquals("does not match itself: $entry", SiteAccess.Allowed, strict("https://$entry/"))
    }

    /** The two lists are edited separately; a new block rule must never swallow a supported site. */
    @Test
    fun `no supported site is blocked`() = Hosts.SUPPORTED.forEach { entry ->
        assertFalse("supported but blocked: $entry", generic("https://$entry/") == SiteAccess.Blocked)
    }

    // endregion

    // region Restricted categories lifted by the configuration

    private fun check(strictMode: Boolean, vararg allowed: RestrictedCategory) =
        CheckSiteAccessUseCase(strictSupportedSitesOnly = strictMode, extraBlockedHosts = setOf("example.com"),
            allowedRestrictions = allowed.toSet())

    private val youTube = listOf("https://www.youtube.com/watch?v=1", "https://youtu.be/abc", "https://m.youtube.com/shorts/1")
    private val adult = listOf("https://www.xvideos.com/video123", "https://xhamster19.com/videos/abc", "https://www.pornhub.com/view_video.php?viewkey=1")

    @Test
    fun `youtube's pages and player hosts are the youtube category, adult sites and mirrors the adult one`() {
        assertEquals(RestrictedCategory.YouTube, RestrictedSites.categoryOf("youtube.com"))
        assertEquals(RestrictedCategory.YouTube, RestrictedSites.categoryOf("rr3---sn-a.googlevideo.com"))
        assertEquals(RestrictedCategory.YouTube, RestrictedSites.categoryOf("i.ytimg.com"))
        assertEquals(RestrictedCategory.Adult, RestrictedSites.categoryOf("xvideos.com"))
        assertEquals(RestrictedCategory.Adult, RestrictedSites.categoryOf("xhamster19.com"))
        assertEquals(null, RestrictedSites.categoryOf("notgooglevideo.com"))
        assertEquals(null, RestrictedSites.categoryOf("dailymotion.com"))
    }

    @Test
    fun `by default both categories are blocked in both modes`() = listOf(true, false).forEach { strictMode ->
        assertAccess(SiteAccess.Blocked, check(strictMode), youTube + adult)
    }

    @Test
    fun `each switch lifts only its own category`() = listOf(true, false).forEach { strictMode ->
        assertAccess(SiteAccess.Allowed, check(strictMode, RestrictedCategory.YouTube), youTube)
        assertAccess(SiteAccess.Blocked, check(strictMode, RestrictedCategory.YouTube), adult)
        assertAccess(SiteAccess.Allowed, check(strictMode, RestrictedCategory.Adult), adult)
        assertAccess(SiteAccess.Blocked, check(strictMode, RestrictedCategory.Adult), youTube)
    }

    @Test
    fun `an allowed category counts as supported in strict mode, other unlisted sites still do not`() {
        val both = check(true, RestrictedCategory.YouTube, RestrictedCategory.Adult)
        assertAccess(SiteAccess.Allowed, both, youTube + adult)
        assertAccess(SiteAccess.Unsupported, both, listOf("https://samplelib.com/sample-mp4.html"))
    }

    @Test
    fun `the app's own blocked hosts stay blocked whatever is allowed`() {
        val both = check(false, RestrictedCategory.YouTube, RestrictedCategory.Adult)
        assertAccess(SiteAccess.Blocked, both, listOf("https://example.com/v", "https://cdn.example.com/a.mp4"))
    }

    @Test
    fun `a link carrying a restricted site follows the same switch`() {
        val carried = "https://go.test/out?to=https://www.youtube.com/watch?v=1"
        assertEquals(SiteAccess.Blocked, check(false)(carried))
        assertEquals(SiteAccess.Allowed, check(false, RestrictedCategory.YouTube)(carried))
    }

    @Test
    fun `a media host is judged by its host alone, never by a link in its query`() {
        val default = check(false)
        assertTrue(default.blocksHostOf("https://rr3---sn-a.googlevideo.com/videoplayback?id=1"))
        assertFalse(default.blocksHostOf("https://cdn.test/video.mp4?ref=https://www.youtube.com/watch?v=1"))
        assertFalse(check(false, RestrictedCategory.YouTube).blocksHostOf("https://rr3---sn-a.googlevideo.com/videoplayback?id=1"))
    }

    // endregion
}
