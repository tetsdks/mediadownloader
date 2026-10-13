package com.markhoor.mediadownloader.data.scraper.fallback

import com.markhoor.mediadownloader.core.Constants.GetInDevice
import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.array
import com.markhoor.mediadownloader.core.text
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.MediaGoneException
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * A third-party resolver raced alongside several sites' own scrapers as a fallback. It reads a
 * post as someone who is signed in to it, which is its whole worth: Instagram shows a signed-out
 * request no media at all, and this answers for it anyway.
 *
 * **Two requests, in order:** the api refuses a link with `401 {"error":"Missing request token"}`
 * unless the request carries a token from [GetInDevice.TOKEN_URL], so the token is asked for first
 * and a failed token means no answer rather than a wasted second request. A token is good for more
 * than one link but carries the moment it was issued, so it is asked for each time rather than
 * kept - two cheap requests on a path that only runs when a site's own reader came back empty.
 *
 * The api is read defensively on purpose: it is somebody else's private api with no documentation
 * and no promises, and it has already been rebuilt under this module once (the `/wp-json/aio-dl/`
 * route it used to answer on is gone, and a call to it looked exactly like a link with no media
 * behind it). Every field is read as text whether it arrives as a string, a number or `null`, so a
 * shape that changes again costs this reader its answer and nothing else.
 */
internal class GetInDeviceScraper(private val fetcher: HttpFetcher) : SiteScraper() {

    private val headers = mapOf(
        "Origin" to GetInDevice.ORIGIN,
        Network.HEADER_REFERER to "${GetInDevice.ORIGIN}/",
        "Accept" to Network.ACCEPT_JSON,
    )

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val token = tokenOrNull() ?: return null
        val answer = fetcher.postJson(
            url = GetInDevice.API_URL,
            body = buildJsonObject { put("url", url) }.toString(),
            headers = headers + (GetInDevice.TOKEN_HEADER to token),
        ).getOrThrow()
        val media = fetcher.json.parseToJsonElement(answer)
        media.text("error")?.let { refusal ->
            // Said as a failure rather than returned as nothing: it is the one refusal that tells
            // a caller a session would not have helped. See [GetInDevice.GONE_WORDS].
            val said = refusal.lowercase(Locale.US)
            if (GetInDevice.GONE_WORDS.any { said.contains(it) }) throw MediaGoneException(refusal)
            return null
        }
        if (readTheSiteItself(media.text("title"), media.text("source"))) return null

