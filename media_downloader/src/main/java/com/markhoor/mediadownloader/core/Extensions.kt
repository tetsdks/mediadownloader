package com.markhoor.mediadownloader.core

import com.markhoor.mediadownloader.core.Constants.MediaSize
import com.markhoor.mediadownloader.core.Constants.Storage
import com.markhoor.mediadownloader.core.Constants.Url
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.IDN
import java.net.URI
import java.util.Locale

// region Url and host

private val SCHEME_AT_START = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""")

/** The host a string opens with, when it opens with one. Anchored, so it is cheap. */
private val LEADING_HOST = Regex(
    """^\s*(https?://)?[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?![^/?#:\s])""",
    RegexOption.IGNORE_CASE,
)

private val EMBEDDED_URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val URL_START = Regex("""https?://""", RegexOption.IGNORE_CASE)

/**
 * The same url is usually asked about several times in a row, on the main thread, so the last
 * answer is kept. One immutable pair behind @Volatile: callers run on several threads, and a torn
 * read must never pair one url with another url's host.
 */
@Volatile
private var lastHostLookup: Pair<String, String?>? = null

/**
 * The normalised host of whatever this string is: a url, a bare host, or text with a url in it.
 * Lowercase, punycode decoded, without `www.` or a trailing dot. `null` when there is none.
 *
 * The host the string itself opens with wins over a url quoted inside it, so
 * `sex.com/?ref=https://x.com` is `sex.com`. Only when the string does not open with a host -
 * `look at this https://x.com/abc` - is the first embedded url read instead.
 */
internal fun String.normalizedHost(): String? {
    lastHostLookup?.let { (url, host) -> if (url == this) return host }
    val host = findNormalizedHost()
    lastHostLookup = this to host
    return host
}

private fun String.findNormalizedHost(): String? {
    // A host is always in the first bytes; a WebView url can be megabytes.
    val trimmed = take(Url.EMBEDDED_SCAN_LIMIT).trim()
    if (trimmed.isEmpty()) return null
    LEADING_HOST.find(trimmed)?.value?.trim()?.hostOrNull()?.let { return it }
    return (EMBEDDED_URL.find(trimmed)?.value ?: trimmed).trim().hostOrNull()
}

private fun String.hostOrNull(): String? {
    if (isEmpty()) return null
    val rawHost = runCatching { URI(withHttpsScheme()).host }.getOrNull() ?: return null
    // IDN.toUnicode is native ICU work that only matters for punycode, so ASCII hosts skip it.
    val isPunycode = rawHost.startsWith(Url.PUNYCODE_PREFIX) || rawHost.contains(".${Url.PUNYCODE_PREFIX}")
    val decoded = if (isPunycode) runCatching { IDN.toUnicode(rawHost) }.getOrNull() ?: return null else rawHost
    return decoded.lowercase(Locale.US).trimEnd('.').removePrefix(Url.WWW_PREFIX)
}

private fun String.withHttpsScheme(): String =
    if (SCHEME_AT_START.containsMatchIn(this)) this else "https://$this"

/**
 * Whether this host is one of [domains] or a subdomain of one. The host is walked one label at a
 * time, so a match only ever starts after a dot: `reddit.com.evil.net` is not `reddit.com`.
 */
internal fun String.isUnderAnyOf(domains: Set<String>): Boolean {
    var candidate = this
    while (true) {
        if (candidate in domains) return true
        val dot = candidate.indexOf('.')
        if (dot < 0) return false
        candidate = candidate.substring(dot + 1)
    }
}

/** Whether this url's site is one of [domains]. Host based, never a substring match. */
internal fun String?.isSiteOf(vararg domains: String): Boolean {
    val host = this?.normalizedHost() ?: return false
    return domains.any { host == it || host.endsWith(".$it") }
}

