package com.markhoor.mediadownloader.presentation.browser

/**
 * The tabs a browser holds: their order, which one is shown, which one opened which, and which of
 * them have earned a live page. No WebView and no Android type anywhere in here - [BrowserTabs]
 * owns those and asks this what to do with them - so every rule below is read in a plain test.
 *
 * @param maxTabs how many tabs may be open at once.
 */
internal class TabRoster(private val maxTabs: Int) {

    /** Left to right, as a tab strip shows them. */
    private val order = mutableListOf<String>()

    /** Which tab opened which, for a tab opened by a link in another. */
    private val openers = mutableMapOf<String, String>()

    /** Least recently shown first, so the tab that gives its page up first is the one at the front. */
    private val byUse = mutableListOf<String>()

    var activeId: String? = null
        private set

    val ids: List<String> get() = order.toList()
    val size: Int get() = order.size
    val canOpenMore: Boolean get() = order.size < maxTabs

    fun isOpen(id: String): Boolean = id in order

    /**
     * Adds a tab. A tab opened from another goes directly to its right, where a browser keeps a
     * link's tab beside the page that opened it; anything else goes at the end. It counts as just
     * used even when it opens in the background, because it is loading either way - the page it
     * was opened for is the last thing worth throwing away.
     *
     * @return `false` when [maxTabs] are already open, or the id is already in use.
     */
    fun open(id: String, select: Boolean, openerId: String? = null): Boolean {
        if (!canOpenMore || id in order) return false
        val beside = openerId?.let { order.indexOf(it) } ?: -1
        if (beside >= 0) order.add(beside + 1, id) else order.add(id)
        if (beside >= 0 && openerId != null) openers[id] = openerId
        byUse.add(id)
        // Something has to be on screen: the first tab is shown whether or not it asked to be.
        if (select || activeId == null) select(id)
        return true
    }

    fun select(id: String): Boolean {
        if (id !in order) return false
        activeId = id
        byUse.remove(id)
        byUse.add(id)
        return true
    }

    /**
     * Removes a tab. Closing the one on screen shows the tab that opened it - a link's tab closed
     * puts you back on the page you followed it from - or else its right neighbour, or its left.
     */
    fun close(id: String): Boolean {
        val at = order.indexOf(id)
        if (at < 0) return false
        val opener = openerOf(id)
        order.removeAt(at)
        byUse.remove(id)
        openers.remove(id)
        if (activeId == id) {
            activeId = null
            val next = opener ?: order.getOrNull(at) ?: order.getOrNull(at - 1)
            next?.let { select(it) }
        }
        // A tab that opened this one is still open; one closed with it is not its child's opener.
        openers.entries.removeAll { (_, opener) -> opener == id }
        return true
    }

    /** The tab a link in another opened this one from, while that one is still open. */
    fun openerOf(id: String): String? = openers[id]?.takeIf { it in order }

    /**
     * Which of the [live] tabs must give their page up to leave at most [budget] of them live,
     * least recently shown first. The tab on screen is never one of them, and neither is [keep] -
     * a tab in the middle of a callback of its own cannot have its WebView destroyed underneath it.
     */
    fun overBudget(live: Collection<String>, budget: Int, keep: String? = null): List<String> {
        val over = live.size - maxOf(budget, 1)
        if (over <= 0) return emptyList()
        val sparable = live.filterTo(mutableSetOf()) { it != activeId && it != keep }
        return byUse.filter { it in sparable }.take(over)
    }
}
