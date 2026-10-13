package com.markhoor.mediadownloader.data.network

import com.markhoor.mediadownloader.core.isUsableHost
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The device's own proxies, with the ones that are not a host left out.
 *
 * Some carriers write a name rather than a host into the APN's proxy field - `Airtel `, `Jio 4G` -
 * and Android hands it on as the proxy to use. OkHttp 5 will not look up a hostname it cannot make
 * canonical, and a name with a space in it is one of those: it throws
 * `IllegalArgumentException: unexpected hostname: Airtel `, which is not an `IOException` and so
 * is not a failed request. On an enqueued call OkHttp tells the callback of a failure and then
 * **rethrows it from its own thread**, where no caller can catch it - a crash in the host app,
 * caused by a phone's settings, on a request nobody could have written differently.
 *
 * A name without whitespace is not refused this way; it is looked up and not found, which is an
 * ordinary failed request. So only the ones that would be refused are dropped.
 *
 * So a proxy whose host the client would refuse is dropped here, before it is ever handed over,
 * and the request goes direct instead. A request that genuinely needed that proxy still fails -
 * as it already did - but it fails as a failed request rather than as a dead process.
 *
 * @param of where the device's own answer comes from; the system's selector unless a test says
 *   otherwise. Read each time, because an app may install its own after this one is built.
 */
internal class SafeProxySelector(
    private val of: () -> ProxySelector? = { getDefault() },
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        val offered = runCatching { of()?.select(uri) }.getOrNull().orEmpty()
        val usable = offered.filter { it.isUsable() }
        // Never an empty list: a selector that offers nothing is one that offers no connection.
        return usable.ifEmpty { listOf(Proxy.NO_PROXY) }
    }

    override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) {
        runCatching { of()?.connectFailed(uri, address, failure) }
    }

    /**
     * A proxy worth offering. One already resolved to an address is fine however it is named -
     * nothing will be looked up - and so is a direct connection; what is dropped is a name that
     * has still to be resolved and could not be.
     */
    private fun Proxy.isUsable(): Boolean {
        if (type() == Proxy.Type.DIRECT) return true
        val address = address() as? InetSocketAddress ?: return false
        if (!address.isUnresolved) return true
        return address.hostString?.isUsableHost() == true
    }
}
