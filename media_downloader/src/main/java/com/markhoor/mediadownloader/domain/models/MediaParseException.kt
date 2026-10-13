package com.markhoor.mediadownloader.domain.models

/** Why a link produced no media. Every parse failure is one of these. */
sealed class MediaParseException(val url: String, message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** YouTube or an adult site the configuration does not allow, or a host the app blocked. */
    class SiteBlocked(url: String) : MediaParseException(url, "Downloads are not allowed from this site")

    /** Not a supported site while `strictSupportedSitesOnly` is on, or not a web link. */
    class SiteUnsupported(url: String) : MediaParseException(url, "This site is not supported")

    /** An allowed site, but not a link any parser understands (a feed, a profile, a home page). */
    class LinkNotRecognised(url: String) : MediaParseException(url, "This link does not point at media")

    /**
     * The link was understood but no media could be read from it.
     *
     * @param needsSignIn the site only answers a signed-in request and the browser holds no
     *   session for it, so there was nothing to read rather than nothing there. Offer the user the
     *   browser and let them sign in; Instagram is like this for every post as of October 2026.
     */
    class MediaNotFound(url: String, cause: Throwable?, val needsSignIn: Boolean = false) :
        MediaParseException(url, if (needsSignIn) SIGN_IN_MESSAGE else NOT_FOUND_MESSAGE, cause)

    private companion object {
        const val NOT_FOUND_MESSAGE = "No media found behind this link"
        const val SIGN_IN_MESSAGE = "Sign in to this site in the browser, then try again"
    }
}
