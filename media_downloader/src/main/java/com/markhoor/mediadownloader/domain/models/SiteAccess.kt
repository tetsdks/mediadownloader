package com.markhoor.mediadownloader.domain.models

/** Whether downloads may be offered for a url's site. */
enum class SiteAccess {

    /** Downloads may be offered. */
    Allowed,

    /**
     * YouTube or an adult site the configuration does not allow (both refused by default), or one of
     * the app's `extraBlockedHosts`.
     */
    Blocked,

    /** Not on the supported list while `strictSupportedSitesOnly` is on, or not a web page. */
    Unsupported,
}
