package com.markhoor.mediadownloader.data.scraper

import com.markhoor.mediadownloader.core.Constants.Scraping
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs several scrapers for one link at once: the first to find media wins and the rest are
 * canceled, so their requests do not keep holding threads after the answer is in. When every
 * one fails, the last failure is the answer.
 *
 * A [backups] reader is raced with the rest - its requests go out at the same time, so it costs no
 * waiting - but its answer is only taken once every other reader has come back empty, or has had
 * [Scraping.BACKUP_GRACE_MS] to answer and not used it. That is for the third-party resolver: it
 * reads a site we read ourselves, less well (no title on an Instagram post, and a Facebook link it
 * cannot resolve is answered with whatever the feed is showing), so being quicker must not be
 * enough to win - and a reader of ours that has stalled must not hold the answer for the whole
 * request timeout either.
 */
internal class ScraperRacer {

    private class Answer(val isBackup: Boolean, val result: Result<ScrapedMediaDto>)

    suspend fun firstSuccess(
        scrapers: List<SiteScraper>,
        url: String,
        backups: List<SiteScraper> = emptyList(),
    ): Result<ScrapedMediaDto> {
        val readers = scrapers + backups
        if (readers.isEmpty()) return Result.failure(NoMediaException("No scraper for $url"))
        return coroutineScope {
            val answers = Channel<Answer>(readers.size)
            val jobs = readers.mapIndexed { index, scraper ->
                launch { answers.send(Answer(index >= scrapers.size, scraper.scrape(url))) }
            }
            var held: ScrapedMediaDto? = null
            var ourFailure: Throwable? = null
            var backupFailure: Throwable? = null
            var oursLeft = scrapers.size
            var waiting = readers.size
            while (waiting > 0) {
                // With a backup's answer in hand, ours are given a grace and then no longer
                // waited for: a stalled request would otherwise hold the parse for a minute.
                val answer = (
                    if (held == null) answers.receive()
                    else withTimeoutOrNull(Scraping.BACKUP_GRACE_MS) { answers.receive() }
                    ) ?: break
                waiting--
                if (!answer.isBackup) oursLeft--
                val media = answer.result.getOrNull()
                when {
                    media == null && answer.isBackup -> backupFailure = answer.result.exceptionOrNull()
                    media == null -> ourFailure = answer.result.exceptionOrNull()
                    // A reader of ours answered: that is the answer, whoever else is still working.
                    !answer.isBackup -> {
                        jobs.forEach { it.cancel() }
                        return@coroutineScope Result.success(media)
                    }
                    else -> held = held ?: media
                }
                val backup = held
                if (backup != null && oursLeft == 0) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope Result.success(backup)
                }
            }
            jobs.forEach { it.cancel() }
            held?.let { return@coroutineScope Result.success(it) }
            // Our own reader's failure is the one worth reporting: it knows the site, and it is
            // what tells a caller that a sign-in would have read the post.
            Result.failure(ourFailure ?: backupFailure ?: NoMediaException("No media at $url"))
        }
    }
}