        val pictures = pictures(media)
        val videos = videos(media)
        return ScrapedMediaDto(
            // Sound alone only where there is nothing to watch or look at: on a reel the api lists
            // the same progressive file again as its audio, which is not a second quality.
            qualities = (videos + pictures).ifEmpty { sounds(media) },
            title = media.text("title"),
            thumbnailUrl = media.text("thumbnail"),
            durationMillis = media.text("duration")?.toDoubleOrNull()?.times(1_000)?.toLong(),
        )
    }

    private suspend fun tokenOrNull(): String? =
        fetcher.getText(GetInDevice.TOKEN_URL, headers).getOrNull()
            ?.let { fetcher.json.parseToJsonElement(it).text("token") }
            ?.takeIf { it.isNotBlank() }

    /**
     * Whether the answer is the site's own page rather than a post. A link Facebook does not have
     * is answered with the Watch feed, and one Threads does not have with its sign-up page, so the
     * service reads whatever media that page carries and offers it as the link's - a real,
     * playable video belonging to somebody else entirely. The only thing that gives it away is
     * that the page's title is the site's own name or its sign-up line, so that is what is
     * refused. See [GetInDevice.SHELL_NAMES].
     *
     * Best effort, deliberately: it is one string, and a site that writes it in another language
     * gets past. A post of one's own titled nothing but `Facebook` is refused with it, which is
     * the side to err on - the site's own reader is racing this one and answers such a post too.
     */
    private fun readTheSiteItself(title: String?, source: String?): Boolean {
        val named = title?.trim()?.lowercase(Locale.US)?.ifEmpty { null } ?: return false
        if (named in GetInDevice.SHELL_NAMES) return true
        if (GetInDevice.SHELL_OPENINGS.any { named.startsWith(it) }) return true
        // `LinkedIn Login, Sign in`: the site's name and then what the page is for.
        val site = source?.trim()?.lowercase(Locale.US)?.ifEmpty { null } ?: return false
        return named.startsWith(site) && GetInDevice.SHELL_WORDS.any { named.contains(it) }
    }

    private fun videos(media: JsonElement): List<ScrapedQualityDto> =
        media.array("videos").orEmpty().mapNotNull { entry ->
            val stream = entry.text("hlsStreamUrl")?.takeIf { entry.text("is_hls").toBoolean() }
            val url = stream ?: entry.text("url") ?: return@mapNotNull null
            ScrapedQualityDto(
                url = url,
                type = MediaType.Video,
                label = labelOf(entry.text("quality")),
                sizeBytes = entry.text("size")?.toLongOrNull(),
                // The api says when a video's sound is a file of its own; ours joins the two.
                audioUrl = if (entry.names("mergeParts")) soundApartFrom(media, url) else null,
            )
        }

    /**
     * The pictures the post itself holds. An account's own picture is dropped first: the service
     * answers a reel it cannot resolve with that instead of an error, and nothing else in the
     * answer says so. See [GetInDevice.PROFILE_PICTURE_PATH]. With it gone there is no quality
     * left, which `SiteScraper.scrape` reads as the nothing it is.
     */
    private fun pictures(media: JsonElement): List<ScrapedQualityDto> {
        val photos = media.array("photos").orEmpty().filterNot { entry ->
            entry.text("url")?.let { GetInDevice.PROFILE_PICTURE_PATH.containsMatchIn(it) } == true
        }
        return photos.mapIndexedNotNull { index, entry ->
            val url = entry.text("url") ?: return@mapIndexedNotNull null
            ScrapedQualityDto(
                url = url,
                type = MediaType.Image,
                // One picture is the media; several are an album, where which one it is matters.
                label = if (photos.size > 1) "${QualityLabels.MEDIA}_${index + 1}" else QualityLabels.IMAGE,
                sizeBytes = entry.text("size")?.toLongOrNull(),
            )
        }
    }

    private fun sounds(media: JsonElement): List<ScrapedQualityDto> =
        media.array("audios").orEmpty().mapNotNull { entry ->
            val url = entry.text("url") ?: return@mapNotNull null
            ScrapedQualityDto(url, MediaType.Audio, label = QualityLabels.MEDIA, sizeBytes = entry.text("size")?.toLongOrNull())
        }

    /** The first sound that is not the file it would be joined to; the api repeats a url freely. */
    private fun soundApartFrom(media: JsonElement, videoUrl: String): String? =
        media.array("audios").orEmpty().firstNotNullOfOrNull { entry ->
            entry.text("url")?.takeIf { it != videoUrl }
        }

    /** Whether the api said anything at all under [key] - a list, a word, anything but null. */
    private fun JsonElement.names(key: String): Boolean =
        (this as? JsonObject)?.get(key)?.let { it !is JsonNull } == true

    /**
     * What to call a quality. The api labels a video with its frame size (`1080x1920`), which is
     * the long side on a reel and the short side on anything landscape; a person reads the short
     * one as the quality, so `1080x1920` and `1920x1080` are both `1080p`. Anything else it says
     * (`HD`, `audio`) is left as it is.
     */
    private fun labelOf(quality: String?): String {
        val sides = quality?.split('x')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 2 }
        return sides?.min()?.let { "${it}p" } ?: quality?.takeIf { it.isNotBlank() } ?: QualityLabels.HD
    }
}
