package com.markhoor.mediadownloader.presentation.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tab rules on their own: order, what is shown, and which page is given up next. */
class TabRosterTest {

    private val roster = TabRoster(maxTabs = 4)

    @Test
    fun `the first tab is shown whether or not it asked to be`() {
        assertTrue(roster.open("a", select = false))

        assertEquals("a", roster.activeId)

        roster.open("b", select = false)
        assertEquals("a", roster.activeId)
    }

    @Test
    fun `a tab opened from another sits beside it, and closing it goes back to it`() {
        roster.open("a", select = true)
        roster.open("b", select = true)
        roster.select("a")
        roster.open("fromA", select = true, openerId = "a")

        assertEquals(listOf("a", "fromA", "b"), roster.ids)
        assertEquals("a", roster.openerOf("fromA"))

        roster.close("fromA")
        assertEquals("a", roster.activeId)
    }

    @Test
    fun `closing the shown tab shows its right neighbour, and the last one its left`() {
        listOf("a", "b", "c").forEach { roster.open(it, select = true) }

        roster.select("b")
        roster.close("b")
        assertEquals("c", roster.activeId)

        roster.close("c")
        assertEquals("a", roster.activeId)

        roster.close("a")
        assertNull(roster.activeId)
        assertEquals(0, roster.size)
    }

    /** A tab whose opener was closed before it has nowhere to go back to. */
    @Test
    fun `an opener that is gone is not offered`() {
        roster.open("a", select = true)
        roster.open("fromA", select = true, openerId = "a")

        roster.close("a")

        assertNull(roster.openerOf("fromA"))
        assertEquals("fromA", roster.activeId)
    }

    @Test
    fun `no more tabs than the maximum`() {
        listOf("a", "b", "c", "d").forEach { assertTrue(roster.open(it, select = false)) }

        assertFalse(roster.canOpenMore)
        assertFalse(roster.open("e", select = true))
        assertFalse("the one it refused is not shown", roster.activeId == "e")

        roster.close("a")
        assertTrue(roster.canOpenMore)
        assertTrue(roster.open("e", select = true))
    }

    @Test
    fun `the least recently shown tab gives its page up first`() {
        listOf("a", "b", "c", "d").forEach { roster.open(it, select = true) }
        val live = roster.ids

        assertEquals(listOf("a", "b"), roster.overBudget(live, budget = 2))
        assertEquals(emptyList<String>(), roster.overBudget(live, budget = 4))

        roster.select("a")
        assertEquals(listOf("b", "c"), roster.overBudget(live, budget = 2))
    }

    @Test
    fun `the shown tab and the one in a callback keep their pages`() {
        listOf("a", "b", "c", "d").forEach { roster.open(it, select = true) }
        val live = roster.ids

        // Three must go to leave one: the shown tab is not one of them, nor is "a", busy opening
        // a window - a WebView destroyed inside its own callback takes the app with it.
        assertEquals(listOf("b", "c"), roster.overBudget(live, budget = 1, keep = "a"))
    }

    /** A tab opened in the background is loading, so it is the last page worth throwing away. */
    @Test
    fun `a background tab counts as just used`() {
        roster.open("a", select = true)
        roster.open("b", select = true)
        roster.open("background", select = false)

        assertEquals("b", roster.activeId)
        assertEquals(listOf("a"), roster.overBudget(roster.ids, budget = 2))
    }
}
