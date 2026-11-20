package app.opentv.account.sync

import android.util.Log
import app.opentv.account.AccountCrypto
import app.opentv.remote.CastItem
import app.opentv.remote.RemoteCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.Reader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct device-to-device links on the same Wi-Fi: sync records arrive in milliseconds, what's playing updates
 * the moment it changes, and a phone can drive a TV as a remote. Only ever a shortcut for data (everything also
 * goes through the account server), but the remote works only over this link, for its latency.
 *
 * Security: every frame is sealed with the account key (AES-GCM), so nobody without it can read or forge one. A
 * link starts with a three-step handshake in which each side contributes a fresh random number; every later frame
 * carries the other side's number inside its seal, so a frame recorded earlier can't be replayed into a new link,
 * and a device's clock doesn't matter. Before the handshake completes a connection gets 5 s and 64 KB, and at
 * most 16 such connections are open, so someone else on the network can't exhaust the device.
 *
 * Durable: heartbeats every 5 s, a link silent for 15 s is dropped, and known devices are dialled again every 10 s
 * (and at once when the app comes to the front or the network changes).
 */
class LanLink(
    private val scope: CoroutineScope,
    private val key: ByteArray,
    private val deviceId: String,
    private val onRecords: suspend (List<WireRecord>) -> Unit,
    private val onPresence: (Presence) -> Unit,
    /** The account's time, for stamping frames (not for checking them: the handshake does that). */
    private val now: () -> Long = System::currentTimeMillis,
    private val onCommand: (from: String, RemoteCommand) -> Unit = { _, _ -> },
    /** A device just linked (send it what's playing here straight away). */
    private val onLinked: (String) -> Unit = {},
    /** A device's address that worked, to remember for dialling straight away next time. */
    private val onEndpoint: (Endpoint) -> Unit = {},
    /** Another device asked for a bulk copy: write it with [emit] (false: the connection broke, stop). */
    private val onBulk: suspend (BulkRequest, emit: (BulkChunk) -> Boolean) -> Unit = { _, emit -> emit(BulkChunk(done = true, found = false)) },
    /** Whether a device is still on the account (a removed device keeps the key until it next reaches the server). */
    private val allowed: (String) -> Boolean = { true },
) {
    /**
     * [nonce]: the sender's random number for this link (handshake). [sid]: the receiver's number, proving the frame
     * belongs to this link. [ping]/[pong]: a round-trip probe (the sender's own clock, echoed back unchanged).
     */
    @Serializable data class Frame(val from: String, val at: Long, val records: List<WireRecord> = emptyList(), val presence: Presence? = null,
        val command: RemoteCommand? = null, val to: String? = null, val ping: Long? = null, val pong: Long? = null,
        val nonce: String? = null, val sid: String? = null,
        /** A one-off bulk copy asked for in the dialler's first frame (LAN sync, see [LanCatalogue]). */
        val bulk: BulkRequest? = null, val chunk: BulkChunk? = null)

    /** Asks a device for a playlist source's catalogue. [source] names the source portably ([LanCatalogue.sourceKey]). */
    @Serializable data class BulkRequest(val source: String, val part: String)

    /** Part of a bulk reply: rows of one table, or the end ([done]; [found] false: that device doesn't have it). */
    @Serializable data class BulkChunk(val table: String = "", val columns: List<String> = emptyList(),
        val rows: List<List<kotlinx.serialization.json.JsonElement>> = emptyList(), val done: Boolean = false, val found: Boolean = true)

    /** What a device is doing now. Never stored. */
    @Serializable data class Presence(
        val device: String, val name: String,
        val title: String? = null, val detail: String? = null,
        /** movie, episode, live or recording */
        val kind: String? = null,
        val positionMs: Long? = null,
        val at: Long = 0,
        /** tv, phone or tablet */
        val deviceKind: String = "phone",
        val durationMs: Long? = null,
        val playing: Boolean = false,
        /** What's playing, named portably: "watch here" plays it on another device from the same spot. */
        val item: CastItem? = null,
        /** Takes remote commands (the app is open and in front). */
        val remote: Boolean = false,
    )

    /** How to reach a device directly. */
    @Serializable data class Endpoint(val device: String, val host: String, val port: Int)

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    // The link's own threads for its blocking socket work: the shared IO pool can be busy for minutes with a catalogue
    // download, and a remote press mustn't wait for a free thread there.
    private val io = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "jaskLan-io").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private var server: ServerSocket? = null
    private val jobs = ArrayList<Job>()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val known = ConcurrentHashMap<String, Endpoint>()
    private val pending = AtomicInteger(0)
    private val strikes = ConcurrentHashMap<String, Int>()
    // Records are applied in order on their own coroutine, so a slow apply never holds up remote presses.
    private val recordsIn = Channel<List<WireRecord>>(Channel.UNLIMITED)
    private val _rtt = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Round-trip time to each linked device, in milliseconds. */
    val rtt: StateFlow<Map<String, Long>> = _rtt.asStateFlow()
    private val _linked = MutableStateFlow<Set<String>>(emptySet())
    /** Devices linked directly right now. */
    val linked: StateFlow<Set<String>> = _linked.asStateFlow()

    private fun nonce() = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * One connection to a device. Sending is queued to the connection's own thread: callers (a remote button on the
     * UI thread) never touch the network, and frames go out at once, in order. A failed write drops the link.
     */
    private inner class Peer(var device: String, val socket: Socket, val dialled: Boolean) {
        val mine = nonce()
        @Volatile var theirs: String? = null
        @Volatile var authed = false
        val out = PrintWriter(socket.getOutputStream().bufferedWriter(), true)
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "jaskLan").apply { isDaemon = true } }
        @Volatile var heard = System.currentTimeMillis()
        @Volatile var closed = false
        /** Set on a connection that asked for a bulk copy instead of a link. */
        @Volatile var bulk: BulkRequest? = null
        /** Writes straight to the socket on the calling thread (bulk replies: waits for the network, in order). */
        fun sendNow(f: Frame): Boolean = !closed && runCatching { out.println(seal(f.copy(sid = f.sid ?: theirs))); !out.checkError() }.getOrDefault(false)
        /** Who opened this connection: of two connections between the same devices, both keep the one the lower id opened. */
        val opener get() = if (dialled) deviceId else device
        fun send(f: Frame): Boolean {
            if (closed) return false
            val line = seal(f.copy(sid = f.sid ?: theirs))
            runCatching { writer.execute { if (!runCatching { out.println(line); check(!out.checkError()) }.isSuccess) drop(this) } }
                .onFailure { return false }
            return true
        }
        fun close() { closed = true; writer.shutdown(); runCatching { socket.close() } }
    }

    /** Starts listening, the heartbeat and the redialler. Returns this device's endpoint (null: not on a local network). */
    @Synchronized fun start(address: String? = localAddress()): Endpoint? {
        val host = address ?: return null
        val ss = server ?: ServerSocket(0).also { server = it }
        if (jobs.isEmpty()) {
            jobs += scope.launch(io) {
                while (isActive) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: break
                    if (pending.incrementAndGet() > 16) { pending.decrementAndGet(); runCatching { s.close() }; continue }
                    launch { read(Peer("", s, dialled = false)) }
                }
            }
            jobs += scope.launch {
                while (isActive) {
                    delay(5_000)
                    val cutoff = System.currentTimeMillis() - 15_000
                    for (p in peers.values) if (p.heard < cutoff) drop(p) // silent: the network dropped
                    for (p in peers.values) p.send(Frame(deviceId, now(), ping = System.nanoTime() / 1_000_000)) // heartbeat + probe
                }
            }
            jobs += scope.launch { while (isActive) { redialNow(); delay(10_000) } }
            jobs += scope.launch { for (batch in recordsIn) runCatching { onRecords(batch) }.onFailure { Log.w(TAG, "LAN records failed", it) } }
        }
        return Endpoint(deviceId, host, ss.localPort)
    }

    /** A device of this account (or, from network discovery, possibly someone else's) is at [endpoint]: dial until linked. */
    fun connect(endpoint: Endpoint) {
        // Cores announce themselves like devices: this is a core's current home-network address.
        app.opentv.account.CoreDirectory.seen(endpoint.device, endpoint.host)
        if (endpoint.device == deviceId || !allowed(endpoint.device)) return
        known[endpoint.device] = endpoint
        if (peers.containsKey(endpoint.device)) return
        scope.launch(io) {
            val s = runCatching { Socket().apply { connect(InetSocketAddress(endpoint.host, endpoint.port), 1500); tcpNoDelay = true } }.getOrNull()
                ?: return@launch // not reachable now (asleep, other network): keep trying
            if (peers.containsKey(endpoint.device)) { runCatching { s.close() }; return@launch }
            pending.incrementAndGet()
            val peer = Peer(endpoint.device, s, dialled = true)
            peer.send(Frame(deviceId, now(), nonce = peer.mine)) // step 1
            val linked = read(peer)
            // Never got past the handshake six times running (about a minute): not this account's device (someone
            // else's jaskTV), so stop dialling it. Once or twice can just be a busy or restarting device.
            if (linked) strikes.remove(endpoint.device)
            else if (strikes.merge(endpoint.device, 1, Int::plus)!! >= 6) { known.remove(endpoint.device, endpoint); strikes.remove(endpoint.device) }
        }
    }

    fun connected(): Set<String> = peers.keys

    /** Dials every known device not linked now (the app came to the front, the network changed). */
    fun redialNow() { for (ep in known.values) connect(ep) }

    /** Drops links to devices no longer on the account. */
    fun prune() { for (p in peers.values) if (!allowed(p.device)) drop(p); known.keys.removeAll { !allowed(it) } }

    fun sendRecords(records: List<WireRecord>) { if (records.isNotEmpty()) broadcast(Frame(deviceId, now(), records = records)) }
    fun sendPresence(p: Presence) = broadcast(Frame(deviceId, now(), presence = p))
    /** A remote command to one device. False: not linked to it. */
    fun sendCommand(to: String, c: RemoteCommand): Boolean = peers[to]?.send(Frame(deviceId, now(), command = c, to = to)) ?: false
    /** Sends a ping now (the remote opening): the round-trip time shows within milliseconds. */
    fun probe(to: String) { peers[to]?.send(Frame(deviceId, now(), ping = System.nanoTime() / 1_000_000)) }

    private fun broadcast(frame: Frame) { for (p in peers.values) if (!p.send(frame)) drop(p) }

    private fun drop(peer: Peer) {
        if (peers.remove(peer.device, peer)) { _linked.value = peers.keys.toSet(); _rtt.value = _rtt.value - peer.device }
        peer.close()
    }

    /** The handshake finished: link it (keeping, of two links to one device, the one both sides agree on). */
    private fun link(p: Peer) {
        p.authed = true
        pending.decrementAndGet()
        p.socket.soTimeout = 20_000 // nothing at all (not even a heartbeat) for 20 s: give up on this socket
        val other = peers[p.device]
        if (other != null && other !== p) {
            if (p.opener != minOf(deviceId, p.device)) { p.close(); return }
            drop(other)
        }
        peers[p.device] = p
        _linked.value = peers.keys.toSet()
        if (p.dialled) known[p.device]?.let(onEndpoint)
        onLinked(p.device)
    }

    /** Reads one connection until it closes. Returns whether it ever linked. */
    private suspend fun read(p: Peer): Boolean {
        var everLinked = false
        runCatching {
            p.socket.tcpNoDelay = true
            p.socket.soTimeout = 5_000
            val reader = InputStreamReader(p.socket.getInputStream(), Charsets.UTF_8).buffered(64 * 1024)
            while (true) {
                val line = readLine(reader, if (p.authed) MAX_FRAME else MAX_HELLO) ?: break
                val f = open(line) ?: if (p.authed) continue else break // a stranger: hang up
                if (!p.authed) {
                    when {
                        // Step 2 (on the side that was dialled): the dialler's number; answer with ours.
                        !p.dialled && p.theirs == null && f.nonce != null && f.sid == null -> {
                            if (!allowed(f.from)) break
                            p.device = f.from; p.theirs = f.nonce; p.bulk = f.bulk
                            p.send(Frame(deviceId, now(), nonce = p.mine))
                        }
                        // Step 3 (dialler): our number came back with theirs: linked; confirm.
                        p.dialled && f.sid == p.mine && f.nonce != null && f.from == p.device -> {
                            p.theirs = f.nonce
                            p.send(Frame(deviceId, now(), ping = System.nanoTime() / 1_000_000))
                            link(p); everLinked = true
                        }
                        // Dialled side, a bulk request: answer it on this connection, then hang up (never a link).
                        !p.dialled && p.theirs != null && p.bulk != null && f.sid == p.mine && f.from == p.device -> {
                            p.authed = true; pending.decrementAndGet()
                            p.socket.soTimeout = 60_000
                            val req = p.bulk!!
                            runCatching { onBulk(req) { chunk -> p.sendNow(Frame(deviceId, now(), chunk = chunk)) } }
                                .onFailure { Log.w(TAG, "LAN bulk reply failed", it); p.sendNow(Frame(deviceId, now(), chunk = BulkChunk(done = true, found = false))) }
                            p.close()
                            return false
                        }
                        // Dialled side: the confirmation carries our number: linked.
                        !p.dialled && p.theirs != null && f.sid == p.mine && f.from == p.device -> { link(p); everLinked = true }
                        else -> break
                    }
                    if (!p.authed) continue
                } else if (f.sid != p.mine || f.from != p.device) continue // not from this link (a replay): ignore
                if (p.closed) break
                p.heard = System.currentTimeMillis()
                f.ping?.let { p.send(Frame(deviceId, now(), pong = it)) }
                f.pong?.let { _rtt.value = _rtt.value + (p.device to (System.nanoTime() / 1_000_000 - it)) }
                if (f.records.isNotEmpty()) recordsIn.trySend(f.records)
                f.presence?.let(onPresence)
                if (f.command != null && f.to == deviceId) onCommand(f.from, f.command)
            }
        }.onFailure { Log.d(TAG, "LAN link closed: ${it.message}") }
        if (!p.authed) pending.decrementAndGet()
        drop(p)
        return everLinked
    }

    /**
     * Asks [device] (linked, or at least known) for a bulk copy and hands each chunk to [onChunk] as it arrives.
     * A separate connection, so the link (remote presses, presence) isn't held up. True: it arrived complete.
     */
    suspend fun fetch(device: String, request: BulkRequest, onChunk: suspend (BulkChunk) -> Unit): Boolean =
        kotlinx.coroutines.withContext(io) {
            val ep = known[device] ?: return@withContext false
            val s = runCatching { Socket().apply { connect(InetSocketAddress(ep.host, ep.port), 1500); tcpNoDelay = true; soTimeout = 60_000 } }
                .getOrNull() ?: return@withContext false
            val p = Peer(device, s, dialled = true)
            try {
                if (!p.sendNow(Frame(deviceId, now(), nonce = p.mine, bulk = request))) return@withContext false
                val reader = InputStreamReader(s.getInputStream(), Charsets.UTF_8).buffered(256 * 1024)
                while (true) {
                    val f = open(readLine(reader, MAX_FRAME) ?: return@withContext false) ?: return@withContext false
                    if (f.from != device || f.sid != p.mine) return@withContext false
                    if (p.theirs == null) {
                        // Their number: confirm, and the reply starts.
                        p.theirs = f.nonce ?: return@withContext false
                        if (!p.sendNow(Frame(deviceId, now(), ping = 0))) return@withContext false
                        continue
                    }
                    val c = f.chunk ?: continue
                    if (c.done) return@withContext c.found
                    onChunk(c)
                }
                @Suppress("UNREACHABLE_CODE") false
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Log.d(TAG, "LAN bulk fetch failed: ${e.message}"); false }
            finally { p.close() }
        }

    /** A line of at most [max] characters (longer: the connection is dropped), or null at the end. */
    private fun readLine(r: Reader, max: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val c = r.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (sb.length >= max) throw IllegalStateException("Frame too long")
            sb.append(c.toChar())
        }
    }

    private fun seal(f: Frame) = AccountCrypto.seal(key, "lan", "frame", json.encodeToString(Frame.serializer(), f))
    private fun open(line: String): Frame? = runCatching { json.decodeFromString(Frame.serializer(), AccountCrypto.open(key, "lan", "frame", line)) }
        .getOrNull()?.takeIf { it.from != deviceId }

    /** Cuts every link as a network drop would (tests): the listening socket stays, as it does when Wi-Fi blips. */
    internal fun dropLinksForTest() { peers.values.forEach { it.close() } }

    @Synchronized fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        runCatching { server?.close() }; server = null
        peers.values.forEach { it.close() }; peers.clear(); known.clear()
        _linked.value = emptySet()
    }

    companion object {
        private const val TAG = "jaskLan"
        private const val MAX_HELLO = 64 * 1024
        private const val MAX_FRAME = 8 * 1024 * 1024
        /** This device's private IPv4 address on Wi-Fi or Ethernet. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
