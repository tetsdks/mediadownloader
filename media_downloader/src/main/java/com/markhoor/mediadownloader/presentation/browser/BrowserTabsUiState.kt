package com.markhoor.mediadownloader.presentation.browser

import android.graphics.Bitmap

/**
 * The browser's tabs, as a tab strip draws them.
 *
 * @param tabs left to right, the order a strip shows them in.
 * @param activeId the tab on screen; `null` only before the first tab is opened - closing the
 *   last tab leaves a new, empty one rather than nothing.
 * @param canOpenMore `false` once [com.markhoor.mediadownloader.core.Constants.Browser.MAX_TABS]
 *   are open: [BrowserTabs.open] would refuse.
 * @param canRestoreClosed a closed tab can still be brought back with [BrowserTabs.restoreLastClosed].
 */
data class BrowserTabsUiState(
    val tabs: List<BrowserTabUiState> = emptyList(),
    val activeId: String? = null,
    val canOpenMore: Boolean = true,
    val canRestoreClosed: Boolean = false,
) {
    val count: Int get() = tabs.size
    val active: BrowserTabUiState? get() = tabs.firstOrNull { it.id == activeId }
}

/**
 * One tab.
 *
 * @param favicon the page's own icon, once it sends one.
 * @param preview a picture of the page, for a card in a tab switcher; `null` until the tab has
 *   been drawn once. It is taken as the tab leaves the screen and before its page is put away, and
 *   [BrowserTabs.capturePreview] takes a fresh one of the tab on screen. The bitmap belongs to the
 *   module: draw it, do not recycle it.
 * @param hasLivePage the tab still holds a WebView. A tab without one keeps its page as saved
 *   state - it was put away to leave the memory to the tabs in use - and is restored when selected;
 *   the host needs this for nothing but a hint that selecting it will take a moment.
 */
data class BrowserTabUiState(
    val id: String,
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val favicon: Bitmap? = null,
    val preview: Bitmap? = null,
    val isActive: Boolean = false,
    val hasLivePage: Boolean = true,
) {
    val isLoading: Boolean get() = progress in 1..99

    /** What to write on the tab: its title, or its address until it has one. */
    val label: String get() = title.ifBlank { url }
}
