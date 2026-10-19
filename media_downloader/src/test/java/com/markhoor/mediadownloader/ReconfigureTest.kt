package com.markhoor.mediadownloader

import com.markhoor.mediadownloader.domain.models.MediaDownloaderNotInitializedException
import com.markhoor.mediadownloader.domain.models.SiteAccess
import com.markhoor.mediadownloader.domain.policy.RestrictedCategory
import com.markhoor.mediadownloader.domain.usecase.CheckSiteAccessUseCase
import com.markhoor.mediadownloader.domain.usecase.SiteAccessSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Changing the configuration after start. A host app with a settings screen must be able to lift a
 * block or leave strict mode without restarting the process, and must be told plainly about the
 * settings a call could not change rather than left thinking it did.
 */
class ReconfigureTest {

    private val base = MediaDownloaderConfig()

    // region What a change can and cannot reach

    @Test
    fun `a change to a setting read once is named, and the rest of the change still lands`() {
        val wanted = base.copy(
            allowAdultSites = true,
            performanceMode = PerformanceMode.LowEnd,
            twitterApiKey = "another",
        )

        val fixed = base.fixedSettingsChangedBy(wanted)
        val applied = wanted.keepingFixedSettingsOf(base)

        assertEquals(setOf(FixedSetting.PerformanceMode, FixedSetting.TwitterApiKey), fixed)
        // Named, not refused: one unchangeable setting must not cost the call its other changes.
        assertTrue("the change that could be made was dropped", applied.allowAdultSites)
        assertEquals(base.performanceMode, applied.performanceMode)
        assertEquals(base.twitterApiKey, applied.twitterApiKey)
    }

    @Test
    fun `a change to nothing fixed is applied whole and names nothing`() {
        val wanted = base.copy(
            strictSupportedSitesOnly = false,
            allowYouTube = true,
            extraBlockedHosts = setOf("example.com"),
            instagramCookie = "sessionid=fresh",
            downloadFolderName = "Elsewhere",
        )

        assertEquals(emptySet<FixedSetting>(), base.fixedSettingsChangedBy(wanted))
        assertEquals(wanted, wanted.keepingFixedSettingsOf(base))
    }

    @Test
    fun `the same configuration again changes and names nothing`() {
        assertEquals(emptySet<FixedSetting>(), base.fixedSettingsChangedBy(base))
        assertEquals(base, base.keepingFixedSettingsOf(base))
    }

    @Test
    fun `a configuration that cannot work is refused, with the reason`() {
        val folder = runCatching { base.copy(downloadFolderName = "a/b").requireUsable() }.exceptionOrNull()
        assertTrue(folder is IllegalArgumentException)
        assertTrue(folder?.message.orEmpty().contains("downloadFolderName"))

        val icon = runCatching { base.copy(notificationIcon = 0).requireUsable() }.exceptionOrNull()
        assertTrue(icon is IllegalArgumentException)
        assertTrue(icon?.message.orEmpty().contains("notificationIcon"))
    }

    // endregion

    // region That the change is actually read again

    /**
     * The point of the whole thing: one site-access use case lives for the process and is held by
     * the browser screens, the parser and the downloads already running, so it has to answer from
     * the configuration as it stands rather than the one it was built with.
     */
    @Test
    fun `site access answers from the configuration as it stands`() {
        var config = base.copy(strictSupportedSitesOnly = true)
        val access = CheckSiteAccessUseCase {
            SiteAccessSettings(
                strictSupportedSitesOnly = config.strictSupportedSitesOnly,
                extraBlockedHosts = config.extraBlockedHosts,
                allowedRestrictions = config.allowedRestrictions,
            )
        }
        val unlisted = "https://www.example.com/watch/1"
        val adult = "https://www.pornhub.com/view_video.php?viewkey=1"

        assertEquals(SiteAccess.Unsupported, access(unlisted))
        assertEquals(SiteAccess.Blocked, access(adult))

        config = config.copy(strictSupportedSitesOnly = false, allowAdultSites = true)

        assertEquals("strict mode was left and nothing noticed", SiteAccess.Allowed, access(unlisted))
        assertEquals("the category was allowed and nothing noticed", SiteAccess.Allowed, access(adult))

        config = config.copy(extraBlockedHosts = setOf("Example.COM"))

        // Normalised again when the setting changes, not once at the start.
        assertEquals(SiteAccess.Blocked, access(unlisted))
    }

    @Test
    fun `a host blocked on top of the list is lifted again when the host app drops it`() {
        var blocked = setOf("example.com")
        val access = CheckSiteAccessUseCase {
            SiteAccessSettings(strictSupportedSitesOnly = false, extraBlockedHosts = blocked)
        }
        val url = "https://www.example.com/a"

        assertEquals(SiteAccess.Blocked, access(url))
        assertTrue(access.blocksHostOf(url))

        blocked = emptySet()

        assertEquals(SiteAccess.Allowed, access(url))
        assertTrue(!access.blocksHostOf(url))
    }

    /** An allowed category is still only the one allowed; the others stay blocked. */
    @Test
    fun `allowing one category does not lift the other`() {
        var allowed = setOf(RestrictedCategory.YouTube)
        val access = CheckSiteAccessUseCase {
            SiteAccessSettings(
                strictSupportedSitesOnly = false,
                extraBlockedHosts = emptySet(),
                allowedRestrictions = allowed,
            )
        }

        assertEquals(SiteAccess.Allowed, access("https://www.youtube.com/watch?v=1"))
        assertEquals(SiteAccess.Blocked, access("https://www.pornhub.com/view_video.php?viewkey=1"))

        allowed = setOf(RestrictedCategory.Adult)

        assertEquals(SiteAccess.Blocked, access("https://www.youtube.com/watch?v=1"))
        assertEquals(SiteAccess.Allowed, access("https://www.pornhub.com/view_video.php?viewkey=1"))
    }

    // endregion

    /** Before initialize there is nothing to read or change, and saying so beats a wrong answer. */
    @Test
    fun `reading or changing the configuration before initialize fails plainly`() {
        if (MediaDownloader.isInitialized) return
        assertTrue(runCatching { MediaDownloader.config }.exceptionOrNull() is MediaDownloaderNotInitializedException)
        assertTrue(
            runCatching { MediaDownloader.reconfigure { it } }.exceptionOrNull()
                is MediaDownloaderNotInitializedException,
        )
    }
}
