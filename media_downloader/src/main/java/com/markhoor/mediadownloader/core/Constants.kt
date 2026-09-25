package com.markhoor.mediadownloader.core

/**
 * Every constant the module uses, grouped by what it is for.
 *
 * Host lists are registrable domains: lowercase, no scheme, no `www.`, no path. A host matches
 * an entry when it equals it or ends in `"." + entry`, so subdomains are covered and a lookalike
 * such as `sex.com` never matches `x.com`.
 */
internal object Constants {

    object Url {
        const val PUNYCODE_PREFIX = "xn--"
        const val WWW_PREFIX = "www."

        /** How far into a string embedded urls are looked for; WebView urls can be megabytes. */
        const val EMBEDDED_SCAN_LIMIT = 8_192

        /** How many embedded urls are checked before giving up. */
        const val MAX_EMBEDDED_URLS = 8
    }

    object Network {
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val SIZE_PROBE_TIMEOUT_MS = 10_000L

        /** Far above any api answer or html page that is parsed, far below the heap. */
        const val MAX_RESPONSE_BYTES = 25L * 1024 * 1024

        const val ACCEPT_HTML =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp," +
                "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9"
        const val ACCEPT_JSON = "application/json"

        const val HEADER_COOKIE = "Cookie"
        const val HEADER_REFERER = "Referer"
        const val HEADER_USER_AGENT = "User-Agent"
        const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"
    }

    object MediaSize {

        /** Smaller than this is a player's placeholder, not a video: ok.ru parks 652 bytes. */
        const val SMALLEST_REAL_VIDEO = 64L * 1024

        /** Larger than this is a misreported bitrate, not a size. */
        const val LARGEST_BELIEVABLE = 32L * 1024 * 1024 * 1024

        /** A sampled segment lighter than this per second is a refusal, not video. */
        const val MIN_SAMPLED_BYTES_PER_SECOND = 5_000.0
    }

    object Download {

        /** Each read is a syscall; a socket with more waiting hands over more per call. */
        const val BUFFER_BYTES = 64 * 1024

        /** How often progress reaches the database and the notification. A reader sees no more. */
        const val PROGRESS_INTERVAL_MS = 1_000L

        /**
         * Written in front of the reason a download failed when the media was never there to take,
         * and taken off again in `DownloadEntity.toModel`, where it becomes
         * `DownloadModel.isMediaGone`. A marker rather than a column of its own: a new column
         * changes the schema, and this database is set to wipe itself rather than migrate.
         */
        const val MEDIA_GONE_MARKER = "media-gone|"

        /** Parts a file is fetched in at once, and segments of a stream. */
        const val MAX_PARALLEL_PARTS = 8
        const val MAX_PARALLEL_SEGMENTS = 8

        /** A file smaller than two of these is fetched in one piece; parts cost a request each. */
        const val MIN_PART_BYTES = 2L * 1024 * 1024

        /** Failures other than a lost connection before a download is given up. */
        const val MAX_ATTEMPTS = 5
        const val BACKOFF_SECONDS = 10L

        /** A stream piece is tried this many times, a growing pause apart, before the download fails. */
        const val SEGMENT_ATTEMPTS = 3
        const val SEGMENT_RETRY_DELAY_MS = 1_000L

        /** A playlist of playlists is followed this deep, never forever. */
        const val MAX_PLAYLIST_HOPS = 3

        /** Longer than this is not a url a site hands out; it is a page's data: blob. */
        const val MAX_STORED_URL_LENGTH = 16_384

        /** Muxer sample buffer floor: a 720p key frame does not fit the default. */
        const val MIN_SAMPLE_BUFFER_BYTES = 1024 * 1024

        /**
         * Muxer sample buffer ceiling. The size is read from the stream itself, and a broken one
         * can ask for hundreds of megabytes; a real 4K key frame is a few.
         */
        const val MAX_SAMPLE_BUFFER_BYTES = 16 * 1024 * 1024

        /** What a download row keeps of text a page or a server supplied. */
        const val MAX_STORED_TITLE_LENGTH = 1_000
        const val MAX_STORED_HEADERS = 32
        const val MAX_HEADER_NAME_LENGTH = 256
        const val MAX_ERROR_LENGTH = 1_000

