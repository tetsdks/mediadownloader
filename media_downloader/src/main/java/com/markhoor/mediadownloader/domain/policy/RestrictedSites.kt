package com.markhoor.mediadownloader.domain.policy

import com.markhoor.mediadownloader.core.Constants.AdultWords
import com.markhoor.mediadownloader.core.Constants.Hosts
import com.markhoor.mediadownloader.core.isUnderAnyOf

/** A kind of site the module restricts, each lifted by its own switch in `MediaDownloaderConfig`. */
internal enum class RestrictedCategory {
    /** YouTube's pages, and the hosts its player streams from. */
    YouTube,

    /** Adult sites - listed, mirrored, or named by their own host. */
    Adult,
}

/**
 * Names the restricted sites. Decides on a normalised host only; a word in a path or a query says
 * nothing about whose site it is. Whether a category is refused is the configuration's call, made
 * in `CheckSiteAccessUseCase`; this only says which category a host belongs to.
 */
internal object RestrictedSites {

    /**
     * The site names behind the restricted list, so numbered or dashed mirrors that no list will
     * keep up with - `xhamster19.com`, `xhamster-mirror.com` - are caught too.
     */
    private val mirrorNames: Set<String> = Hosts.RESTRICTED
        .map { domain -> siteNameOf(domain).trimEnd { it.isDigit() } }
        .filter { it.length >= AdultWords.MIN_MIRROR_NAME_LENGTH }
        .toSet()

    private val wordAfterLeftEdge = edgeRegex(AdultWords.AFTER_LEFT_EDGE, rightEdge = false)
    private val standaloneWord = edgeRegex(AdultWords.STANDALONE, rightEdge = true)

    /** Which restricted kind of site [host] is, or `null` for any other site. */
    fun categoryOf(host: String): RestrictedCategory? = when {
        host.isUnderAnyOf(Hosts.YOUTUBE) || host.isUnderAnyOf(Hosts.YOUTUBE_MEDIA) -> RestrictedCategory.YouTube
        host.isUnderAnyOf(Hosts.RESTRICTED) || isMirrorOfRestrictedSite(host) || namesAnAdultSite(host) ->
            RestrictedCategory.Adult
        else -> null
    }

    private fun isMirrorOfRestrictedSite(host: String): Boolean {
        val labels = host.split('.')
        return mirrorNames.any { name -> labels.any { label -> isMirror(label, of = name) } }
    }

    /** The name itself, or the name followed by digits or a dash. `youtube` is not a `tube` mirror. */
    private fun isMirror(label: String, of: String): Boolean {
        if (label == of) return true
        if (!label.startsWith(of)) return false
        val rest = label.substring(of.length)
        return rest.all { it.isDigit() } || rest.firstOrNull() == '-'
    }

    private fun namesAnAdultSite(host: String): Boolean =
        AdultWords.ANYWHERE.any { host.contains(it) } ||
            wordAfterLeftEdge.containsMatchIn(host) ||
            standaloneWord.containsMatchIn(host)

    /** `de.fapcat.com` is `fapcat`; `xvideos.com` is `xvideos`. */
    private fun siteNameOf(domain: String): String {
        val labels = domain.split('.')
        return if (labels.size > 2 && labels[0].length <= 3) labels[1] else labels[0]
    }

    private fun edgeRegex(words: List<String>, rightEdge: Boolean): Regex =
        Regex("(?<![a-z])(?:${words.joinToString("|")})" + if (rightEdge) "(?![a-z])" else "")
}
