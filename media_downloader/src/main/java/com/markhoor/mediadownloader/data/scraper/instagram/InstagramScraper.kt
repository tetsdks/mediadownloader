package com.markhoor.mediadownloader.data.scraper.instagram

import android.util.Log
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.core.Constants.Instagram
import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.Scraping
import com.markhoor.mediadownloader.core.cookieValue
import com.markhoor.mediadownloader.core.isSiteOf
import com.markhoor.mediadownloader.core.isThreadsShareLink
import com.markhoor.mediadownloader.core.metaProperty
import com.markhoor.mediadownloader.data.network.CookieSource
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.MediaGoneException
import com.markhoor.mediadownloader.data.scraper.NoMediaException
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.SignInRequiredException
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An Instagram post or reel, or a Threads post.
 *
 * Four readers of our own run at once and the third-party resolver with them, and they are ranked
 * rather than raced: being quickest is never enough to win, because they do not all answer equally
 * well.
 *
 * 1. **The signed-in reader** - it is the only one that sees what the user sees, so a private or
 *    age-gated post is its alone. It wins whenever it answers, and when another reader has already
 *    answered it is still given [Scraping.SIGNED_IN_GRACE_MS] to, because it reads a whole page to
 *    answer and the page is sometimes slow.
 * 2. **A reader of ours with artwork**, at once.
 * 3. **A reader of ours without artwork**, after [Instagram.METADATA_GRACE_MS] spent waiting for a
 *    richer answer.
 * 4. **The resolver**, when ours are done, or after [Scraping.BACKUP_GRACE_MS] with nothing from
 *    them: it is somebody else's service, reading a site this module reads itself, and it answers
 *    an Instagram post without a title.
 */
