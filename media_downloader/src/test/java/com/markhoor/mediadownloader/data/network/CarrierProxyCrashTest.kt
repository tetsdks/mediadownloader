package com.markhoor.mediadownloader.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The crash this guard exists for, written down so it stays fixed.
 *
 * Some carriers put a name rather than a host in the APN's proxy field (`Airtel`, `MyTelnet`) and
 * the device hands it to every http client in the app. OkHttp 5 will not look up a hostname that
 * is not canonical - an upper-case letter is enough - and says so by throwing
 * `IllegalArgumentException`, which is not an `IOException` and so is not a failed request: on an
 * enqueued call OkHttp rethrows it from its own thread, and the app dies.
 */
class CarrierProxyCrashTest {

    private class Carrier(private val name: String) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> =
            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(name, 8080)))

        override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) = Unit
    }

    /** Nothing listens here, so the call fails at once without asking the network for anything. */
    private val nowhere = Request.Builder().url("http://localhost:1/").build()

    @Test
    fun `the client refuses a carrier's proxy name, and not as a failed request`() {
        val client = OkHttpClient.Builder().proxySelector(Carrier("Airtel ")).build()

        val thrown = runCatching { client.newCall(nowhere).execute() }.exceptionOrNull()

        assertTrue(
            "expected the hostname to be refused outright, got $thrown",
            thrown is IllegalArgumentException && thrown.message?.contains("Airtel") == true,
        )
    }

    @Test
    fun `behind the guard the same proxy is a failed request, which a caller can handle`() {
        val client = OkHttpClient.Builder()
            .proxySelector(SafeProxySelector { Carrier("Airtel ") })
            .build()

        val thrown = runCatching { client.newCall(nowhere).execute() }.exceptionOrNull()

        assertTrue("expected an ordinary failure, got $thrown", thrown is IOException)
    }
}
