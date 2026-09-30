package com.markhoor.mediadownloader.data.scraper.imdb

import com.markhoor.mediadownloader.domain.models.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an IMDb video page states about its video, as the page writes it. */
class ImdbVideoParserTest {

    /**
     * The page's own state, shortened: the list the player is handed, and the linked data beside
     * it. The urls arrive with their ampersands escaped, which is how the page carries them.
     */
    private val page = """
        <html><head><script type="application/ld+json">
        {"@context":"https://schema.org","@type":"VideoObject","name":"La bola negra - Trailer #1 | IMDb",
        "thumbnailUrl":"https://m.media-amazon.com/images/M/MV5BM2Iw._V1_.jpg",
        "duration":"PT2M3S","uploadDate":"2026-09-28T16:37:37Z"}
        </script></head><body><script>window.__NEXT_DATA__ = {"video":{
        "contentType":{"displayName":{"value":"Trailer","__typename":"LocalizedString"},"__typename":"VideoContentType"},
        "playbackURLs":[
        {"displayName":{"value":"1080p","language":"en-US","__typename":"LocalizedString"},"videoMimeType":"MP4","videoDefinition":"DEF_1080p","url":"https://imdb-video.media-imdb.com/mc/vi20/vi20_1080p.mp4?Expires=1790847180&Signature=aa","__typename":"PlaybackURL"},
        {"displayName":{"value":"AUTO","language":"en-US","__typename":"LocalizedString"},"videoMimeType":"M3U8","videoDefinition":"DEF_AUTO","url":"https://imdb-video.media-imdb.com/mc/vi20/hls/vi20.m3u8?Expires=1790847180","__typename":"PlaybackURL"},
        {"displayName":{"value":"480p","language":"en-US","__typename":"LocalizedString"},"videoMimeType":"MP4","videoDefinition":"DEF_480p","url":"https://imdb-video.media-imdb.com/mc/vi20/vi20_480p.mp4?Expires=1790847180&Signature=cc","__typename":"PlaybackURL"},
        {"displayName":{"value":"720p","language":"en-US","__typename":"LocalizedString"},"videoMimeType":"MP4","videoDefinition":"DEF_720p","url":"https://imdb-video.media-imdb.com/mc/vi20/vi20_720p.mp4?Expires=1790847180&Signature=bb","__typename":"PlaybackURL"}]}};
        </script></body></html>
    """.trimIndent()

    @Test
    fun `every file the page offers is read, largest first`() {
        val qualities = ImdbVideoParser.qualitiesOf(page)

        assertEquals(listOf("1080p", "720p", "480p"), qualities.map { it.label })
        assertTrue(qualities.all { it.type == MediaType.Video })
    }

    /** The page escapes the ampersands in its signed urls; a url read as written signs nothing. */
    @Test
    fun `a signed url is handed over the way it has to be asked for`() {
        val best = ImdbVideoParser.qualitiesOf(page).first()

        assertEquals(
            "https://imdb-video.media-imdb.com/mc/vi20/vi20_1080p.mp4?Expires=1790847180&Signature=aa",
            best.url,
        )
    }

    /** The same video, and the page lists it beside the files rather than instead of them. */
    @Test
    fun `the playlist is passed over while the page offers files`() {
        assertTrue(ImdbVideoParser.qualitiesOf(page).none { it.url.contains(".m3u8") })
    }

    @Test
    fun `with no files, the playlist is handed over on its own to be expanded`() {
        val streamOnly = """
            {"playbackURLs":[{"displayName":{"value":"AUTO","__typename":"LocalizedString"},
            "videoMimeType":"M3U8","videoDefinition":"DEF_AUTO","url":"https://imdb-video.media-imdb.com/mc/vi9/hls/vi9.m3u8?Expires=1"}]}
        """.trimIndent()

        val qualities = ImdbVideoParser.qualitiesOf(streamOnly)

        assertEquals(1, qualities.size)
        assertTrue(qualities.single().url.endsWith(".m3u8?Expires=1"))
    }

    /**
     * The kind of video is written as a `displayName` too. Read without asking what the name looks
     * like, "Trailer" was taken for a quality and paired with the first file listed after it.
     */
    @Test
    fun `what the video is called is not one of its qualities`() {
        assertTrue(ImdbVideoParser.qualitiesOf(page).none { it.label == "Trailer" })
    }

    @Test
    fun `the page's linked data names the video, pictures it and times it`() {
        assertEquals("La bola negra - Trailer #1 | IMDb", ImdbVideoParser.titleOf(page))
        assertEquals("https://m.media-amazon.com/images/M/MV5BM2Iw._V1_.jpg", ImdbVideoParser.thumbnailOf(page))
        assertEquals(123_000L, ImdbVideoParser.durationMillisOf(page))
    }

    @Test
    fun `a page that lists nothing to play is read as nothing`() {
        val html = "<html><body>Video unavailable</body></html>"

        assertTrue(ImdbVideoParser.qualitiesOf(html).isEmpty())
        assertNull(ImdbVideoParser.titleOf(html))
        assertNull(ImdbVideoParser.durationMillisOf(html))
    }
}