        /** Sites whose servers break when a file is asked for in parallel parts. */
        val SINGLE_CONNECTION_HOSTS: Set<String> = setOf("bitchute.com")

        const val WORK_NAME_PREFIX = "media_downloader_"
        const val WORK_TAG = "media_downloader"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_ERROR = "error"
        const val LOG_TAG = "MediaDownloader"
        const val DATABASE_NAME = "media_downloader.db"
        const val NOTIFICATION_CHANNEL_ID = "media_downloader_downloads"

        /** Extra on the intent a download notification opens the host with: the download's id. */
        const val EXTRA_DOWNLOAD_ID = "com.markhoor.mediadownloader.DOWNLOAD_ID"

        /** A job's input without a download id reads as this. */
        const val NO_DOWNLOAD_ID = -1L

        /** An AES-128 stream key is exactly this long. */
        const val AES_KEY_BYTES = 16

        /** A "file" this small that starts with the playlist header is a stream after all. */
        const val MAX_PLAYLIST_FILE_BYTES = 5L * 1024 * 1024

        /** Track mime types, as `MediaExtractor` reports them. */
        const val MIME_VIDEO_PREFIX = "video/"
        const val MIME_AUDIO_PREFIX = "audio/"
    }

    /** Names inside a download's scratch folder; a resumed download finds its pieces by them. */
    object Scratch {
        const val PART_PREFIX = "part-"
        const val VIDEO_DIR = "video"

        /** Where a direct download keeps the two tracks it is about to join. */
        const val VIDEO_TRACK = "video.track"
        const val AUDIO_TRACK = "audio.track"
        const val AUDIO_DIR = "audio"
        const val INIT_NAME = "init.seg"
        const val DONE_SUFFIX = ".seg"
        const val PARTIAL_SUFFIX = ".part"
        const val JOINED_NAME = "joined"

        /** Written once a track's pieces are joined and deleted; the joined file is then whole. */
        const val JOINED_MARKER = "joined.done"

        /** The file length the parts were cut for; parts of another length are another file's. */
        const val PARTS_LENGTH_NAME = "parts.length"

        /** A stream key, fetched once per run, and a piece while it is being decrypted. */
        const val KEY_PREFIX = "key-"
        const val DECRYPTED_SUFFIX = ".dec"
    }

    /** HLS playlist tags. */
    object Hls {
        const val STREAM_INF = "#EXT-X-STREAM-INF:"
        const val MEDIA = "#EXT-X-MEDIA:"
        const val EXTINF = "#EXTINF:"
        const val BYTERANGE = "#EXT-X-BYTERANGE:"
        const val MAP = "#EXT-X-MAP:"
        const val ENDLIST = "#EXT-X-ENDLIST"
        const val KEY = "#EXT-X-KEY:"
        const val MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
        const val HEADER = "#EXTM3U"

        /** Encryption methods: none, and the whole-piece AES the downloader can undo. */
        const val METHOD_NONE = "NONE"
        const val METHOD_AES_128 = "AES-128"
    }

    object Json {
        /** A JSON string value, escapes included, up to the closing quote; group 1 is the value. */
        const val STRING_VALUE = """"((?:\\.|[^"\\])*)""""
    }

    object Storage {
        /** Under the public Download folder: `Download/<root>/Websites/<site>/`. */
        const val DEFAULT_ROOT_FOLDER = "All Video Downloader"
        const val WEBSITES_FOLDER = "Websites"
        const val OTHER_SITES_FOLDER = "Website"

        /** Where parts and segments wait, inside the app's private files. */
        const val TEMP_FOLDER = "media_downloader"

        const val MAX_RAW_TITLE_LENGTH = 200
        const val MAX_FILE_NAME_LENGTH = 50
        const val SNIFF_BYTES = 16

        /**
         * How long the answer to "can the download folder be written" is reused. Long enough that
         * carrying a host's old downloads over does not probe the file system once per row, short
         * enough that a permission granted meanwhile is noticed on the next download.
         */
        const val WRITE_PROBE_TTL_MS = 3_000L

        /** The file the probe makes and removes; hidden, so a left-over one is not in the way. */
        const val WRITE_PROBE_NAME = ".media_downloader_write_probe_"

