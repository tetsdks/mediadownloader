package com.markhoor.mediadownloader.data.browser

import com.markhoor.mediadownloader.IncognitoPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The counting that decides when an incognito session is thrown away. Two incognito tabs share one
 * jar, so emptying it when the first of them closes would wipe the session the other is still
 * browsing in - and never emptying it would leave an incognito session on disk, which is the one
 * thing it must not be.
 *
 * What a real profile does with the jar is the device's answer, not this one's; see
 * `IncognitoDeviceCheck`.
 */
class IncognitoProfileTest {

    private var emptied = 0

    private fun profile(supported: Boolean = true) =
        IncognitoProfile(isSupported = { supported }, emptyJar = { emptied++ })

    @Test
    fun `the jar is emptied by the last page out, not the first`() {
        val profile = profile()

        profile.claim()
        profile.claim()
        profile.claim()
        assertEquals(3, profile.claims)

        profile.release()
        profile.release()
        assertEquals("emptied while a page was still browsing in it", 0, emptied)
        assertEquals(1, profile.claims)

        profile.release()
        assertEquals("the last page out left the session on disk", 1, emptied)
        assertEquals(0, profile.claims)
    }

    /**
     * Opening incognito again after the last one closed is a new session, and it has to be able to
     * end the same way. The count used to be able to go below zero, which would have taken two
     * closes to empty the jar the second time round.
     */
    @Test
    fun `a release with nothing claimed is not held against the next session`() {
        val profile = profile()

        profile.release()
        assertEquals(0, profile.claims)
        assertEquals("nothing was claimed, so nothing was there to empty", 0, emptied)

        profile.claim()
        profile.release()
        assertEquals(1, emptied)
    }

    /**
     * A WebView that cannot keep a second jar is told so rather than being promised one. There is
     * then no profile to put a page in, and nothing of its own to empty.
     */
    @Test
    fun `without a second profile it says so and asks for nothing`() {
        val profile = profile(supported = false)

        assertEquals(IncognitoPrivacy.SharedJar, profile.privacy)
        profile.claim()
        profile.release()
        // emptyJar is still called - the counting is the same either way - but the real one knows
        // there is nothing to empty. What must not happen is a page being told it is isolated.
        assertFalse(profile.privacy == IncognitoPrivacy.Separate)
    }

    @Test
    fun `with a second profile it says that instead`() {
        assertEquals(IncognitoPrivacy.Separate, profile(supported = true).privacy)
    }
}
