package com.markhoor.mediadownloader.data.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The proxy a carrier writes a name into. OkHttp refuses a host it cannot make canonical - a name
 * with a space in it - by throwing from its own thread, which kills the app, so such a proxy
 * never reaches it. A name it would merely fail to look up is left alone: that is a failed
 * request, which is nobody's crash.
 */
class SafeProxySelectorTest {

    private val uri = URI("https://www.dailymotion.com/")

    private class Offering(private val proxies: List<Proxy>) : ProxySelector() {
        var toldOfFailure = false
            private set

        override fun select(uri: URI?): List<Proxy> = proxies

        override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) {
            toldOfFailure = true
        }
    }

    private fun named(host: String, port: Int = 8080) =
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))

    private fun selectorOver(vararg proxies: Proxy): Pair<SafeProxySelector, Offering> {
        val device = Offering(proxies.toList())
        return SafeProxySelector { device } to device
    }

    @Test
    fun `a carrier name instead of a host is dropped`() {
        // Exactly what the crash reports carry, trailing space and all.
        val (selector, _) = selectorOver(named("Airtel "))

        assertEquals(listOf(Proxy.NO_PROXY), selector.select(uri))

        val (spaced, _) = selectorOver(named("Jio 4G"))
        assertEquals(listOf(Proxy.NO_PROXY), spaced.select(uri))
    }

    /** A host that is merely wrong is the client's business: it fails the request, it does not throw. */
    @Test
    fun `a host that simply does not resolve is left to the client`() {
        val wrong = named("Airtel")
        val (selector, _) = selectorOver(wrong)

        assertEquals(listOf(wrong), selector.select(uri))
    }

    @Test
    fun `a real proxy is left alone`() {
        val proxy = named("proxy.corp.example.com", 3128)
        val (selector, _) = selectorOver(proxy)

        assertEquals(listOf(proxy), selector.select(uri))
    }

    @Test
    fun `the good ones are kept and the rest left out`() {
        val good = named("10.0.0.1", 8080)
        val (selector, _) = selectorOver(named("My Telnet"), good, named("Airtel "), Proxy.NO_PROXY)

        assertEquals(listOf(good, Proxy.NO_PROXY), selector.select(uri))
    }

    /** A device with no proxy at all, and one whose selector throws: both mean "go direct". */
    @Test
    fun `nothing offered is a direct connection`() {
        val (selector, _) = selectorOver()
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(uri))

        val throwing = SafeProxySelector { error("this device's selector is broken") }
        assertEquals(listOf(Proxy.NO_PROXY), throwing.select(uri))
    }

    @Test
    fun `a failure is still reported to the device's own selector`() {
        val (selector, device) = selectorOver(named("proxy.corp.example.com"))

        selector.connectFailed(uri, InetSocketAddress("10.0.0.1", 8080), IOException("no route"))

        assertEquals(true, device.toldOfFailure)
    }
}
