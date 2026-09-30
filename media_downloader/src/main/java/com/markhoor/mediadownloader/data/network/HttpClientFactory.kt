package com.markhoor.mediadownloader.data.network

import com.markhoor.mediadownloader.core.Constants.Network
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json

/** The module's one HTTP client and JSON reader, shared by everything that talks to a site. */
internal object HttpClientFactory {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // Sites send null for fields their own schema calls non-null; fall back to the default.
        coerceInputValues = true
        explicitNulls = false
    }

    fun create(): HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = Network.REQUEST_TIMEOUT_MS
            socketTimeoutMillis = Network.REQUEST_TIMEOUT_MS
            requestTimeoutMillis = Network.REQUEST_TIMEOUT_MS
        }
    }

    /**
     * The client media is downloaded with. No limit on a whole request - a large file takes as
     * long as it takes - only on connecting and on a socket that goes quiet.
     */
    fun createForDownloads(): HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = Network.CONNECT_TIMEOUT_MS
            socketTimeoutMillis = Network.REQUEST_TIMEOUT_MS
        }
    }
}
