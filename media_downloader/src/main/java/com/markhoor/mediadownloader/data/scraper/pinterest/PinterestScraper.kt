package com.markhoor.mediadownloader.data.scraper.pinterest

import com.markhoor.mediadownloader.core.Constants.Network
import com.markhoor.mediadownloader.core.Constants.Pinterest
import com.markhoor.mediadownloader.core.Constants.QualityLabels
import com.markhoor.mediadownloader.core.isSiteOf
import com.markhoor.mediadownloader.data.network.HttpFetcher
import com.markhoor.mediadownloader.data.scraper.ScrapedMediaDto
import com.markhoor.mediadownloader.data.scraper.ScrapedQualityDto
import com.markhoor.mediadownloader.data.scraper.SiteScraper
import com.markhoor.mediadownloader.domain.models.MediaType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A Pinterest pin, through Pinterest's own GraphQL query.
 *
 * A video pin gives its HLS master (expanded into qualities later). A picture pin gives its
 * picture - and an idea pin, a story of several pictures, gives every page, read from the pin's
 * html because the query does not return them.
 */
internal class PinterestScraper(private val fetcher: HttpFetcher) : SiteScraper() {

    private val pinIdInPath = Regex("""/pin/(\d+)""")
    private val storyPageImage = Regex(""""images_750x"\s*:\s*\{[^}]*?"url"\s*:\s*"([^"]+)"""")

    private val queryHeaders = mapOf(
        "accept" to Network.ACCEPT_JSON,
        "accept-language" to Network.ACCEPT_LANGUAGE,
        "cookie" to Pinterest.GRAPHQL_COOKIE,
        "origin" to Pinterest.ORIGIN,
        "referer" to "${Pinterest.ORIGIN}/",
        "sec-ch-ua" to "\"Android WebView\";v=\"135\", \"Not-A.Brand\";v=\"8\", \"Chromium\";v=\"135\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "Android",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-origin",
        "user-agent" to Pinterest.GRAPHQL_USER_AGENT,
        "x-csrftoken" to Pinterest.CSRF_TOKEN,
        "x-pinterest-appstate" to "active",
        "x-pinterest-graphql-name" to "CloseupPageQuery",
        "x-pinterest-pws-handler" to "www/ideas/[interest]/[id].js",
        "x-requested-with" to "XMLHttpRequest",
    )

    override suspend fun scrapeOrNull(url: String): ScrapedMediaDto? {
        val pinId = pinIdOf(url) ?: return null
        val pin = queryPin(pinId) ?: return null

        pin.video?.let { stream ->
            return ScrapedMediaDto(
                qualities = listOf(ScrapedQualityDto(stream.url.orEmpty(), MediaType.Video, "HLS")),
                title = pin.autoAltText ?: "Pinterest Video",
                thumbnailUrl = stream.thumbnail?.ifBlank { null } ?: pin.images?.url,
            )
        }

        val pages = storyPages(pinId)
        val qualities = when {
            pages.size > 1 -> pages.mapIndexed { index, page ->
                ScrapedQualityDto(page, MediaType.Image, "${QualityLabels.PAGE} ${index + 1}")
            }
            else -> listOfNotNull((pages.firstOrNull() ?: pin.images?.url)?.let {
                ScrapedQualityDto(it, MediaType.Image, QualityLabels.IMAGE)
            })
        }
        return ScrapedMediaDto(
            qualities = qualities,
            title = pin.autoAltText ?: "Pinterest Image",
            thumbnailUrl = pin.images?.url,
        )
    }

    private suspend fun pinIdOf(url: String): String? {
        val pinUrl = if (url.isSiteOf("pin.it")) resolveShortLink(url) ?: return null else url
        return if (pinUrl.contains("--")) {
            pinUrl.substringAfter("--").substringBefore("/")
        } else {
            pinIdInPath.find(pinUrl)?.groupValues?.get(1)
        }
    }

    /** A `pin.it` link's page names the pin it leads to. */
    private suspend fun resolveShortLink(url: String): String? {
        val page = fetcher.getText(url).getOrNull() ?: return null
        val pinPath = page.substringAfter(Pinterest.PIN_URL, "").substringBefore("/", "")
        return pinPath.ifBlank { null }?.let { "${Pinterest.PIN_URL}$it/" }
    }

    private suspend fun queryPin(pinId: String): PinterestPinDto? {
        val body = buildJsonObject {
            put("queryHash", Pinterest.GRAPHQL_QUERY_HASH)
            putJsonObject("variables") {
                put("pinId", pinId)
                put("isAuth", false)
            }
        }.toString()
        val answer = fetcher.postJson(Pinterest.GRAPHQL_URL, body, queryHeaders).getOrThrow()
        return fetcher.json.decodeFromString<PinterestPinResponseDto>(answer).pin
    }

    /** Each page of an idea pin, in story order. The same block appears twice in the html. */
    private suspend fun storyPages(pinId: String): List<String> {
        val html = fetcher.getText("${Pinterest.PIN_URL}$pinId/", mapOf("user-agent" to Pinterest.PAGE_USER_AGENT))
            .getOrNull() ?: return emptyList()
        val story = html.indexOf("\"storyPinData\"").takeIf { it >= 0 }?.let { html.substring(it) } ?: return emptyList()
        return storyPageImage.findAll(story)
            .map { it.groupValues[1].replace("\\/", "/") }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()
    }
}
