/*
 * Adapted from RikkaApps/Shizuku manager/adb/AdbMdns.kt, Apache-2.0.
 * https://github.com/RikkaApps/Shizuku
 * http://www.apache.org/licenses/LICENSE-2.0
 * Distributed AS IS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package dev.pavlov.foldlidshim.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.Closeable
import java.net.NetworkInterface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Resolve only addresses belonging to this phone, then connect through loopback. */
@Suppress("DEPRECATION")
class AdbMdns(context: Context, type: String) : Closeable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val ports = LinkedBlockingQueue<Int>()
    private val pending = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var closed = false
    private var discoveryStarted = false
    private var failure: String? = null
    private var selected: String? = null
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = synchronized(this@AdbMdns) {
            discoveryStarted = true
            if (closed) stopDiscovery()
        }
        override fun onDiscoveryStopped(serviceType: String) { }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            synchronized(this@AdbMdns) { failure = "mDNS discovery: $errorCode" }
            ports.offer(-1)
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { }
        override fun onServiceFound(info: NsdServiceInfo) = synchronized(this@AdbMdns) {
            if (!closed) { pending.add(info); resolveNext() }
        }
        override fun onServiceLost(info: NsdServiceInfo) = synchronized(this@AdbMdns) {
            pending.removeAll { it.serviceName == info.serviceName }
            if (selected == info.serviceName) { ports.clear(); selected = null }
        }
    }
    init { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }

    private fun resolveNext() {
        if (closed || resolving || pending.isEmpty()) return
        resolving = true
        manager.resolveService(pending.removeFirst(), object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = synchronized(this@AdbMdns) {
                resolving = false
                resolveNext()
            }
            override fun onServiceResolved(info: NsdServiceInfo) = synchronized(this@AdbMdns) {
                resolving = false
                val host = info.host
                val local = host != null && (host.isLoopbackAddress ||
                    NetworkInterface.getNetworkInterfaces().asSequence().any { iface ->
                        iface.inetAddresses.asSequence().any { it == host }
                    })
                if (!closed && local && info.port in 1..65535) {
                    selected = info.serviceName
                    ports.clear()
                    ports.offer(info.port)
                }
                resolveNext()
            }
        })
    }
    fun awaitPort(timeoutMs: Long): Int? {
        val port = ports.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        check(port > 0) { synchronized(this) { failure ?: "mDNS stopped" } }
        return port
    }
    private fun stopDiscovery() { runCatching { manager.stopServiceDiscovery(listener) } }
    @Synchronized override fun close() {
        closed = true
        pending.clear()
        ports.clear()
        ports.offer(-1)
        if (discoveryStarted) stopDiscovery()
    }
    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    }
}