private fun String.startsWithScheme(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

/**
 * Every url written inside this text, the text itself not included. Bounded by
 * [Url.EMBEDDED_SCAN_LIMIT] and [Url.MAX_EMBEDDED_URLS] because a WebView url can be megabytes.
 */
internal fun String.embeddedUrls(): Sequence<String> {
    val scanned = if (length > Url.EMBEDDED_SCAN_LIMIT) substring(0, Url.EMBEDDED_SCAN_LIMIT) else this
    return URL_START.findAll(scanned)
        .drop(if (scanned.startsWithScheme()) 1 else 0)
        .take(Url.MAX_EMBEDDED_URLS)
        .map { match -> scanned.substring(match.range.first).takeWhile { !it.isWhitespace() } }
}

/**
 * The link in this text: the text itself when it opens with a url or a host, otherwise the first
 * url written inside it. A share arrives as "look at this https://...".
 */
internal fun String.extractLink(): String {
    val trimmed = trim()
    if (trimmed.startsWithScheme() || LEADING_HOST.containsMatchIn(trimmed)) {
        return trimmed.takeWhile { !it.isWhitespace() }
    }
    return EMBEDDED_URL.find(trimmed)?.value ?: trimmed
}

/** The path of this url, or `null` when it does not parse. */
internal fun String.urlPath(): String? = runCatching {
    val candidate = trim()
    if (candidate.isEmpty()) return@runCatching null
    URI(candidate.withHttpsScheme()).path
}.getOrNull()

/**
 * A downloadable url: a scheme and no whitespace. A failed extraction that returned a chunk of the
 * page has neither, which is how it is told apart from a real link.
 */
internal fun String?.isHttpUrl(): Boolean = this != null && startsWithScheme() && none { it.isWhitespace() }

/** This link made absolute against [baseUrl], the playlist or page it was found in. */
internal fun String.resolveAgainst(baseUrl: String): String {
    if (startsWithScheme()) return this
    runCatching { URI(baseUrl).resolve(this).toString() }.getOrNull()?.let { return it }
    val origin = baseUrl.substringBefore("://") + "://" + baseUrl.substringAfter("://").substringBefore('/')
    return if (startsWith("/")) origin + this else baseUrl.substringBefore('?').substringBeforeLast('/') + "/" + this
}

/**
 * This link with [playlistUrl]'s query carried over when it has none of its own. Signed playlists
 * (brightcove's fastly_token) name their segments relative to a url whose query is the signature,
 * and a segment asked for without it is refused.
 */
internal fun String.inheritQueryFrom(playlistUrl: String): String {
    val query = playlistUrl.substringAfter('?', "")
    return if (query.isBlank() || contains('?')) this else "$this?$query"
}

/** An HLS playlist: an `m3u8` in the url, or an hls manifest path. */
internal fun String.isHlsPlaylistUrl(): Boolean = contains("m3u8") || (contains("/hls/") && contains("/manifest"))

/** A url that names a picture file. */
internal fun String.isImageFileUrl(): Boolean {
    val lower = lowercase(Locale.US)
    return listOf(".jpg", ".jpeg", ".png", ".webp").any { lower.contains(it) }
}

// endregion

// region Site links: which parser a link belongs to

private val THREADS_MEDIA_PAGE = Regex("""https?://(www\.)?threads\.com/@[^/]+/post/[^/]+/media.*""")

internal fun String.isFacebookVideoLink(): Boolean {
    if (this == Constants.Facebook.WATCH_ROOT || !isSiteOf("facebook.com")) return false
    return listOf("/watch/", "/reel/", "/share/v/", "/share/r/", "/videos/").any { contains(it) }
}

internal fun String.isFacebookShareLink(): Boolean = isSiteOf("facebook.com") && contains("/share/")

internal fun String.isInstagramPostLink(): Boolean =
    (isSiteOf("instagram.com") && listOf("/p/", "/reel/", "/reels/").any { contains(it) }) ||
        isThreadsVideoFileUrl()

/** A post's own page; the site root and the feed name no post. */
internal fun String.isThreadsPostLink(): Boolean =
    (isSiteOf("threads.net", "threads.com") && contains("/post/")) || isThreadsVideoFileUrl()

/** A Threads video served straight from Instagram's CDN. */
internal fun String.isThreadsVideoFileUrl(): Boolean =
    contains("instagram.") && contains("fbcdn.net") && contains("/o1/v/") && contains(".mp4")

internal fun String.isThreadsMediaPage(): Boolean =
    THREADS_MEDIA_PAGE.matches(take(Constants.Twitter.MATCH_LIMIT))

internal fun String.isLinkedInPostLink(): Boolean = isSiteOf("linkedin.com") && contains("/posts/")

/** The whole host: TikTok's feed never puts the post id in the address bar. */
internal fun String.isTikTokLink(): Boolean = isSiteOf("tiktok.com")

/** Read on the host, so `sex.com/a/status/1` is not a tweet however long the url is. */
internal fun String.isTwitterStatusLink(): Boolean =
    take(Constants.Twitter.MATCH_LIMIT).isSiteOf("x.com", "twitter.com") && contains("/status/")

internal fun String.isDailymotionVideoLink(): Boolean =
    isSiteOf("dailymotion.com") && urlPath()?.startsWith("/video/") == true

internal fun String.isDailymotionMetadataLink(): Boolean =
    isSiteOf("dailymotion.com") && urlPath()?.startsWith("/player/metadata/video/") == true

internal fun String.isPinterestPinLink(): Boolean =
    (isSiteOf("pinterest.com") && urlPath()?.startsWith("/pin/") == true) || isSiteOf("pin.it")

// endregion

// region Text decoding

private val HTML_ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,8});")

private val NAMED_HTML_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’",
    "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»", "middot" to "·",
    "bull" to "•", "copy" to "©", "reg" to "®", "trade" to "™",
)

/** One JSON string escape: `\uXXXX`, or one of `\\ \/ \" \b \f \n \r \t`. */
private val JSON_ESCAPE = Regex("""\\(u[0-9a-fA-F]{4}|[\\/"bfnrt])""")
private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
private val REPEATED_NEWLINES = Regex("\n+")

/**
 * HTML entities decoded - numeric ones and the common named ones - and invisible format
 * characters dropped: direction marks travel all the way into a download's file name.
 */
