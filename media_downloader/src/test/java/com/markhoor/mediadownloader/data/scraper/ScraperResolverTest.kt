package com.markhoor.mediadownloader.data.scraper

import com.markhoor.mediadownloader.data.browser.testTube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperResolverTest {

    private class Named(val name: String) : SiteScraper() {
        override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = null
        override fun toString() = name
    }

    /** Stands in for the category test the module makes; the rule under test is the resolver's. */
    private val tube = testTube(
        isRestrictedSite = { host ->
            listOf("pornhub.com", "xnxx.com", "xhamster.com", "eporner.com").any { host.endsWith(it) }
        },
    )

    private fun resolver(twitter: SiteScraper? = Named("twitter")) = ScraperResolver(
        facebookVideo = Named("facebookVideo"),
        facebookShare = Named("facebookShare"),
        instagram = Named("instagram"),
        linkedIn = Named("linkedIn"),
        imdb = Named("imdb"),
        tikTok = Named("tikTok"),
        tube = tube,
        twitter = twitter,
        dailymotion = Named("dailymotion"),
        pinterest = Named("pinterest"),
        getInDevice = Named("getInDevice"),
    )

    private fun scrapersFor(url: String, twitter: SiteScraper? = Named("twitter")) =
        resolver(twitter).scrapersFor(url).map { if (it === tube) "tube" else it.toString() }

    private fun backupsFor(url: String, twitter: SiteScraper? = Named("twitter")) =
        resolver(twitter).backupsFor(url).map { it.toString() }

    @Test
    fun `each site's links reach its own scrapers`() {
        mapOf(
            "https://www.facebook.com/reel/123456789012345" to listOf("facebookVideo"),
            "https://m.facebook.com/watch/?v=123" to listOf("facebookVideo"),
            "https://www.facebook.com/share/v/abc/" to listOf("facebookVideo"),
            "https://www.facebook.com/share/p/abc/" to listOf("facebookShare"),
            "https://www.instagram.com/reel/abc/" to listOf("instagram"),
            "https://www.instagram.com/p/abc/" to listOf("instagram"),
            "https://www.threads.com/@someone/post/abc" to listOf("instagram"),
            // The link Threads' own share sheet hands out; the post is read out of its page.
            "https://www.threads.com/share/BATDAqdzRY/" to listOf("instagram"),
            "https://www.linkedin.com/posts/someone_activity-1" to listOf("linkedIn"),
            // The shortener LinkedIn hands out when a post is shared from its app.
            "https://lnkd.in/p/ekARMhxS" to listOf("linkedIn"),
            "https://www.tiktok.com/@someone/video/7684717011754093844" to listOf("tikTok"),
            "https://www.tiktok.com/foryou" to listOf("tikTok"),
            "https://x.com/someone/status/1234567890" to listOf("twitter"),
            "https://mobile.twitter.com/someone/status/987654321?s=20" to listOf("twitter"),
            "https://www.dailymotion.com/video/x8da0md" to listOf("dailymotion"),
            "https://www.dailymotion.com/player/metadata/video/x8da0md" to listOf("dailymotion"),
            "https://www.pinterest.com/pin/62628251062820323/" to listOf("pinterest"),
            "https://www.imdb.com/video/vi3481586201/" to listOf("imdb"),
            "https://pin.it/abc123" to listOf("pinterest"),
            // One reader for the tube sites: what makes a link its own is the category of the
            // host and a path that names a video, not a list of sites.
            "https://www.pornhub.com/view_video.php?viewkey=abc123" to listOf("tube"),
            "https://www.xnxx.com/video-1ajfxte3/desi" to listOf("tube"),
            "https://xhamster.com/videos/some-slug-xh7FMCw" to listOf("tube"),
            "https://www.eporner.com/video-OzG1R7X34gv/indian-babe" to listOf("tube"),
        ).forEach { (url, expected) -> assertEquals(url, expected, scrapersFor(url)) }
    }

    @Test
    fun `links that name no post have no scraper`() {
        listOf(
            "https://www.threads.com/",
            "https://m.facebook.com/watch/",
            "https://x.com/someone",
            "https://www.instagram.com/someone/",
            "https://www.pinterest.com/search/pins/?q=cats",
            // A tube site's own pages that name no video.
            "https://www.pornhub.com/",
            "https://xhamster.com/categories",
            "https://www.reddit.com/r/videos/",
            "",
        ).forEach { assertTrue(it, scrapersFor(it).isEmpty()) }
    }

    /** A url that merely mentions a site is not that site: `sex.com` ends in the letters `x.com`. */
    @Test
    fun `lookalike hosts are not the site`() {
        listOf(
            "https://www.sex.com/a/status/1",
            "https://notx.com/u/status/1",
            "https://netflix.com/u/status/1",
            "https://example.com/?ref=https://www.instagram.com/reel/abc/",
        ).forEach { assertTrue(it, scrapersFor(it).isEmpty()) }
    }

    @Test
    fun `without a tweeload key X links have no scraper of ours`() {
        assertTrue(scrapersFor("https://x.com/someone/status/1", twitter = null).isEmpty())
        // The backup is not a scraper of ours, and is the only reader such a link has left.
        assertEquals(
            listOf("getInDevice"),
            backupsFor("https://x.com/someone/status/1", twitter = null),
        )
    }

    /**
     * The third-party backup, where it was measured to read the site. It is never in
     * [ScraperResolver.scrapersFor]: it must not win a race against a reader of ours, and the
     * browser must not stand aside for a site only somebody else's service can read.
     */
    @Test
    fun `the backup is offered for the sites it reads, and no others`() {
        mapOf(
            "https://www.facebook.com/reel/123456789012345" to true,
            "https://www.facebook.com/share/p/abc/" to true,
            "https://www.tiktok.com/@someone/video/7684717011754093844" to true,
            "https://x.com/someone/status/1234567890" to true,
            "https://www.linkedin.com/posts/someone_activity-1" to true,
            "https://www.pinterest.com/pin/62628251062820323/" to true,
            // Instagram and Threads have it inside their own reader, which prefers the session.
            "https://www.instagram.com/reel/abc/" to false,
            "https://www.threads.com/@someone/post/abc" to false,
            // It cannot read these two at all.
            "https://www.dailymotion.com/video/x8da0md" to false,
            "https://www.imdb.com/video/vi3481586201/" to false,
            // An adult site is never named to a third party.
            "https://www.pornhub.com/view_video.php?viewkey=abc123" to false,
            "https://example.com/some/page" to false,
        ).forEach { (url, expected) ->
            assertEquals(url, expected, backupsFor(url).isNotEmpty())
        }
    }

    /** WebView urls can be megabytes; the answer must come back, and must still be right. */
    @Test
    fun `huge urls are answered, not thrown`() {
        assertEquals(listOf("twitter"), scrapersFor("https://x.com/" + "b".repeat(3_000_000) + "/status/1"))
        assertTrue(scrapersFor("https://sex.com/" + "b".repeat(3_000_000) + "/status/1").isEmpty())
        assertTrue(scrapersFor("data:image/png;base64," + "A".repeat(3_000_000)).isEmpty())
    }
}
