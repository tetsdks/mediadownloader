package com.markhoor.mediadownloader.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ProxySelector.getDefault
import java.net.ProxySelector.setDefault
import java.net.SocketAddress
import java.net.URI

/**
 * The crash this guard exists for, written down so it stays fixed.
 *
 * Some carriers put a name rather than a host in the APN's proxy field and the device hands it to
 * every http client in the app. OkHttp 5 will not look up a hostname it cannot make canonical, and
 * says so by throwing `IllegalArgumentException`, which is not an `IOException` and so is not a
 * failed request: on an enqueued call OkHttp rethrows it from its own thread, and the app dies.
 *
 * **Whitespace is the whole of it.** This was written saying an upper-case letter was enough,
 * which is not true; asking okhttp 5.5.0 itself:
 *
 * ```
 * "Airtel"  "MyTelnet"  "AIRTEL"  "Airtel-4G"  "My_Telnet"  "café.example"   all accepted
 * "Airtel " "mytelnet " "Non défini" ""                                      all refused
 * ```
 *
 * So the guard drops a name with whitespace in it and nothing else, and the two names below are
 * the ones a crash report actually carried.
 */
class CarrierProxyCrashTest {

    /** The process's own selector is global state; every test here puts it back. */
    private var original: ProxySelector? = null

    @Before
    fun remember() {
        original = getDefault()
    }

    @After
    fun restore() {
        setDefault(original)
    }

    private class Carrier(private val name: String) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> =
            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(name, 8080)))

        override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) = Unit
    }

    /** Nothing listens here, so the call fails at once without asking the network for anything. */
    private val nowhere = Request.Builder().url("http://localhost:1/").build()

    /** The two a crash report carried, and the one this test was first written for. */
    private val reported = listOf("Non défini ", "mytelnet ", "Airtel ")

    @Test
    fun `the client refuses a carrier's proxy name, and not as a failed request`() {
        reported.forEach { name ->
            val client = OkHttpClient.Builder().proxySelector(Carrier(name)).build()

            val thrown = runCatching { client.newCall(nowhere).execute() }.exceptionOrNull()

            assertTrue(
                "expected $name to be refused outright, got $thrown",
                thrown is IllegalArgumentException && thrown.message?.contains(name.trim()) == true,
            )
        }
    }

    @Test
    fun `behind the guard the same proxy is a failed request, which a caller can handle`() {
        reported.forEach { name ->
            val client = OkHttpClient.Builder()
                .proxySelector(SafeProxySelector { Carrier(name) })
                .build()

            val thrown = runCatching { client.newCall(nowhere).execute() }.exceptionOrNull()

            assertTrue("expected an ordinary failure for $name, got $thrown", thrown is IOException)
        }
    }

    /** A name okhttp is happy with is left alone: it is looked up, and simply not found. */
    @Test
    fun `a name without whitespace is handed on, not dropped`() {
        val client = OkHttpClient.Builder()
            .proxySelector(SafeProxySelector { Carrier("MyTelnet") })
            .build()

        val thrown = runCatching { client.newCall(nowhere).execute() }.exceptionOrNull()

        assertTrue("expected an ordinary failure, got $thrown", thrown is IOException)
    }

    // region The guard the module puts in the process's own place

    /**
     * What `initialize` does, and the whole point of doing it there: a client nobody here
     * configured - the host's own, an sdk's - is covered too.
     */
    @Test
    fun `once installed, a client nobody configured fails the request instead of dying`() {
        reported.forEach { name ->
            setDefault(Carrier(name))
            SafeProxySelector.install()

            val thrown = runCatching {
                OkHttpClient.Builder().build().newCall(nowhere).execute()
            }.exceptionOrNull()

            assertTrue("expected an ordinary failure for $name, got $thrown", thrown is IOException)
        }
    }

    /** Twice must not wrap a wrapper: the one installed would then ask itself for ever. */
    @Test
    fun `installing twice leaves the first one in place`() {
        setDefault(Carrier("Non défini "))
        SafeProxySelector.install()
        val first = getDefault()
        SafeProxySelector.install()

        assertSame(first, getDefault())
    }

    /**
     * The limit, written down so nobody reads more into this than it does. A client takes the
     * default when it is built, so an sdk that builds one from a ContentProvider - before
     * `Application.onCreate`, and so before `initialize` - keeps what it was given.
     */
    @Test
    fun `a client built before the install is not covered`() {
        setDefault(Carrier("Non défini "))
        val earlier = OkHttpClient.Builder().build()
        SafeProxySelector.install()

        val thrown = runCatching { earlier.newCall(nowhere).execute() }.exceptionOrNull()

        assertTrue("expected the old selector to still bite, got $thrown", thrown is IllegalArgumentException)
    }

    // endregion
}