internal fun String.decodeHtmlEntities(): String =
    HTML_ENTITY.replace(this) { match ->
        val entity = match.groupValues[1]
        val codePoint = when {
            entity.startsWith("#x", ignoreCase = true) -> entity.substring(2).toIntOrNull(16)
            entity.startsWith("#") -> entity.substring(1).toIntOrNull()
            else -> null
        }
        when {
            codePoint != null && Character.isValidCodePoint(codePoint) -> String(Character.toChars(codePoint))
            else -> NAMED_HTML_ENTITIES[entity] ?: match.value
        }
    }.filterNot { it.category == CharCategory.FORMAT }.trim()

/**
 * JSON string escapes decoded, [passes] times. Pages embed JSON inside JSON, so a url often
 * arrives escaped twice - `\\u00253D` is `%3D` - and one pass leaves half of it behind.
 */
internal fun String.decodeJsonEscapes(passes: Int = 1): String {
    var text = this
    repeat(passes) {
        text = JSON_ESCAPE.replace(text) { match ->
            val escape = match.groupValues[1]
            when (escape[0]) {
                'u' -> escape.substring(1).toInt(16).toChar().toString()
                'n' -> "\n"
                't' -> "\t"
                'r' -> "\r"
                'b' -> "\b"
                'f' -> ""
                else -> escape
            }
        }
    }
    return text
}

/** A media url lifted out of a page: JSON escapes decoded and `&amp;` turned back into `&`. */
internal fun String.unescapeEmbeddedUrl(passes: Int = 1): String =
    decodeJsonEscapes(passes).replace("&amp;", "&").trim()

private val ATTRIBUTION_TEXT =
    Regex("""^(source|via|credit|credits|image|photo|video|gif)\b\s*[:\-–]?\s*\S+$""", RegexOption.IGNORE_CASE)
private val BARE_ADDRESS = Regex("""^(https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)

/**
 * Where a caption cut from escaped JSON ran on past its own end: its closing quote, then `,` or `}`,
 * then the next quoted key - `#nasa\"},\"comment_count\":17` on an Instagram reel. A caption does
 * not contain that sequence; a match runs over into it only when the page escaped its JSON twice.
 */
private val JSON_TAIL = Regex("""\\*"\s*[}\]]*\s*,\s*\{?\s*\\*"[A-Za-z_][A-Za-z0-9_]*\\*"\s*:""")

