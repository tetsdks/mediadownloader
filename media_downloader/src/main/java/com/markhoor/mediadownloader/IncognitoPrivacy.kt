package com.markhoor.mediadownloader

/**
 * How private an incognito page can actually be on this device. Ask [MediaDownloader.incognitoPrivacy]
 * before offering incognito, so a reader is never promised more than the device can give.
 *
 * The WebView's cookie jar, its web storage and its cache belong to a *profile*, and a profile
 * other than the default one is only available where the WebView itself offers it - which needs a
 * recent WebView, not a recent Android. Without it every WebView in the process shares one jar and
 * there is nothing this module can do about that, so it says so instead of calling a shared jar
 * private.
 */
enum class IncognitoPrivacy {

    /**
     * A jar of its own. An incognito page's cookies, web storage and cache are not the ordinary
     * browsing's, neither can read the other's, and all of it is emptied when the last incognito
     * page closes.
     */
    Separate,

    /**
     * The one shared jar. An incognito page is still kept out of the saved tabs, and this module
     * never writes its session to disk - but a site signed in to in an incognito page is signed in
     * outside it too, and cookies the page sets outlive it.
     */
    SharedJar,
}
