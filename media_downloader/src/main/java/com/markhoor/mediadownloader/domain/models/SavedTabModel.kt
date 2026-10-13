package com.markhoor.mediadownloader.domain.models

/**
 * One tab as it is kept between runs: the page it was on, what to call it until that page says so
 * again, and whether it was the one on screen.
 *
 * A browser's tabs are the user's, not the session's - they are expected back after the app is
 * closed, killed or the phone restarted, and to go only when the tabs are closed, the app's data
 * is cleared or the app is uninstalled.
 */
internal data class SavedTabModel(
    val url: String = "",
    val title: String = "",
    val isActive: Boolean = false,
)