/** A player's own labels - archive.org's player reports "sound is on. click to mute sound." */
private val PLAYER_CONTROL_TEXT = Regex(
    """\b(click|tap|press) to\b|^(play|pause|mute|unmute|volume|full ?screen|skip ad|replay|sound (is )?(on|off))\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * Text a page shows for its media, as a title: entities decoded, the symbols a page decorates it
 * with ("▶️ …", "• …") dropped from the front, and what is not a name at all - an attribution line
 * ("Source www.reddit.com"), a bare address, a player's control label - no title.
 */
/** The label of the field a title was read from: tnaflix's "Description: Teenage ...". */
private val FIELD_LABEL = Regex("""^(description|title|video title|caption)\s*:\s*""", RegexOption.IGNORE_CASE)

internal fun String.asMediaTitle(): String {
    val text = (JSON_TAIL.find(this)?.let { take(it.range.first) } ?: this).decodeHtmlEntities().trim().dropWhile { !it.isLetterOrDigit() && it !in "\"'(#@¿¡[" }.trim()
        .replaceFirst(FIELD_LABEL, "")
        .withoutSiteSuffix()
    val notAName = ATTRIBUTION_TEXT.matches(text) || BARE_ADDRESS.matches(text) || PLAYER_CONTROL_TEXT.containsMatchIn(text)
    return if (notAName) "" else text
}

/**
 * A title as it should be shown and used as a file name: leftover `\uXXXX` escapes decoded rather
 * than deleted (a non latin caption would come out empty), and runs of blank lines collapsed.
 */
/**
 * [Constants.Titles.SITE_SUFFIXES] taken off the end, as often as they are stacked there, with
 * LinkedIn's "| <author> posted on the topic" and a segment a page repeats ("… | Microsoft Azure |
 * Microsoft Azure") along the way.
 */
private fun String.withoutSiteSuffix(): String {
    var title = split(" | ").fold(mutableListOf<String>()) { parts, part ->
        parts.apply { if (lastOrNull()?.equals(part.trim(), ignoreCase = true) != true) add(part.trim()) }
    }.joinToString(" | ")
    while (true) {
        val shorter = title.withoutAuthorLabel() ?: title.withoutOneSiteSuffix() ?: return title
        // A title that is nothing but the site's name stays what it was.
        if (shorter.isEmpty()) return title
        title = shorter
    }
}

private fun String.withoutAuthorLabel(): String? =
    takeIf { substringAfterLast(" | ", "").endsWith(LINKEDIN_AUTHOR_LABEL, ignoreCase = true) }
        ?.substringBeforeLast(" | ")?.trim()

private fun String.withoutOneSiteSuffix(): String? {
    for (name in Constants.Titles.SITE_SUFFIXES) {
        val separator = SITE_SUFFIX_SEPARATORS.firstOrNull { endsWith(it + name, ignoreCase = true) } ?: continue
        return dropLast(separator.length + name.length).trim()
    }
    return null
}

private const val LINKEDIN_AUTHOR_LABEL = "posted on the topic"

private val SITE_SUFFIX_SEPARATORS = listOf(" | ", " - ", " – ", " · ", " : ", " on ")

internal fun String.cleanTitle(): String =
    UNICODE_ESCAPE.replace(this) { match ->
        match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
    }.replace(REPEATED_NEWLINES, "\n").trim()

// endregion

// region Html

private val OG_TITLE_TAG = Regex("""<meta property="og:title" content="([^"]*)"""")
private val OG_IMAGE_ALT_TAG = Regex("""<meta property="og:image:alt" content="([^"]*)"""")
private val TITLE_TAG = Regex("""<title[^>]*>([^<]*)</title>""")

/**
 * The name a page gives its post: og:title first (a page's `<title>` is often a generic,
 * locale dependent label such as "Facebook"), then og:image:alt, then `<title>`. Empty when none.
 */
internal fun String.titleFromHtml(): String {
    OG_TITLE_TAG.find(this)?.groupValues?.get(1)?.withoutReelStats()?.takeIf { it.isNotBlank() }?.let { return it }
    OG_IMAGE_ALT_TAG.find(this)?.groupValues?.get(1)?.withoutReelStats()?.takeIf { it.isNotBlank() }?.let { return it }
    return TITLE_TAG.find(this)?.groupValues?.get(1)?.decodeHtmlEntities().orEmpty()
}

/** A reel's og:title reads `<stats> | <author> on Reels`; the stats are not part of the post. */
private fun String.withoutReelStats(): String {
    val decoded = decodeHtmlEntities()
    val beforeBar = decoded.substringBefore(" | ", "")
    val statsFirst = beforeBar.firstOrNull { it.isLetterOrDigit() }?.isDigit() == true
    return if (statsFirst) decoded.substringAfter(" | ", decoded).trim() else decoded
}

/** The `content` of `<meta property="[property]" ...>`, entity decoded. */
internal fun String.metaProperty(property: String): String? =
    Regex("""<meta\s+property="${Regex.escape(property)}"\s+content="([^"]*)"""")
        .find(this)?.groupValues?.get(1)?.decodeHtmlEntities()?.takeIf { it.isNotBlank() }

// endregion

// region Json

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

internal fun JsonElement?.array(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

/** A primitive's text, whether the JSON wrote it as a string or a number; `null` for JSON null. */
internal fun JsonElement?.text(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

// endregion

// region Media

/**
 * This size as it should be shown, or `null` where it cannot be true: nothing, a player's
 * placeholder passed off as a video, or an estimate in the tens of gigabytes.
 */
internal fun Long?.sensibleSize(isVideo: Boolean): Long? {
    val size = this ?: return null
    if (size <= 0 || size > MediaSize.LARGEST_BELIEVABLE) return null
    if (isVideo && size < MediaSize.SMALLEST_REAL_VIDEO) return null
    return size
}

/** `"720x1280"` → `"720p"`: a quality is named for its short side, whichever way up it is. */
internal fun String.qualityNameFromResolution(): String {
    val parts = split("x")
    if (parts.size != 2) return this
    val width = parts[0].trim().toIntOrNull() ?: return this
    val height = parts[1].trim().toIntOrNull() ?: return this
    return "${minOf(width, height)}p"
}

// endregion

// region Files and downloads

/** Refused by file systems, plus `#` and `%`, which break a file path turned into a uri. */
private val UNSAFE_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|#%\p{Cntrl}]+""")
private val BLANK_RUN = Regex("""\s+""")

/**
 * A title made fit to name a file: no characters a file system refuses, no blank runs, no
 * leading dots or dashes, at most [Storage.MAX_FILE_NAME_LENGTH] characters. Empty when nothing
 * usable is left; the caller picks the fallback. Bounded first - a scraped title can be a page.
 */
internal fun String.toFileNameBase(): String =
    take(Storage.MAX_RAW_TITLE_LENGTH)
        .replace(UNSAFE_FILE_NAME_CHARS, " ")
        .replace(BLANK_RUN, " ")
        .trim()
        .trimStart('.', '-', '_', ' ')
        // A title that is itself a file name ("sample 720p.mp4") would be saved as "….mp4.mp4".
        .withoutMediaExtension()
        .take(Storage.MAX_FILE_NAME_LENGTH)
        .trim()

private fun String.withoutMediaExtension(): String {
    val extension = substringAfterLast('.', "").lowercase(Locale.US)
    return if (extension in Storage.KNOWN_MEDIA_EXTENSIONS) substringBeforeLast('.').trimEnd() else this
}

/**
 * The extension to name a download of this url with: the url's own when it is a known media
 * extension, else [type]'s usual one. Tumblr's `.pnj` is a jpeg under another name.
 */