internal class InstagramScraper(
    private val fetcher: HttpFetcher,
    private val signedIn: SiteScraper,
    private val preview: SiteScraper,
    private val embed: SiteScraper,
    private val graphQl: SiteScraper,
    private val getInDevice: SiteScraper,
    private val cookies: CookieSource = CookieSource { null },
) : SiteScraper() {

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? = coroutineScope {
        val postUrl = postAddressOf(url) ?: return@coroutineScope null
        val asTheUserSeesIt = CompletableDeferred<ScrapedMediaDto>()
        val signedInJob = launch { signedIn.scrape(postUrl).getOrNull()?.let(asTheUserSeesIt::complete) }
        val withArtwork = CompletableDeferred<ScrapedMediaDto>()
        val withoutArtwork = CompletableDeferred<ScrapedMediaDto>()
        val fallbacks = fallbacksFor(postUrl).map { scraper ->
            launch {
                val media = scraper.scrape(postUrl).getOrNull() ?: return@launch
                if (media.thumbnailUrl.isNullOrBlank()) withoutArtwork.complete(media) else withArtwork.complete(media)
            }
        }

        // Raced with the rest so it costs no waiting, but read last: it answers without a title,
        // and it is somebody else's service reading a site this module reads itself.
        val saidGone = AtomicBoolean(false)
        val backup = async {
            getInDevice.scrape(postUrl)
                .onFailure { noteWhyTheResolverSaidNothing(it, saidGone) }
                .getOrNull()
        }
        val chosen = bestAnswer(
            asTheUserSeesIt = asTheUserSeesIt,
            signedInJob = signedInJob,
            withArtwork = withArtwork,
            withoutArtwork = withoutArtwork,
            fallbacks = fallbacks,
            backup = backup,
        )
        (fallbacks + signedInJob).forEach { it.cancel() }
        backup.cancel()
        chosen ?: refuseWithoutSession(postUrl, saidGone.get())
    }

    /**
     * Nothing was read. Instagram serves a signed-out request a page with no media in it - no
     * `video_versions`, an embed whose `contextJSON` is null - and its api answers a signed-out
     * request nothing either, so with no session there was never anything to find and the host is
     * told that signing in is what would fix it. With a session it is this post that could not be
     * read, which is the ordinary nothing-found.
     *
     * Unless [saidGone]: the resolver reads a post as a signed-in account, so a post **it** cannot
     * find is not one a session would have shown. Measured on a reel that did not exist, where
     * every reader of ours was locked out and only the resolver could tell the difference - asking
     * for a sign-in there sends the user to do something that cannot help.
     */
    /**
     * What the resolver's silence meant. [MediaGoneException] and [NoMediaException] are answers -
     * it was read, and it had nothing or said the post is not there. Anything else is the resolver
     * itself failing: its api is somebody else's, with no promises, and when it changes under this
     * module every signed-out post quietly loses the one reader that did not need a session.
     *
     * That is said out loud because nothing else says it. The failure a host sees is the same
     * `needsSignIn` either way - sign in is still what the user can do - so a resolver that has
     * gone dark looks exactly like an ordinary signed-out post, and went unnoticed for a fortnight
     * when its api put a bot check in front of itself. A line in the log is what would have shown
     * it. See `GetInDeviceScraper` and HOST-GUIDE section 9.6.
     */
    private fun noteWhyTheResolverSaidNothing(error: Throwable, saidGone: AtomicBoolean) {
        when (error) {
            is MediaGoneException -> saidGone.set(true)
            is NoMediaException -> Unit
            else -> Log.w(Download.LOG_TAG, "The resolver could not be read; a signed-out post has nothing left to answer it", error)
        }
    }

    private fun refuseWithoutSession(postUrl: String, saidGone: Boolean): ScrapedMediaDto? {
        if (saidGone) return null
        val session = cookies.cookiesFor(postUrl)?.cookieValue(Instagram.SESSION_COOKIE)
        if (session == null) throw SignInRequiredException(siteNameOf(postUrl))
        return null
    }

    private fun siteNameOf(url: String) = if (url.isSiteOf("threads.net", "threads.com")) "Threads" else "Instagram"

    /**
     * The post's own address. Threads' share sheet hands out `threads.com/share/<code>`, which
     * names no post: every reader below builds its request from the post's address, so a shared
     * link reached none of them and was turned away as not pointing at a post. The page the share
     * link serves is a redirect to the post and names it in its `og:url`, which is what is read
     * here - one request, and only for a share link.
     */
    private suspend fun postAddressOf(url: String): String? =
        if (!url.isThreadsShareLink()) {
            url
        } else {
            fetcher.getText(url, mapOf("Accept" to Network.ACCEPT_HTML)).getOrNull()
                ?.metaProperty("og:url")?.takeIf { it.contains("/post/") }
        }

    /**
     * Our own readers of a post. A `/p/` post is answered by GraphQL alone: the post's own page
     * and its embed page are a reel's readers and have nothing in them for it - which, since
     * Instagram signed the public out of GraphQL, left a photo post nothing at all until the
     * third-party backup above was given it too.
     */
    private fun fallbacksFor(url: String): List<SiteScraper> =
        if (url.contains("/p/")) listOf(graphQl) else listOf(preview, embed, graphQl)

    /**
     * The best answer of the five readers, by the ranking in this class's own documentation, and
     * `null` only once every one of them has come back empty.
     *
     * Each branch below either answers or waits for ever and is canceled at the end; **none of
     * them may finish with `null`** except `whenAllDone`, which is the one that means there was
     * nothing. A branch that completed with nothing would end the `select` and take the readers
     * still working down with it - which a failing resolver did, before its branch learned to wait.
     */
    private suspend fun bestAnswer(
        asTheUserSeesIt: CompletableDeferred<ScrapedMediaDto>,
        signedInJob: Job,
        withArtwork: CompletableDeferred<ScrapedMediaDto>,
        withoutArtwork: CompletableDeferred<ScrapedMediaDto>,
        fallbacks: List<Job>,
        backup: Deferred<ScrapedMediaDto?>,
    ): ScrapedMediaDto? = coroutineScope {
        val afterGrace = async { withoutArtwork.await().also { delay(Instagram.METADATA_GRACE_MS) } }
        // One of ours answered. The user's own view is worth a moment's wait, and no more.
        val oursThenTheReader = async {
            val ours = select<ScrapedMediaDto> {
                withArtwork.onAwait { it }
                afterGrace.onAwait { it }
            }
            withTimeoutOrNull(Scraping.SIGNED_IN_GRACE_MS) { signedInJob.join() }
            if (asTheUserSeesIt.isCompleted) asTheUserSeesIt.await() else ours
        }
        val whenAllDone = async {
            (fallbacks + signedInJob).joinAll()
            when {
                asTheUserSeesIt.isCompleted -> asTheUserSeesIt.await()
                withoutArtwork.isCompleted -> withoutArtwork.await()
                else -> backup.await()
            }
        }
        val backupAfterGrace = async {
            val media = backup.await() ?: awaitCancellation()
            delay(Scraping.BACKUP_GRACE_MS)
            // A reader of ours answered inside the grace and is only being held for the signed-in
            // one: that answer is better than this, so this branch stands down and waits.
            if (withArtwork.isCompleted || withoutArtwork.isCompleted) awaitCancellation()
            media
        }
        val chosen = select<ScrapedMediaDto?> {
            asTheUserSeesIt.onAwait { it }
            oursThenTheReader.onAwait { it }
            whenAllDone.onAwait { it }
            backupAfterGrace.onAwait { it }
        }
        afterGrace.cancel()
        oursThenTheReader.cancel()
        whenAllDone.cancel()
        backupAfterGrace.cancel()
        chosen
    }
}
