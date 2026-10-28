package com.markhoor.mediadownloader.data.scraper.fallback

import com.markhoor.mediadownloader.core.Constants.GetInDevice
import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.data.download.FakeMediaServer
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.MediaGoneException
import com.markhoor.mediadownloader.data.scraper.NoMediaException
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The third-party resolver, against the answers the live api actually gives. The shapes here were
 * taken from it in October 2026 - a reel, a Threads photograph, and its refusal without a token -
 * because this reader exists to survive the api being rebuilt, which has happened once already.
 */
class GetInDeviceScraperTest {

    private val reel = "https://www.instagram.com/reel/DQN9eI6CF55"
    private val video = "https://scontent-waw2-1.cdninstagram.com/o1/v/t2/f2/m86/AQONmLwPf786MK.mp4"
    private val cover = "https://scontent-waw2-1.cdninstagram.com/v/t51.71878-15/569850018.jpg"
    private val token = """{"token":"4d08c6ff10de05de.1791800414596"}"""

    /** A reel: one video labelled by its frame size, and the same file listed again as its sound. */
    private val reelAnswer = """
        {"source":"instagram","title":null,"thumbnail":"$cover","duration":null,
         "videos":[{"url":"$video","sig":"41bc2220","quality":"1080x1920","format":"mp4","size":null,
                    "is_hls":false,"hlsStreamUrl":null,"mergeParts":null,"order":0}],
         "audios":[{"url":"$video","quality":"audio","format":"m4a","size":null,"order":1}],
         "photos":[],"files":[]}
    """.trimIndent()

    private fun scraperOver(vararg served: Pair<String, String>): Pair<GetInDeviceScraper, FakeMediaServer> {
        val server = FakeMediaServer(served.associate { (url, body) -> url to body.toByteArray() })
        return GetInDeviceScraper(HttpFetcher(server.client, HttpClientFactory.json)) to server
    }

    @Test
    fun `a reel is read as one video, named by its short side`() = runTest {
        val (scraper, server) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to reelAnswer,
        )

        val media = scraper.scrape(reel).getOrThrow()

