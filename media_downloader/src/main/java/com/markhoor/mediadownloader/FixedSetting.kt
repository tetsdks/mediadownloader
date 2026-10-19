package com.markhoor.mediadownloader

/**
 * A setting that cannot be changed after [MediaDownloader.initialize], because something built once
 * from it is already in use. [MediaDownloader.reconfigure] answers with the ones a call tried to
 * change and did not: each keeps the value `initialize` was given, and everything else in the same
 * config is applied.
 */
enum class FixedSetting {

    /**
     * How hard the device may be worked. How many parts a download is fetched in, how many sizes
     * are probed at once and how often a page is scanned are read from it when each of those is
     * built, and the downloads already running were built with the old answer.
     */
    PerformanceMode,

    /**
     * The readers the host app brought. They decide which hosts count as supported and which
     * scraper answers for a link, both settled when the scrapers were wired together.
     */
    ExtraSources,

    /**
     * The guard in front of the device's proxy list. It is a process-wide default that OkHttp takes
     * a copy of when a client is built, so turning it off later would not reach the clients that
     * already have it, and turning it on later would not reach the host's own.
     */
    GuardCarrierProxies,

    /**
     * The X/Twitter api key. Whether X links have a reader at all is decided when the scrapers are
     * wired together: with no key there is no X reader to hand a link to.
     */
    TwitterApiKey,
}
