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

    internal companion object {

        /**
         * Puts one in front of whatever the process already had, once.
         *
         * The two clients this module builds carry one of these themselves, which is enough for
         * the module's own requests and nothing else. The crash is not the module's own requests:
         * the phone's proxy is handed to **every** client in the process, and a host app that
         * builds its own - or an sdk whose code the host does not have - dies on it just the same.
         * So the module puts one in the process's own place rather than making each host find
         * this out and write it again. [MediaDownloaderConfig.guardCarrierProxies] turns it off
         * for a host that would rather own that decision.
         *
         * Two things it cannot do. **A client takes the default when it is built, not when it
         * calls** - measured - so anything built before `initialize` keeps what it was given; an
         * sdk that starts from a ContentProvider runs before `Application.onCreate` and is one of
         * those. And a host that sets its own default afterwards replaces this, as it should.
         *
         * The one it replaces is captured and asked for every answer; it is not looked up again,
         * because by then this *is* the answer and it would ask itself for ever. Installing twice
         * leaves the first in place rather than wrapping a wrapper.
         */
        fun install() {
            runCatching {
                val system = getDefault()
                if (system is SafeProxySelector) return
                setDefault(SafeProxySelector { system })
            }
        }
    }
}
