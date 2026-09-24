package com.markhoor.mediadownloader

import com.markhoor.mediadownloader.data.hls.HlsQualityReader
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.network.MediaSizeProbe
import com.markhoor.mediadownloader.data.repo.MediaParserRepositoryImpl
import com.markhoor.mediadownloader.data.scraper.ScraperRacer
import com.markhoor.mediadownloader.data.scraper.ScraperResolver
import com.markhoor.mediadownloader.data.scraper.dailymotion.DailymotionScraper
import com.markhoor.mediadownloader.data.scraper.facebook.FacebookShareScraper
import com.markhoor.mediadownloader.data.scraper.facebook.FacebookVideoScraper
import com.markhoor.mediadownloader.data.scraper.fallback.GetInDeviceScraper
import com.markhoor.mediadownloader.data.scraper.instagram.InstagramEmbedScraper
import com.markhoor.mediadownloader.data.scraper.instagram.InstagramGraphQlScraper
import com.markhoor.mediadownloader.data.scraper.instagram.InstagramPreviewScraper
import com.markhoor.mediadownloader.data.scraper.instagram.InstagramScraper
import com.markhoor.mediadownloader.data.scraper.instagram.InstagramSignedInScraper
import com.markhoor.mediadownloader.data.scraper.linkedin.LinkedInScraper
import com.markhoor.mediadownloader.data.scraper.pinterest.PinterestScraper
import com.markhoor.mediadownloader.data.scraper.tiktok.TikTokScraper
import com.markhoor.mediadownloader.data.scraper.twitter.TwitterScraper
import com.markhoor.mediadownloader.domain.usecase.CheckSiteAccessUseCase
import com.markhoor.mediadownloader.domain.usecase.ParseLinkUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Parses real links against the live sites and prints what came back. Skipped unless
 * `MEDIA_DOWNLOADER_LIVE=1`; links to try go in `MEDIA_DOWNLOADER_LINKS`, separated by spaces,
 * and X needs `TWITTER_API_KEY`. A tool for checking the scrapers still work, not a unit test.
 *
 * ```
 * MEDIA_DOWNLOADER_LIVE=1 MEDIA_DOWNLOADER_LINKS="https://..." ./gradlew --no-daemon \
 *     :media_downloader:testDebugUnitTest --tests "*LiveParseCheck"
 * ```
 */
class LiveParseCheck {

    @Test
    fun parseLiveLinks() {
        assumeTrue(System.getenv("MEDIA_DOWNLOADER_LIVE") == "1")
        val links = System.getenv("MEDIA_DOWNLOADER_LINKS").orEmpty().split(' ').filter { it.isNotBlank() }
        val parseLink = parseLinkUseCase(twitterApiKey = System.getenv("TWITTER_API_KEY"))

        runBlocking {
            links.forEach { link ->
                val started = System.currentTimeMillis()
                val result = parseLink(link)
                val seconds = (System.currentTimeMillis() - started) / 1000.0
                println("=== $link  (${seconds}s)")
                result.onSuccess { media ->
                    println("  title    : ${media.title.take(70)}")
                    println("  thumbnail: ${media.thumbnailUrl?.take(90)}")
                    media.qualities.forEach { quality ->
                        println(
                            "  - ${quality.label.padEnd(12)} ${quality.type.name.padEnd(5)} " +
                                "${quality.sizeBytes?.let { "%.2f MB".format(it / 1_048_576.0) } ?: "size ?".padEnd(8)} " +
                                "audio=${quality.audioUrl != null} ${quality.url.take(70)}",
                        )
                    }
                }.onFailure { error ->
                    println("  FAILED: ${error::class.simpleName}: ${error.message} <- ${error.cause?.message?.take(120)}")
                }
            }
        }
    }

    private fun parseLinkUseCase(twitterApiKey: String?): ParseLinkUseCase {
        val client = HttpClientFactory.create()
        val fetcher = HttpFetcher(client, HttpClientFactory.json)
        val sizeProbe = MediaSizeProbe(client)
        val getInDevice = GetInDeviceScraper(fetcher)
        val instagram = InstagramScraper(
            signedIn = InstagramSignedInScraper(fetcher) { null },
            preview = InstagramPreviewScraper(fetcher),
            embed = InstagramEmbedScraper(fetcher),
            graphQl = InstagramGraphQlScraper(fetcher),
            getInDevice = getInDevice,
        )
        val resolver = ScraperResolver(
            facebookVideo = FacebookVideoScraper(fetcher),
            facebookShare = FacebookShareScraper(fetcher, instagram),
            instagram = instagram,
            linkedIn = LinkedInScraper(fetcher),
            tikTok = TikTokScraper(fetcher),
            twitter = twitterApiKey?.let { TwitterScraper(fetcher, it) },
            dailymotion = DailymotionScraper(fetcher, appPackage = "com.markhoor.mediadownloader.livecheck"),
            pinterest = PinterestScraper(fetcher),
            getInDevice = getInDevice,
        )
        return ParseLinkUseCase(
            checkSiteAccess = CheckSiteAccessUseCase(strictSupportedSitesOnly = true, extraBlockedHosts = emptySet()),
            repository = MediaParserRepositoryImpl(
                resolver = resolver,
                racer = ScraperRacer(),
                hlsQualityReader = HlsQualityReader(fetcher, sizeProbe),
                sizeProbe = sizeProbe,
                ioDispatcher = Dispatchers.IO,
            ),
        )
    }
}