internal fun String.mediaExtension(type: MediaType): String {
    val candidate = substringBefore('?').substringBefore('#')
        .substringAfterLast('/', "")
        .substringAfterLast('.', "")
        .lowercase(Locale.US)
    return when {
        candidate == "pnj" -> "jpg"
        candidate in Storage.KNOWN_MEDIA_EXTENSIONS -> candidate
        else -> when (type) {
            MediaType.Video -> "mp4"
            MediaType.Image -> "jpg"
            MediaType.Audio -> "mp3"
        }
    }
}

/**
 * What a file's first bytes say it is, or `null` when they say nothing this module names files
 * by. A url's extension can lie - a `.png` link that serves a jpeg, a `.jpg` that is a webp.
 */
internal fun ByteArray.sniffedExtension(): String? {
    fun startsWith(vararg bytes: Int, at: Int = 0) =
        size >= at + bytes.size && bytes.indices.all { this[at + it] == bytes[it].toByte() }
    return when {
        startsWith(0x66, 0x74, 0x79, 0x70, at = 4) -> "mp4"                    // ....ftyp
        startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
        startsWith(0x89, 0x50, 0x4E, 0x47) -> "png"
        startsWith(0x47, 0x49, 0x46, 0x38) -> "gif"                             // GIF8
        startsWith(0x52, 0x49, 0x46, 0x46) && startsWith(0x57, 0x45, 0x42, 0x50, at = 8) -> "webp"
        startsWith(0x1A, 0x45, 0xDF, 0xA3) -> "webm"
        else -> null
    }
}

/** Markup: past a byte-order mark and white space, the first byte is `<`. No media file starts so. */
internal fun ByteArray.looksLikeMarkup(): Boolean {
    var at = if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) 3 else 0
    while (at < size && this[at].toInt().toChar().isWhitespace()) at++
    return at < size && this[at] == '<'.code.toByte()
}

/** The folder a download from this page is filed under, by its host. */
internal fun String.siteFolderName(): String {
    val host = normalizedHost() ?: return Storage.OTHER_SITES_FOLDER
    return Storage.SITE_FOLDERS.firstOrNull { (_, domains) -> host.isUnderAnyOf(domains) }?.first
        ?: Storage.OTHER_SITES_FOLDER
}

/**
 * A file in this folder named [baseName].[extension] that does not exist yet:
 * `name.mp4`, then `name (1).mp4`, `name (2).mp4`…
 */
internal fun File.freeFile(baseName: String, extension: String): File {
    var candidate = File(this, "$baseName.$extension")
    var copy = 1
    while (candidate.exists()) {
        candidate = File(this, "$baseName ($copy).$extension")
        copy++
    }
    return candidate
}

/** Writes these files one after another into [target], replacing it. Missing files are an error. */
internal fun List<File>.joinInto(target: File) {
    target.parentFile?.mkdirs()
    target.outputStream().buffered(Constants.Download.BUFFER_BYTES).use { output ->
        forEach { part -> part.inputStream().use { it.copyTo(output, Constants.Download.BUFFER_BYTES) } }
    }
}

/** A small file that starts with the HLS header: a playlist, whatever its name says. */
internal fun File.isSmallPlaylistFile(): Boolean {
    if (!isFile || length() > Constants.Download.MAX_PLAYLIST_FILE_BYTES) return false
    val header = Constants.Hls.HEADER.toByteArray()
    val head = ByteArray(header.size)
    val read = inputStream().use { it.read(head) }
    return read == header.size && head.contentEquals(header)
}

/** Moves this file onto [target], replacing it; copies when the two are on different volumes. */
internal fun File.moveTo(target: File) {
    if (target.exists()) target.delete()
    if (renameTo(target)) return
    copyTo(target, overwrite = true)
    delete()
}

/** `bytes=a-b`, or `bytes=a-` for a range that runs to the end of the file. */
internal fun LongRange.toRangeHeader(): String =
    if (last == Long.MAX_VALUE) "bytes=$first-" else "bytes=$first-$last"

/** A subtitle track listed beside the picture; never part of the video. */
internal fun String.isSubtitleUrl(): Boolean {
    val path = substringBefore('?').lowercase(Locale.US)
    return path.endsWith(".vtt") || path.endsWith(".webvtt") || path.endsWith(".srt")
}

/**
 * Whether an entry in a playlist is another playlist rather than a piece of media. With no
 * extension at all under an hls path it is one (brightcove's signed renditions); any other
 * extension is a piece - pinterest's CMAF `.cmfv` included.
 */
internal fun String.isPlaylistEntryUrl(): Boolean {
    val path = substringBefore('?')
    if (path.endsWith(".m3u8", ignoreCase = true) || path.endsWith(".m3u", ignoreCase = true)) return true
    val hasExtension = path.substringAfterLast('/').substringAfterLast('.', "").isNotEmpty()
    return !hasExtension && path.contains("/hls/", ignoreCase = true)
}

// endregion

// region Browser: pages and the requests they make

