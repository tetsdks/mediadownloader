package com.markhoor.mediadownloader.data.scraper

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Runs several scrapers for one link at once: the first to find media wins and the rest are
 * canceled, so their requests do not keep holding threads after the answer is in. When every
 * one fails, the last failure is the answer.
 */
internal class ScraperRacer {

    suspend fun firstSuccess(scrapers: List<SiteScraper>, url: String): Result<ScrapedMediaDto> {
        if (scrapers.isEmpty()) return Result.failure(NoMediaException("No scraper for $url"))
        return coroutineScope {
            val results = Channel<Result<ScrapedMediaDto>>(scrapers.size)
            val jobs = scrapers.map { scraper -> launch { results.send(scraper.scrape(url)) } }
            var lastFailure: Throwable? = null
            repeat(scrapers.size) {
                val result = results.receive()
                if (result.isSuccess) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope result
                }
                lastFailure = result.exceptionOrNull()
            }
            Result.failure(lastFailure ?: NoMediaException("No media at $url"))
        }
    }
}
