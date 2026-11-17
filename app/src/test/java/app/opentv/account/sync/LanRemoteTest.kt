package app.opentv.account.sync

import app.opentv.account.AccountCrypto
import app.opentv.remote.CastItem
import app.opentv.remote.RemoteCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Wi-Fi link as a remote: commands reach only the device they're for, strangers can't send any, and a dropped link comes back by itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LanRemoteTest {
    private val key = AccountCrypto.newAccountKey()
    // Its own threads: the full suite's other tests share Dispatchers.IO, and blocking socket reads mustn't queue behind them.
    private val scope = CoroutineScope(java.util.concurrent.Executors.newCachedThreadPool().asCoroutineDispatcher())

    private fun waitFor(ms: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("timed out", ok())
    }

    @Test fun strangersCantCrashFloodOrReplay() = runBlocking {
        val commands = Channel<RemoteCommand>(Channel.UNLIMITED)
        val tv = LanLink(scope, key, "tv-1111", onRecords = {}, onPresence = {}, onCommand = { _, c -> commands.trySend(c) })
        val ep = tv.start("127.0.0.1")!!
        // An endless line with no newline: cut off at 64 KB, not read into memory forever.
        val flood = java.net.Socket("127.0.0.1", ep.port)
        val sent = runCatching { val o = flood.getOutputStream(); repeat(64) { o.write(ByteArray(64 * 1024) { 'x'.code.toByte() }) } }
        assertTrue("the TV hangs up on an endless line", sent.isFailure || flood.getInputStream().read() == -1)
        // 20 idle connections: at most 16 are held, each for 5 s; then a real device links normally.
        val idle = List(20) { runCatching { java.net.Socket("127.0.0.1", ep.port) }.getOrNull() }
        // A frame sealed with the right key but not part of a handshake (a replay of a recorded frame) is refused.
        val json = kotlinx.serialization.json.Json
        java.net.Socket("127.0.0.1", ep.port).use { raw ->
            val w = java.io.PrintWriter(raw.getOutputStream(), true)
            val replay = LanLink.Frame("phone-2222", 0, command = RemoteCommand("release"), to = "tv-1111", sid = "recorded-earlier")
            w.println(AccountCrypto.seal(key, "lan", "frame", json.encodeToString(LanLink.Frame.serializer(), replay)))
            Thread.sleep(300)
        }
        assertTrue(commands.tryReceive().isFailure)
        val phone = LanLink(scope, key, "phone-2222", onRecords = {}, onPresence = {})
        phone.start("127.0.0.1"); phone.connect(ep)
        waitFor(20_000) { "tv-1111" in phone.linked.value }
        assertTrue(phone.sendCommand("tv-1111", RemoteCommand("playPause")))
        assertEquals("playPause", withTimeout(2000) { commands.receive() }.type)
        idle.forEach { runCatching { it?.close() } }; flood.close()
        listOf(tv, phone).forEach { it.stop() }
    }

    @Test fun bothDiallingAtOnceSettlesOnOneLink() = runBlocking {
        val a = LanLink(scope, key, "aaaa", onRecords = {}, onPresence = {})
        val b = LanLink(scope, key, "bbbb", onRecords = {}, onPresence = {})
        val ea = a.start("127.0.0.1")!!; val eb = b.start("127.0.0.1")!!
        a.connect(eb); b.connect(ea) // at the same moment
        waitFor { "bbbb" in a.linked.value && "aaaa" in b.linked.value }
        // Still linked a few seconds later (no tearing down each other's connection), and it carries commands.
        Thread.sleep(3_000)
        assertTrue("bbbb" in a.linked.value && "aaaa" in b.linked.value)
        assertTrue(a.sendCommand("bbbb", RemoteCommand("playPause")))
        listOf(a, b).forEach { it.stop() }
    }

    @Test fun aRemovedDeviceCantLink() = runBlocking {
        var removed = false
        val tv = LanLink(scope, key, "tv-1111", onRecords = {}, onPresence = {}, allowed = { !(removed && it == "phone-2222") })
        val phone = LanLink(scope, key, "phone-2222", onRecords = {}, onPresence = {})
        val ep = tv.start("127.0.0.1")!!
        phone.start("127.0.0.1"); phone.connect(ep)
        waitFor { "phone-2222" in tv.linked.value }
        removed = true; tv.prune()
        waitFor { "phone-2222" !in tv.linked.value }
        phone.connect(ep); Thread.sleep(1_000)
        assertFalse("phone-2222" in tv.linked.value)
        listOf(tv, phone).forEach { it.stop() }
    }

    /** Remote presses over the link: queued without blocking the caller, delivered in order, a few ms each on loopback. */
    @Test fun remotePressesAreFastAndInOrder() = runBlocking {
        val got = Channel<Pair<Long, Int>>(Channel.UNLIMITED)
        val tv = LanLink(scope, key, "tv-1111", onRecords = {}, onPresence = {}, onCommand = { _, c -> got.trySend(System.nanoTime() to c.key!!) })
        val phone = LanLink(scope, key, "phone-2222", onRecords = {}, onPresence = {})
        val ep = tv.start("127.0.0.1")!!
        phone.start("127.0.0.1"); phone.connect(ep)
        waitFor { "tv-1111" in phone.linked.value && "phone-2222" in tv.linked.value }
        val sendCost = ArrayList<Long>(); val latency = ArrayList<Long>()
        repeat(200) { i ->
            val t0 = System.nanoTime()
            assertTrue(phone.sendCommand("tv-1111", RemoteCommand("key", key = i)))
            sendCost += System.nanoTime() - t0
            val (at, k) = withTimeout(2000) { got.receive() }
            assertEquals(i, k)
            latency += at - t0
            Thread.sleep(5) // a fast thumb, not a flood
        }
        fun p(list: List<Long>, q: Double) = list.sorted()[((list.size - 1) * q).toInt()] / 1_000_000.0
        println("LAN remote: send call p50 %.3f ms p99 %.3f ms; delivered p50 %.2f ms p99 %.2f ms".format(p(sendCost, .5), p(sendCost, .99), p(latency, .5), p(latency, .99)))
        assertTrue("sending must not block the caller", p(sendCost, .99) < 5.0)
        assertTrue("delivery p99 under 20 ms on loopback", p(latency, .99) < 20.0)
        // The round-trip probe reports a time.
        phone.probe("tv-1111")
        waitFor { phone.rtt.value["tv-1111"] != null }
        listOf(tv, phone).forEach { it.stop() }
    }

    @Test fun commandsPresenceAndRecovery() = runBlocking {
        val commands = Channel<Pair<String, RemoteCommand>>(Channel.UNLIMITED)
        val seen = Channel<LanLink.Presence>(Channel.UNLIMITED)
        val tv = LanLink(scope, key, "tv-1111", onRecords = {}, onPresence = {}, onCommand = { f, c -> commands.trySend(f to c) })
        val phone = LanLink(scope, key, "phone-2222", onRecords = {}, onPresence = { seen.trySend(it) })
        val stranger = LanLink(scope, AccountCrypto.newAccountKey(), "evil-3333", onRecords = {}, onPresence = {})
        val ep = tv.start("127.0.0.1")!!
        phone.start("127.0.0.1"); stranger.start("127.0.0.1")
        phone.connect(ep); stranger.connect(ep)
        waitFor { "tv-1111" in phone.linked.value && "phone-2222" in tv.linked.value }
        assertFalse("evil-3333" in tv.linked.value)

        // A remote press and a cast reach the TV, marked as from the phone.
        assertTrue(phone.sendCommand("tv-1111", RemoteCommand("key", key = 19)))
        assertTrue(phone.sendCommand("tv-1111", RemoteCommand("cast", cast = CastItem("movie", "pl1", "m9", positionMs = 600_000))))
        val first = withTimeout(2000) { commands.receive() }
        assertEquals("phone-2222" to 19, first.first to first.second.key)
        assertEquals(600_000L, withTimeout(2000) { commands.receive() }.second.cast?.positionMs)
        // The stranger isn't linked, so it can't send anything to the TV.
        assertFalse(stranger.sendCommand("tv-1111", RemoteCommand("key", key = 19)))

        // What the TV is playing reaches the phone.
        tv.sendPresence(LanLink.Presence("tv-1111", "Living Room", "Heat", kind = "movie", positionMs = 1000, durationMs = 9000, playing = true, deviceKind = "tv"))
        assertEquals("Heat", withTimeout(2000) { seen.receive() }.title)

        // The Wi-Fi drops: both sides notice, and the phone dials again on its own (every 10 s) without being told.
        tv.dropLinksForTest()
        waitFor { "tv-1111" !in phone.linked.value }
        waitFor(15_000) { "tv-1111" in phone.linked.value && "phone-2222" in tv.linked.value }
        assertTrue(phone.sendCommand("tv-1111", RemoteCommand("playPause")))
        assertEquals("playPause", withTimeout(2000) { commands.receive() }.second.type)
        listOf(tv, phone, stranger).forEach { it.stop() }
    }
}