private val RUMBLE_VIDEO_PATH = Regex("""^/v[0-9a-z]{3,}(-|\.html)""")
private val TIKTOK_VIDEO_PATH = Regex("""/video/\d""")
private val MEDIA_FILE_ENDING = Regex("""\.(mp4|m4v|mov|webm|mkv|m3u8|mpd|ts|mp3|m4a|aac|ogg|jpg|jpeg|png|gif|webp)$""")
private val IMAGE_FILE_ENDING = Regex("""\.(jpg|jpeg|webp|png)$""")

/** This url cut to the part the sniffing rules look at: WebView urls can be megabytes. */
private fun String.sniffable(): String = take(Constants.Browser.MAX_SNIFFED_URL_LENGTH)

/** A TikTok page of cards (discover, search, a profile grid) rather than a player. */
internal fun String.isTikTokGridPage(): Boolean {
    if (!isSiteOf("tiktok.com")) return false
    val path = urlPath()?.lowercase(Locale.US) ?: return false
    if (TIKTOK_VIDEO_PATH.containsMatchIn(path)) return false
    if (path.startsWith("/v/") || path.startsWith("/t/") || path.startsWith("/embed")) return false
    return path.trimEnd('/') !in setOf("", "/foryou", "/following", "/live")
}

/**
 * One piece of media's own page, on a site served by sniffing: there the stream the player asks
 * for is that media, where on a feed it is whichever card loaded first.
 */
internal fun String.isSniffedSingleMediaPage(): Boolean {
    val path = urlPath()?.lowercase(Locale.US) ?: return false
    return when {
        isSiteOf("rumble.com") -> RUMBLE_VIDEO_PATH.containsMatchIn(path) || path.startsWith("/embed/")
        isSiteOf("twitch.tv") -> path.startsWith("/videos/") || path.contains("/clip/")
        isSiteOf("bitchute.com") -> path.startsWith("/video/")
        isSiteOf("tumblr.com") -> path.contains("/post/")
        isSiteOf("snackvideo.com") -> path.contains("/video/") || path.contains("/@")
        isSiteOf("mojapp.in", "moj.sharechat.com") -> path.contains("/video/")
        isSiteOf("ted.com") -> path.startsWith("/talks/")
        isSiteOf("9gag.com") -> path.startsWith("/gag/")
        isSiteOf("vimeo.com") -> path.removePrefix("/").takeWhile { it.isDigit() }.isNotEmpty()
        else -> false
    }
}

/**
 * A video's own page on imdb, not any imdb page. Imdb's listings - "What's on TV", a film's page -
 * play trailers of their own, and reading a listing as one media's page named every one of them
 * after the listing ("2026 Fall TV Watch Guide"), gave them its artwork, and handed the listing
 * over in place of the video.
 */
private val IMDB_STREAM_ID = Regex("""imdb-video\.media-imdb\.com/[^ ]*?/(vi\d+)/""")

/**
 * The page of the video an imdb stream belongs to, or `null`. Imdb's listings play a few seconds
 * of each video inline (`/mc/vi951634457/previews/...`), all of them the same length and size; the
 * video itself is on the page this names.
 */
internal fun String.imdbVideoPageFromStream(): String? =
    IMDB_STREAM_ID.find(sniffable())?.groupValues?.get(1)?.let { "https://www.imdb.com/video/$it/" }

internal fun String.isImdbVideoPage(): Boolean =
    isSiteOf("imdb.com") && urlPath()?.startsWith("/video/") == true

/** Nothing a page script or an image, font or style sheet could ever be the media. */
internal fun String.isStaticAssetUrl(): Boolean {
    val path = sniffable().substringBefore('?').substringBefore('#')
    return path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.US) in
        Constants.Browser.STATIC_ASSET_EXTENSIONS
}

private val ROTATING_AD_HOST = Regex("""[a-z0-9]{8}\.(bkcdn|bxcdn)\.net""")
private val SHA1_FILE = Regex("""(^|/)[0-9a-f]{40}\.mp4$""")

/** An advert, a tracker, or a creative: never the media the user asked for. */
internal fun String.isAdvertMediaUrl(): Boolean {
    val url = sniffable().lowercase(Locale.US)
    val host = url.substringAfter("//", "").substringBefore('/').substringBefore('?').substringBefore(':')
    if (host.isNotBlank() && host.isUnderAnyOf(Constants.Browser.ADVERT_HOSTS)) return true
    val path = url.substringAfter("//", url).substringAfter('/', "").substringBefore('?').substringBefore('#')
    if (path.split('/').any { it in Constants.Browser.ADVERT_PATH_SEGMENTS }) return true
    // The pre-rolls and banner loops on xvideos, youjizz, thisvid and drtuber:
    // `z6v2p9a8.bkcdn.net/library/<id>/<sha1>.mp4`, `n2j9y0x0.bxcdn.net/<sha1>.mp4`. Only that
    // shape - a random label and a hash for a name - since the CDN's name alone says nothing.
    if (ROTATING_AD_HOST.matches(host) && SHA1_FILE.containsMatchIn(path)) return true
    // `dclk_video_ads`: a Google video advert, served through YouTube's own redirector (ted).
    return listOf("/ad-creative", "/vast/", "metrics.brightcove.com", "media-backend.bitchute.com", "dclk_video_ads",
        "/tracker?", "/beacon", "/collect?").any { url.contains(it) }
}