        /** The folder a download is filed under, by the host it came from. First match wins. */
        val SITE_FOLDERS: List<Pair<String, Set<String>>> = listOf(
            "TikTok" to setOf("tiktok.com"),
            "Facebook" to setOf("facebook.com", "fb.com", "fb.watch", "fbcdn.net"),
            "Twitter X" to setOf("x.com", "twitter.com", "twimg.com"),
            "Instagram" to setOf("instagram.com", "cdninstagram.com"),
            "Daily Motion" to setOf("dailymotion.com", "dai.ly", "dmcdn.net"),
            "Threads" to setOf("threads.net", "threads.com"),
        )

        /** The picture formats among [KNOWN_MEDIA_EXTENSIONS]. */
        val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "gif", "webp")

        /** Extensions a file may keep from its url; anything else there is a hash or a token. */
        val KNOWN_MEDIA_EXTENSIONS: Set<String> = setOf(
            "mp4", "webm", "mkv", "mov", "m4v", "3gp",
            "mp3", "m4a", "aac", "opus", "ogg", "wav",
            "jpg", "jpeg", "png", "gif", "webp",
        )
    }

    object Device {
        /** At or below any of these a device is treated as low-end. */
        const val LOW_END_HEAP_MB = 128
        const val LOW_END_TOTAL_MEMORY_BYTES = 3L * 1024 * 1024 * 1024
        const val LOW_END_CORES = 4

        /** Parallel parts of a file and pieces of a stream on a low-end device. */
        const val LOW_END_PARALLEL_PARTS = 4
        const val LOW_END_PARALLEL_SEGMENTS = 4

        /** Largest page or api answer read into memory on a low-end device. */
        const val LOW_END_MAX_RESPONSE_BYTES = 8L * 1024 * 1024

        /**
         * One response may take at most this share of the heap. It is read as bytes and then held
         * as text - three times its size - and the scrapers of one link race each other.
         */
        const val HEAP_SHARE_PER_RESPONSE = 16
    }

    object Browser {
        const val NANOS_PER_MILLI = 1_000_000L


        /** The JavaScript interface the page scripts call; unique so no page or host collides with it. */
        const val BRIDGE_NAME = "MediaDownloaderBridge"
        const val SCRIPT_FOLDER = "media_downloader"

        /**
         * Scripts are injected as a page loads its resources, which can be hundreds a second. One
         * injection per this window, plus one after the burst, rescans every card without parsing
         * a 100 kB script per image.
         */
        const val INJECT_INTERVAL_MS = 500L
        const val INJECT_INTERVAL_LOW_END_MS = 1_200L

        /** How much slower the scripts' own DOM scans run on a low-end device. */
        const val SCAN_SCALE_LOW_END = 2.5

        /** How long a request for media waits for anything before the page is said to have none. */
        const val NOTHING_FOUND_MS = 25_000L

        /**
         * Tells a page script the search it was waiting on is over, so it can stop marking the
         * button that was pressed. Guarded: the site scripts define no such function, and a page
         * may have none of ours left on it at all.
         */
        const val SEARCH_DONE_SCRIPT = "window.mksSearchDone&&window.mksSearchDone();"

        /** How long a tap on a card waits for its restarted player before taking the last stream seen. */
        const val SNIFFER_FALLBACK_MS = 3_500L

        /**
         * How long a press waits to hear the stream of the player it was on before the page's own
         * memory answers instead. A page may hold a player per row - imdb's listings do - and the
         * one stream heard there is not every row's; the player, made to fetch again, says which
         * video it plays within a moment.
         */
        const val PRESSED_STREAM_WAIT_MS = 1_500L

        /** Page commands waiting for a WebView; older ones are dropped, a script is only good for its page. */
        const val COMMAND_BUFFER = 8

        /** A card's slug at least this long is a real title; a heading longer than this is one too. */
        const val MIN_SLUG_TITLE_LENGTH = 12
        const val LONG_DOM_TITLE_LENGTH = 25

        /**
         * A request whose path ends in one of these is a video file, on whatever host - a page's
         * own player asking for it. archive.org and pexels serve theirs this way from hosts no
         * per-site rule names, and their players are out of the page script's reach.
         */
        val VIDEO_FILE_EXTENSIONS: Set<String> = setOf("mp4", "webm", "m4v", "mov")

        /** Media urls remembered per page, so one stream heard twice is offered once. */
        const val MAX_SEEN_MEDIA = 256

        /** Stream masters remembered per page; a feed of players has one per player. */
        const val MAX_PAGE_MASTERS = 16

        /**
         * Requests and resources waiting to be looked at. A heavy page fires hundreds a second; on
         * a slow device past this many the newest are dropped rather than queued without end - a
         * stream asks for its pieces again and again, so one dropped request loses nothing.
         */
        const val MAX_PENDING_BULK_SIGNALS = 512

        /** A page read for the media it names; a real page is far smaller. */
        const val PAGE_READ_MAX_BYTES = 3L * 1024 * 1024

        /** Longer than this is a data: or blob: url, never a request worth reading. */
        const val MAX_SNIFFED_URL_LENGTH = 8_192

        /**
         * Longer than this is not a running time: a player reports a live stream as infinity, and
         * a manifest that states its pieces badly can add up to years. A day is past any video a
         * site offers to download and well short of those.
         */
        const val LONGEST_BELIEVABLE_SECONDS = 24.0 * 60 * 60

        /** Candidate playlists asked for at once when looking beside a segment. */
        const val PLAYLIST_PROBES_AT_ONCE = 21
        const val PLAYLIST_PROBES_AT_ONCE_LOW_END = 7

        /** Media sizes probed at once while a found item is being described. */
        const val SIZE_PROBES_AT_ONCE = 4
        const val SIZE_PROBES_AT_ONCE_LOW_END = 2

        const val BITCHUTE_MEDIA_API = "https://api.bitchute.com/api/beta/video/media"
        const val BITCHUTE_REFERER = "https://www.bitchute.com/"
        const val BITCHUTE_ORIGIN = "https://www.bitchute.com"

        /** The names a CDN gives the playlist beside its segments, nearest-first. */
        val PLAYLIST_NAMES: List<String> = listOf(
            "rendition.m3u8", "index.m3u8", "playlist.m3u8", "chunklist.m3u8",
            "prog_index.m3u8", "media.m3u8", "master.m3u8",
        )

        /** Requests that can never be media, dropped before anything else looks at them. */
        val STATIC_ASSET_EXTENSIONS: Set<String> = setOf(
            "js", "mjs", "css", "woff", "woff2", "ttf", "otf", "eot", "svg", "ico", "json", "map", "wasm",
        )

        /** Hosts adverts, trackers and their creatives are served from. */
        val ADVERT_HOSTS: Set<String> = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "2mdn.net",
            "adservice.google.com", "imasdk.googleapis.com", "amazon-adsystem.com", "adnxs.com",
            "adsrvr.org", "taboola.com", "outbrain.com", "media.net", "criteo.com", "criteo.net",
            "pubmatic.com", "rubiconproject.com", "openx.net", "adform.net", "smartadserver.com",
            "casalemedia.com", "zedo.com", "teads.tv", "connatix.com", "adsafeprotected.com",
            "moatads.com", "scorecardresearch.com", "quantserve.com", "adroll.com",
            "sharethrough.com", "triplelift.com", "indexww.com", "yieldmo.com", "spotxchange.com",
            "springserve.com", "adcolony.com", "applovin.com", "unityads.unity3d.com", "vungle.com",
            "inmobi.com", "smaato.net", "revcontent.com", "mgid.com", "adsterra.com", "popads.net",
            "propellerads.com", "exoclick.com", "juicyads.com", "trafficjunky.net", "ads-twitter.com",
            // The adult networks' creative servers: pre-rolls and banner loops on the tube sites.
            "tsyndicate.com", "trafficstars.com", "adtng.com", "exosrv.com", "magsrv.com",
            "sacdnssedge.com", "growcdnssedge.com", "dreamserve.dev",
        )

        /** Whole path segments advert creatives are filed under; `downloads/` is not `ads/`. */
        val ADVERT_PATH_SEGMENTS: Set<String> = setOf(
            "ads", "ad", "advert", "adverts", "advertising", "advertisement", "adserver", "adservice",
            "creatives", "creative", "banner", "banners", "adimages", "adimg",
        )

        /** Sites served by sniffing their player; their single media pages are known by path. */
        val SNIFFED_SITES: Set<String> = setOf(
            "ted.com", "9gag.com", "vimeo.com", "rumble.com", "twitch.tv", "tumblr.com",
            "snackvideo.com", "mojapp.in", "moj.sharechat.com", "bitchute.com",
        )

        /**
         * Where a post lives on a site the parser reads. A card leading anywhere else is not one of
         * its posts: pinterest's category tiles are pictures inside links to `/ideas/...`, and a
         * button on them could only ever hand over a listing the parser cannot read.
         */
        val PARSER_POST_PATHS: Map<String, String> = mapOf(
            "pinterest.com" to "/pin/",
            // Imdb's home is a poster per film linking to the film, and its editorial cards link to
            // a listing; only a video's own page holds something to download.
            "imdb.com" to "/video/",
        )

        /** Sites the parser reads, as hosts: on their feeds a card's own page is worth handing over. */
        val PARSER_SITE_HOSTS: Set<String> = setOf(
            "linkedin.com", "dailymotion.com", "pinterest.com", "pin.it", "imdb.com", "tiktok.com",
        )
    }

    object Presentation {
        /** How long a ViewModel keeps collecting after its screen stops: a rotation, not a leave. */
        const val STOP_TIMEOUT_MS = 5_000L
    }

    object Titles {
        /**
         * What sites append to a page's title after a separator - " | Facebook",
         * " - Find & Share on GIPHY" - which names the site, not the media. Removed from the end
         * only, and only after a separator, so a title that mentions the site keeps it.
         */
        val SITE_SUFFIXES: List<String> = listOf(
            "Facebook", "Instagram", "LinkedIn", "Threads", "TikTok", "Pinterest", "Dailymotion",
            "Vimeo", "IMDb", "Imgur", "Streamable", "Reddit", "Rumble", "BitChute", "TED Talk", "TED",
            "Internet Archive", "Free Download, Borrow, and Streaming", "Find & Share on GIPHY", "GIPHY",
            "Tumblr", "9GAG", "Twitch", "Pexels",
        )
    }

    object QualityLabels {
        const val HD = "HD"
        const val SD = "SD"
        const val WATERMARK = "Watermark"
        const val IMAGE = "Image"
        const val PAGE = "Page"
        const val MEDIA = "Media"
    }

    object Facebook {
        const val REEL_URL = "https://www.facebook.com/reel/"
        const val PAGE_URL = "https://www.facebook.com/"
        const val WATCH_ROOT = "https://m.facebook.com/watch/"
        const val SESSION_COOKIE = "sb=5ZA9Zy8EyAPTVLLqGVnuMMqR"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/62.0.3202.89 Safari/537.36"
    }

    object Instagram {
        const val REEL_URL = "https://www.instagram.com/reel/"
        const val POST_URL = "https://www.instagram.com/p/"
        const val GRAPHQL_URL = "https://www.instagram.com/graphql/query"
        const val GRAPHQL_DOC_ID = "8845758582119845"

        /** Anonymous browser-session cookies the public endpoints expect; not an account. */
        const val GRAPHQL_COOKIE =
            "csrftoken=KfUBze2TeAG0H4FrGFi0B2; csrftoken=DQXjFKuLZhp53agTq3S7hS; " +
                "ig_did=9B0E8882-43CC-49CF-8596-28E549D03E6E; ig_nrcb=1; mid=Zz2B8AAEAAHWk0oIY1pMO4AGCKvh"
        const val PREVIEW_COOKIE =
            "csrftoken=MuobrszuFzx0mhnd0ERpvp; datr=Xv-IZ1M6UgYV-cFJVnCqOlHq; " +
                "ig_did=A7A681DD-2A45-4641-B645-76B0DDB6A054; dpr=1.5; mid=Z4j_XwABAAHO8JbQLK7gVnxS0-QL; " +
                "wd=480x774; csrftoken=DQXjFKuLZhp53agTq3S7hS; mid=Zz2B8AAEAAHWk0oIY1pMO4AGCKvh"
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; U; Android 9; en-us; SM-G988N Build/JOP24G) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/86.0.4240.198 Mobile Safari/537.36"
        const val SIGNED_IN_USER_AGENT =
            "Mozilla/5.0 (Linux; U; Android 9; en-us; SM-G977N Build/JOP24G) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/95.0.4638.74 Mobile Safari/537.36"

        /** How long an answer without artwork waits for a richer scraper that is about to finish. */
        const val METADATA_GRACE_MS = 1_500L

        /** Only a caption at least this long is taken from a loose `"text"` field. */
        const val MIN_LOOSE_CAPTION_LENGTH = 20
    }

    object Pinterest {
        const val PIN_URL = "https://www.pinterest.com/pin/"
        const val ORIGIN = "https://www.pinterest.com"
        const val GRAPHQL_URL = "https://www.pinterest.com/_/graphql/"
        const val GRAPHQL_QUERY_HASH = "09db395a558573aceb6f502723775a8cbcc59013be571476b3d8bfe6067cd904"
        const val CSRF_TOKEN = "61b97a3612072fb94b88068a6b1230c9"
        const val GRAPHQL_COOKIE = "csrftoken=$CSRF_TOKEN; _pinterest_sess=YOUR_SESSION_HERE;"
        const val GRAPHQL_USER_AGENT =
            "Mozilla/5.0 (Linux; U; Android 9; en-us; SM-G977N Build/JOP24G) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/86.0.4240.198 Mobile Safari/537.36"
        const val PAGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; SM-G977N) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/86.0.4240.198 Mobile Safari/537.36"
    }

    object TikTok {
        const val TIKWM_API_URL = "https://www.tikwm.com/api/"
        const val TIKWM_PLAY_URL = "https://www.tikwm.com/video/media/play/"
    }

    object Twitter {
        const val TWEELOAD_URL = "https://tweeload.aculix.net/status/"

        /** A real status url names itself in its first bytes; urls can be megabytes long. */
        const val MATCH_LIMIT = 2_048
    }

    object Dailymotion {
        const val METADATA_URL = "https://www.dailymotion.com/player/metadata/video/"
        const val METADATA_QUERY =
            "?embedder=https%3A%2F%2Fwww.dailymotion.com%2Fpk&geo=1&player-id=x138o4&locale=en" +
                "&dmV1st=c9d37c06-4f3d-4487-a8ce-8afd9a570772&dmTs=384695&is_native_app=0" +
                "&client_type=webapp&dmViewId=1ij0vegql8241eb7a77&parallelCalls=1&app="
        const val REFERER = "https://www.dailymotion.com/"
        const val ORIGIN = "https://www.dailymotion.com"
    }

    object LinkedIn {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/135.0.0.0 Safari/537.36"
    }

    object GetInDevice {
        const val API_URL = "https://www.getindevice.com/wp-json/aio-dl/video-data"
    }

    object Hosts {

        /** YouTube's pages. Refused unless `MediaDownloaderConfig.allowYouTube`. */
        val YOUTUBE: Set<String> = setOf("youtube.com", "youtu.be", "youtube-nocookie.com")

        /**
         * The hosts YouTube's player streams and draws from. They count as YouTube, so an embed on
         * another page is no way round the rule.
         */
        val YOUTUBE_MEDIA: Set<String> = setOf("googlevideo.com", "ytimg.com", "youtube.googleapis.com")

        /** The sites downloads are offered on when `strictSupportedSitesOnly` is on. */
        val SUPPORTED: Set<String> = setOf(
            // Social & community
            "facebook.com", "fb.watch", "instagram.com", "x.com", "twitter.com", "tiktok.com",
            "threads.net", "threads.com", "pinterest.com", "pin.it", "linkedin.com", "tumblr.com",
            "reddit.com", "redd.it", "snapchat.com", "bsky.app", "mastodon.social", "weibo.com",
            "t.me", "medium.com", "quora.com",
            // Video
            "dailymotion.com", "dai.ly", "vimeo.com", "rumble.com", "odysee.com", "bitchute.com",
            "twitch.tv", "netflix.com", "imdb.com", "ted.com", "snackvideo.com", "mojapp.in",
            "moj.sharechat.com", "streamable.com", "kick.com", "ok.ru", "bilibili.com",
            "nicovideo.jp", "coub.com", "veoh.com", "archive.org", "tamashaweb.com",
            // Images & GIF
            "imgur.com", "giphy.com", "tenor.com", "pexels.com", "unsplash.com", "pixabay.com",
            "flickr.com", "freepik.com", "500px.com", "behance.net", "dribbble.com",
            "artstation.com", "9gag.com", "ifunny.co",
            // News
            "bbc.com", "bbc.co.uk", "dailymail.co.uk", "cnn.com", "nytimes.com", "theguardian.com",
            "aljazeera.com", "reuters.com", "nbcnews.com", "cbsnews.com", "abcnews.go.com",
            "sky.com", "dw.com", "france24.com", "ndtv.com",
            // Pakistan
            "geo.tv", "arynews.tv", "dawn.com", "samaa.tv", "thenews.com.pk", "bolnews.com",
            // Sports
            "espn.com", "espncricinfo.com", "skysports.com", "goal.com", "cricbuzz.com",
            // Education, reference & tech
            "wikipedia.org", "wikimedia.org", "w3schools.com", "analog.com", "khanacademy.org",
            "coursera.org", "udemy.com", "geeksforgeeks.org", "ti.com", "arduino.cc",
            "raspberrypi.com", "fandom.com",
            // Shopping
            "alibaba.com", "aliexpress.com",
        )

        /** Adult sites, their networks and their CDNs. Refused unless `MediaDownloaderConfig.allowAdultSites`. */
        val RESTRICTED: Set<String> = setOf(
            "brazzers.com", "stripchat.com", "xhopen.com", "de.perfecktdamen.co", "de.fapcat.com",
            "pornhits.com", "pornmate.com", "pornhat.com", "xhaccess.com", "xhspot.com",
            // xHamster
            "xhamster.com", "xhamster.desi", "xhamster.one", "xhamster2.com", "xhamster3.com",
            "xhamster15.com", "xhamster45.desi", "xhcdn.com", "xhpingcdn.com",
            // xVideos
            "xvideos.com", "xvideos2.com", "xvideos3.com", "xvideos5.com", "xvideos.es",
            "xvideos.red",
            // XNXX
            "xnxx.com", "xnxx.health", "xnxx-cdn.com", "xnxxx.cc", "xxnxx.pro", "xbxx.me",
            "inxxx.com",
            // PornHub network
            "pornhub.com", "pornhubpremium.com", "pornhub.org", "redtube.com", "redtube.com.br",
            "youporn.com", "tube8.com", "thumbzilla.com", "keezmovies.com", "phncdn.com",
            // SpankBang
            "spankbang.com", "sb-cd.com", "spankbang.cc", "spankbangcdn.com",
            // Other tubes
            "beeg.com", "beeg.xxx", "tnaflix.com", "moviesand.com", "tnaflix.net", "eporner.com",
            "drtuber.com", "sunporno.com", "txxx.com", "hclips.com", "hotmovs.com",
            "bravotube.net", "nuvid.com", "faphouse.com", "fapello.com", "fuq.com",
            "alphaporno.com", "vporn.com", "porntrex.com", "hqporner.com", "xtube.com",
            "sexvid.xxx", "pornktube.com", "ok.xxx", "4tube.com", "pinkrod.com", "porndig.com",
            "eroprofile.com", "gotporn.com", "ashemaletube.com", "shemalez.com", "trannytube.tv",
            "youjizz.com", "motherless.com", "thisvid.com", "empflix.com", "hdzog.com",
            // Studios
            "naughtyamerica.com", "xempire.com", "realitykings.com", "bangbros.com",
            "bangbros18.com", "mofos.com", "wicked.com", "babes.com", "digitalplayground.com",
            "mylf.com", "teamskeet.com",
        )
    }

    /**
     * Words that name an adult site on a host no list contains yet, in three strictness tiers.
     * Measured against real hosts: a single rule either blocked google-analytics.com and
     * essex.gov.uk or missed nearly every adult host, so each word gets only the edge it needs.
     */
    object AdultWords {

        /** Coined words and brand names: matched anywhere in the host. */
        val ANYWHERE: List<String> = listOf(
            "porn", "xxx", "zzz", "nude", "nsfw", "hentai", "milf", "boobs", "fuck", "erotic",
            "camgirl", "xhamster", "xnxx", "brazzers", "spank", "chaturbate", "bongacams",
            "camsoda", "stripchat", "bdsm",
        )

        /** Need a clear left edge: `sexvideos` is a site name, `essex` is a place. */
        val AFTER_LEFT_EDGE: List<String> = listOf("sex", "xvideos")

        /** Need both edges: `anal` opens `analytics` and `analog`. */
        val STANDALONE: List<String> = listOf("anal", "escort")

        /** A mirror name shorter than this is too generic to match on its own. */
        const val MIN_MIRROR_NAME_LENGTH = 4
    }
}
