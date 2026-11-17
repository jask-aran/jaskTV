package app.opentv.account.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the account's other devices on the same Wi-Fi with the network's own service discovery (mDNS), so they
 * link even with no internet. Each device advertises "_jasktv._tcp" with its device id; what it finds is handed
 * to [LanLink.connect], where only a device holding the account key gets past the first frame.
 */
class LanDiscovery(context: Context, private val deviceId: String, private val found: (LanLink.Endpoint) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    // Older Android resolves one service at a time: queue them.
    private val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    fun start(port: Int) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = "jaskTV-" + deviceId.take(8)
            serviceType = TYPE
            this.port = port
            setAttribute("d", deviceId)
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "Advertise failed: $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = Unit
        }.also { runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) } }
        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "Discovery failed: $code") }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceName.startsWith("jaskTV-") && !s.serviceName.endsWith(deviceId.take(8))) { queue.add(s); next() }
            }
            override fun onServiceLost(s: NsdServiceInfo) = Unit
        }.also { runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, it) } }
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (!resolving.compareAndSet(false, true)) return
        val s = queue.poll() ?: run { resolving.set(false); return }
        runCatching {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) { resolving.set(false); next() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val id = i.attributes["d"]?.let { String(it) }
                    val host = i.host?.hostAddress
                    if (id != null && host != null && id != deviceId) found(LanLink.Endpoint(id, host, i.port))
                    resolving.set(false); next()
                }
            })
        }.onFailure { resolving.set(false) }
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
        queue.clear()
    }

    companion object {
        private const val TAG = "jaskLan"
        const val TYPE = "_jasktv._tcp."
    }
}