/** A piece of a stream; its playlist is the media. */
internal fun String.isHlsSegmentUrl(): Boolean {
    val path = sniffable().substringBefore('?')
    return path.endsWith(".ts", ignoreCase = true) || path.endsWith(".m4s", ignoreCase = true)
}

internal fun String.isBrightcovePlaybackApi(): Boolean = sniffable().contains("edge.api.brightcove.com/playback/")

/**
 * A stream the sniffer must leave alone: Dailymotion's and Pinterest's variants, which the parser
 * reads better from the post, and restricted CDNs whatever page asked for them.
 */
internal fun String.isIgnoredStream(): Boolean {
    val url = sniffable()
    val restricted = (url.contains(".xhcdn.com") && !url.contains("_TPL_.av1.mp4.m3u")) ||
        url.contains("media-hls.doppiocdn.net") || url.contains("gcore-vid.xnxx-cdn.com") ||
        (url.contains("-vid.xnxx-") && !url.contains("hls.m3u8"))
    // Imdb autoplays a few seconds of a film on its page as `hls-preview-<id>.m3u8`; the trailer
    // itself is on the video's own page, and is served as a file.
    // Imdb plays a few seconds of a video in its listings (`/mc/vi951634457/previews/...`), all of
    // them the same length and size; the video itself is on its own page.
    return restricted || url.contains("hls-preview") ||
        (url.contains("imdb-video.media-imdb.com") && url.contains("/previews/")) ||
        url.contains("pubads.g.doubleclick.net") || url.contains("audio.m3u8") ||
        url.contains("https://vod3.cf.dmcdn.net") || (url.contains("https://v1.pinimg.com") && url.contains("w.m3u8"))
}

/** Requests the sniffer never takes, whatever page made them. */
internal fun String.isRefusedRequest(): Boolean {
    val url = sniffable()
    // Fluid Player loads a blank clip of its own before every video; it is never the media.
    return url.contains(".tsyndicate.com/media=") || url.contains("doppiocdn.net/hls/") ||
        url.contains("cdn.fluidplayer.com/static/")
}

/** A url whose path names a video file ([Constants.Browser.VIDEO_FILE_EXTENSIONS]), not a piece of a stream. */
internal fun String.isVideoFileUrl(): Boolean {
    // KVS, the script most tube sites run on, serves its files as `/get_file/.../123.mp4/`.
    val path = sniffable().substringBefore('?').substringBefore('#').lowercase(Locale.US).trimEnd('/')
    return path.hasVideoFileExtension() && !path.isStreamPiecePath()
}

private fun String.hasVideoFileExtension(): Boolean =
    Constants.Browser.VIDEO_FILE_EXTENSIONS.any { endsWith(".$it") }

private val STREAM_INIT_NAME = Regex("""(^|[-_])init([-_.]|\d)""")

/**
 * An fMP4 stream's pieces end in `.mp4` too. xHamster's player asks for
 * `480p.av1.mp4/init-v1-a1.mp4` - the few-kB header of one variant - and it was offered as the
 * video. A stream's init segment is named so, and a packager keeps a variant's pieces in a folder
 * named like the file they make up; neither is ever a video on its own.
 */
private fun String.isStreamPiecePath(): Boolean {
    val segments = split('/')
    return STREAM_INIT_NAME.containsMatchIn(segments.last()) ||
        segments.dropLast(1).any { it.hasVideoFileExtension() }
}

/** A media file on a CDN the sniffer knows, downloadable as it is. */
internal fun String.isDirectMediaUrl(): Boolean {
    val url = sniffable()
    return (url.contains("scontent") && url.contains(".mp4?")) || url.contains("instagram.flhe2-4.fna.fbcdn.net/") ||
        (url.startsWith("https://img-9gag-fun.9cache.com/") && url.endsWith(".webm")) ||
        url.startsWith("https://dms.licdn.com/") ||
        (url.contains(".vkcdn5.com/") && url.contains("mp4")) ||
        isTikTokVideoFileUrl() ||
        url.startsWith("https://www.dailymotion.com/cdn/manifest/video/") ||
        (url.startsWith("https://cloudyvideo.com/m3/") && !url.endsWith(".png") && url.length > 35) ||
        (url.startsWith("https://myfilestorage.xyz/") && url.endsWith(".mp4")) ||
        ((url.contains("videos-cloudfront.jwpsrv.com/") || url.contains("content.jwplatform.com/videos/")) && url.endsWith(".mp4")) ||
        (url.contains("aws-") && url.contains(".mp4")) ||
        (url.contains("cdn-") && url.contains(".mp4?") && url.contains("=moj")) ||
        (url.contains("rumble.cloud") && url.contains(".mp4") && !url.contains(".tar?")) ||
        (url.contains("https://www.udemy.com/api-2.0/") && url.contains("lectures/") && url.contains("download_urls")) ||
        (url.contains("imdb-video.media-imdb.com/") && url.contains(".mp4")) ||
        (url.contains("hls-reels/reels/") && url.contains("playlist.m3u8") && url.contains("tamashaweb")) ||
        (url.contains("va.media.tumblr.com/") && url.contains(".mp4")) ||
        (url.contains(".ttvnw.net/vod/") && url.contains(".m3u8?")) || url.contains("production.assets.clips.twitchcdn.net") ||
        (url.contains("videocdn.alibaba.") && url.contains("video") && url.contains(".mp4")) ||
        isThreadsVideoFileUrl() ||
        (url.contains(".bitchute.com/") && url.contains(".mp4") && !url.contains("media-backend.bitchute.com")) ||
        (url.contains("player.odycdn.com/") && (url.contains(".mp4") || url.contains(".m3u8")))
}

