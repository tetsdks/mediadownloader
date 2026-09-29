package com.markhoor.mediadownloader.data.scraper.pornhub

/**
 * Finds the player's own description of a watch page. The site hands its player a JSON object in a
 * `var flashvars_<id> = {…};` assignment that carries the title, the running time, the cover and
 * one entry per quality, so nothing here reads the page's markup - which changes far more often
 * than the object does.
 *
 * It is also what keeps the adverts out. A watch page plays a pre-roll from an advert network and
 * leaves its `<video>` in the document; anything that went looking for a video element, or for the
 * first media request the page makes, would offer that advert as the download. The pre-roll is not
 * in this object, so it cannot be picked up by mistake.
 */
internal object PornhubParser {

    private val ASSIGNMENT = Regex("""var\s+flashvars_\d+\s*=\s*\{""")

    /** The player object as JSON text, or `null` when the page carries none. */
    fun flashvarsJsonOf(html: String): String? {
        val openingBrace = ASSIGNMENT.find(html)?.range?.last ?: return null
        return objectAt(html, openingBrace)
    }

    /**
     * The JSON object that starts at [open], counted out brace by brace rather than matched by a
     * regex: it nests objects, and holds strings with braces of their own.
     */
    private fun objectAt(text: String, open: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in open until text.length) {
            val character = text[index]
            when {
                escaped -> escaped = false
                inString && character == '\\' -> escaped = true
                character == '"' -> inString = !inString
                inString -> Unit
                character == '{' -> depth++
                character == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, index + 1)
                }
            }
        }
        return null
    }
}