        val quality = media.qualities.single()
        assertEquals(video, quality.url)
        assertEquals(MediaType.Video, quality.type)
        assertEquals("1080p", quality.label)
        assertEquals(cover, media.thumbnailUrl)
        // The sound is the same file again; offering it as a second quality would be a lie.
        assertNull(quality.audioUrl)
        assertEquals("4d08c6ff10de05de.1791800414596", server.lastHeaders[GetInDevice.TOKEN_HEADER])
    }

    /**
     * The bot check in front of the api answers anything without a browser's name a challenge
     * page, which arrives here as a json error and costs the fallback every link it is asked for.
     * Measured against the live api in October 2026: `200` with this, `403` and a challenge with
     * no `User-Agent`, with ktor's and with okhttp's. Both calls are sent from the one header map,
     * so asking the finished scrape answers for the token call as well.
     */
    @Test
    fun `every call names a browser, or the bot check answers a page instead of json`() = runTest {
        val (scraper, server) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to reelAnswer,
        )

        scraper.scrape(reel).getOrThrow()

        assertEquals(GetInDevice.USER_AGENT, server.lastHeaders[Network.HEADER_USER_AGENT])
        assertTrue("not a browser's name: ${GetInDevice.USER_AGENT}", GetInDevice.USER_AGENT.startsWith("Mozilla/"))
    }

    @Test
    fun `without a token the api is not even asked`() = runTest {
        val (scraper, server) = scraperOver(GetInDevice.API_URL to reelAnswer)

        val failure = scraper.scrape(reel).exceptionOrNull()

        assertTrue("expected a failure, got $failure", failure != null)
        assertTrue("the link was asked for anyway: ${server.requests}", server.requests.none { it.contains("download") })
        // Not a nothing-found: no token is the api being unreadable, which is worth saying out
        // loud, and a link with no media behind it is not. They were the same silence until a bot
        // check in front of the api cost every signed-out Instagram post its only reader for a
        // fortnight with nothing in the log. See InstagramScraper's note on the resolver.
        assertFalse("an unreadable api was read as a link with nothing behind it", failure is NoMediaException)
    }

    /** What the api answers a request that carries no token; it is not media, and not a crash. */
    @Test
    fun `a refusal is read as nothing found`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """{"error":"Missing request token"}""",
        )

        assertTrue(scraper.scrape(reel).isFailure)
    }

    @Test
    fun `a Threads photograph is read as a picture`() = runTest {
        val picture = "https://scontent.cdninstagram.com/v/t51.2885-15/photo.jpg"
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"source":"threads","title":"A line the post itself carries","thumbnail":"$picture",
                 "videos":[],"audios":[],"photos":[{"url":"$picture","quality":"HD","format":"jpg","size":null,"order":0}]}
            """.trimIndent(),
        )

        val media = scraper.scrape("https://www.threads.com/@someone/post/Dd6YnykGgJb").getOrThrow()

        assertEquals(picture, media.qualities.single().url)
        assertEquals(MediaType.Image, media.qualities.single().type)
        assertEquals("Image", media.qualities.single().label)
        assertEquals("A line the post itself carries", media.title)
    }

    @Test
    fun `an album is read as one quality per picture, in the order given`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"source":"instagram","videos":[],"photos":[
                    {"url":"https://cdn.test/one.jpg","quality":"HD","order":0},
                    {"url":"https://cdn.test/two.jpg","quality":"HD","order":1}]}
            """.trimIndent(),
        )

        val media = scraper.scrape("https://www.instagram.com/p/DQN9eI6CF55").getOrThrow()

        assertEquals(listOf("Media_1", "Media_2"), media.qualities.map { it.label })
        assertEquals(listOf("https://cdn.test/one.jpg", "https://cdn.test/two.jpg"), media.qualities.map { it.url })
    }

    /** A stream is the url to take, and sound in a file of its own is joined to its video. */
    @Test
    fun `a stream and a video whose sound is apart are both read`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"source":"facebook","title":"A video","duration":"12",
                 "videos":[{"url":"https://cdn.test/v.mp4","quality":"1920x1080","size":"2048",
                            "is_hls":true,"hlsStreamUrl":"https://cdn.test/v.m3u8"},
                           {"url":"https://cdn.test/hd.mp4","quality":"720x1280","mergeParts":["a","b"]}],
                 "audios":[{"url":"https://cdn.test/sound.m4a","quality":"audio"}]}
            """.trimIndent(),
        )

        val media = scraper.scrape("https://www.facebook.com/reel/123456789012345").getOrThrow()

        val stream = media.qualities.first()
        assertEquals("https://cdn.test/v.m3u8", stream.url)
        assertEquals("1080p", stream.label)
        assertEquals(2048L, stream.sizeBytes)
        val joined = media.qualities.last()
        assertEquals("720p", joined.label)
        assertEquals("https://cdn.test/sound.m4a", joined.audioUrl)
        assertEquals(12_000L, media.durationMillis)
    }

    /** Sound alone is worth offering only where the api listed nothing to watch or look at. */
    @Test
    fun `an answer that is only sound is read as sound`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"source":"tiktok","videos":[],"photos":[],
                 "audios":[{"url":"https://cdn.test/music.m4a","quality":"audio"}]}
            """.trimIndent(),
        )

        val media = scraper.scrape("https://www.tiktok.com/@someone/video/7684717011754093844").getOrThrow()

        assertEquals(MediaType.Audio, media.qualities.single().type)
        assertEquals("https://cdn.test/music.m4a", media.qualities.single().url)
    }

    /**
     * What the live api answers for a Facebook link that does not exist: not an error, but a real
     * playable video off the Watch feed, titled `Facebook`. Handing that over would offer somebody
     * else's video as this link's, which is worse than finding nothing.
     */
    @Test
    fun `the site's own feed is not the post that was asked for`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"source":"facebook","title":"Facebook","author":null,
                 "thumbnail":"https://scontent-bos5-1.xx.fbcdn.net/v/t51.82787-10/801582366.jpg",
                 "videos":[{"url":"https://video-bos5-1.xx.fbcdn.net/o1/v/t2/f2/m367/AQOTYCNveS.mp4",
                            "quality":"HD","format":"mp4"}]}
            """.trimIndent(),
        )

        assertTrue(scraper.scrape("https://www.facebook.com/watch/?v=999999999999999").isFailure)
    }

    /**
     * The one refusal worth telling apart: the service reads a post as a signed-in account, so a
     * post it cannot find is gone rather than hidden. Taken live from a reel that did not exist.
     */
    @Test
    fun `a post the service says is gone is refused as gone, not as nothing`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"error":"The post or video could not be found. It may have been deleted or the link is incorrect."}
            """.trimIndent(),
        )

        val failure = scraper.scrape("https://www.instagram.com/reel/DUnGTG7CDDv").exceptionOrNull()

        assertTrue("expected a gone failure, got $failure", failure is MediaGoneException)
    }

    /** Its other refusals mean only that this reader got nothing; they say nothing about the post. */
    @Test
    fun `a timed-out refusal is only this reader coming back empty`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"error":"Request timed out. The server took too long to respond."}
            """.trimIndent(),
        )

        val failure = scraper.scrape("https://www.instagram.com/reel/DQN9eI6CF55").exceptionOrNull()

        assertTrue("expected a plain nothing-found, got $failure", failure is NoMediaException)
    }

    /**
     * Instagram answers a reel code it cannot resolve with the account's own picture and no video,
     * and with no title either - so nothing in the answer says it is not the reel. Taken live from
     * two made-up codes, which both gave this same picture.
     */
    @Test
    fun `an account's own picture is not the reel that was asked for`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"source":"instagram","title":null,"author":null,"duration":null,"viewCount":null,
                 "thumbnail":"https://scontent-muc2-1.cdninstagram.com/v/t51.75761-19/501750829.jpg",
                 "videos":[],"audios":[],"files":[],
                 "photos":[{"url":"https://scontent-muc2-1.cdninstagram.com/v/t51.75761-19/501750829.jpg",
                            "quality":"HD"}]}
            """.trimIndent(),
        )

        assertTrue(scraper.scrape("https://www.instagram.com/reel/ZZZZZZZZZZZ").isFailure)
    }

    /** The namespace is the whole of it: a picture the post really holds is read as before. */
    @Test
    fun `a post's own picture is still read when the account's is dropped`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"source":"instagram","title":null,
                 "photos":[{"url":"https://scontent-muc2-1.cdninstagram.com/v/t51.75761-19/avatar.jpg"},
                           {"url":"https://scontent-muc2-1.cdninstagram.com/v/t51.2885-15/the-post.jpg"}]}
            """.trimIndent(),
        )

        val media = scraper.scrape("https://www.instagram.com/p/DQN9eI6CF55").getOrThrow()

        val quality = media.qualities.single()
        assertEquals("https://scontent-muc2-1.cdninstagram.com/v/t51.2885-15/the-post.jpg", quality.url)
        assertEquals(MediaType.Image, quality.type)
        // One picture was left, so it is the media rather than an album entry.
        assertEquals(QualityLabels.IMAGE, quality.label)
    }

    /** Threads answers a post it does not have with its sign-up page, which carries a picture. */
    @Test
    fun `a sign-up page is not a post either`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"source":"threads","title":"Join Threads to share ideas, ask questions and more",
                 "videos":[],"photos":[{"url":"https://static.cdninstagram.com/logo.jpg","quality":"HD"}]}
            """.trimIndent(),
        )

        assertTrue(scraper.scrape("https://www.threads.com/@nobody/post/AAAAAAAAAAA").isFailure)
    }

    /** A sign-in page names itself after the site: `LinkedIn Login, Sign in`, with a picture. */
    @Test
    fun `a sign-in page named after its own site is not a post`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"source":"linkedin","title":"LinkedIn Login, Sign in","videos":[],
                 "photos":[{"url":"https://static.licdn.com/logo.png","quality":"HD"}]}
            """.trimIndent(),
        )

        assertTrue(scraper.scrape("https://www.linkedin.com/posts/someone_activity-1").isFailure)
    }

    /** The guard is the whole title, not a word in it: a post may well talk about the site. */
    @Test
    fun `a post whose caption merely names the site is kept`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to """
                {"source":"facebook","title":"My first day at the Facebook office",
                 "videos":[{"url":"https://cdn.test/day.mp4","quality":"1280x720"}]}
            """.trimIndent(),
        )

        val media = scraper.scrape("https://www.facebook.com/reel/123456789012345").getOrThrow()

        assertEquals("My first day at the Facebook office", media.title)
        assertEquals("720p", media.qualities.single().label)
    }

    @Test
    fun `an api that answers something else entirely costs this reader its answer and nothing more`() = runTest {
        val (scraper, _) = scraperOver(
            GetInDevice.TOKEN_URL to token,
            GetInDevice.API_URL to "<!DOCTYPE html><html>404 - this api moved again</html>",
        )

        assertTrue(scraper.scrape(reel).isFailure)
    }
}