/** A TikTok video file on its CDN. */
internal fun String.isTikTokVideoFileUrl(): Boolean {
    val url = sniffable()
    return url.startsWith("https://v16-webapp-prime.tiktok.com/video") ||
        url.startsWith("https://v16-webapp-prime.us.tiktok.com/video/") ||
        url.startsWith("https://v19-webapp-prime.tiktok.com/video") ||
        (url.contains(".tiktokcdn.") && !url.contains("jpeg") && url.contains("/video/"))
}

/**
 * Artwork worth keeping for a download: an image link, or a small image a page script captured
 * inline. Never a stream or a video file - those have been handed over as "thumbnails" and can
 * only ever show as a blank square - and never longer than a stored url may be.
 */
internal fun String?.isUsableThumbnail(): Boolean {
    val url = this ?: return false
    if (url.length > Constants.Download.MAX_STORED_URL_LENGTH) return false
    if (url.startsWith("data:image/")) return true
    if (!url.isHttpUrl()) return false
    val path = url.substringBefore('?').lowercase(Locale.US)
    return !url.isHlsPlaylistUrl() && Constants.Storage.KNOWN_MEDIA_EXTENSIONS
        .filterNot { it in Constants.Storage.IMAGE_EXTENSIONS }.none { path.endsWith(".$it") }
}

/** Artwork a script can hand over: a link, or a frame it captured as a data: image. */
internal fun String.isArtworkUrl(): Boolean = startsWith("http") || startsWith("data:image")

/** An image file by its ending; read from the tail, since that is what decides it. */
internal fun String.looksLikeImageUrl(): Boolean =
    IMAGE_FILE_ENDING.containsMatchIn(takeLast(Constants.Browser.MAX_SNIFFED_URL_LENGTH).substringBefore('?').lowercase(Locale.US))

/** Names a media file, or at least not a page that only plays one (an embed or a player). */
internal fun String.namesMediaFile(): Boolean {
    val path = substringBefore('?').substringBefore('#').lowercase(Locale.US)
    if (MEDIA_FILE_ENDING.containsMatchIn(path)) return true
    if (path.contains("/hls/") || path.contains("manifest")) return true
    return !path.contains("/embed") && !path.contains("/player")
}

/** The same page, ignoring query, fragment, case and a trailing slash. */
internal fun String.isSamePageAs(other: String): Boolean {
    fun bare(url: String) = url.substringBefore('?').substringBefore('#').removeSuffix("/").lowercase(Locale.US)
    return bare(this) == bare(other)
}

/** The same page for the purpose of a tap: everything before the query. */
internal fun String.isSamePathAs(other: String): Boolean = substringBefore('?') == other.substringBefore('?')

/**
 * A permalink's slug as words: `/v7exqzu-sorry-i-annoyed-you.html` → `sorry i annoyed you`. An id
 * at either end is dropped (pexels closes its slugs with the media's number); empty when the slug
 * is not three words or more.
 */
internal fun String?.titleFromSlug(): String {
    val segment = this?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')?.substringAfterLast('/')
        ?.removeSuffix(".html") ?: return ""
    val parts = segment.split('-').filter { it.isNotBlank() }
    if (parts.size < 3) return ""
    val first = parts.first()
    var words = if (first.length <= 12 && first.any { it.isDigit() }) parts.drop(1) else parts
    if (words.size > 2 && words.last().isSlugId()) words = words.dropLast(1)
    return words.joinToString(" ").take(120)
}

/**
 * A media id closing a slug: a long number (pexels), or a token with capitals past its first letter
 * (xHamster's `...-part-1-xhvgYCw`). A slug's words are lower case, so the second is never a word.
 */
private fun String.isSlugId(): Boolean =
    length >= 6 && (all { it.isDigit() } || (drop(1).any { it.isUpperCase() } && any { it.isLowerCase() }))

/**
 * Whether [written] is this slug spelled properly - letters and digits compared, as a prefix,
 * because slugs drop punctuation and get cut short.
 */
internal fun String.isSpelledOutBy(written: String): Boolean {
    fun letters(text: String) = text.lowercase(Locale.US).filter { it.isLetterOrDigit() }
    val slug = letters(this)
    return slug.length >= 12 && letters(written).startsWith(slug)
}

// endregion
