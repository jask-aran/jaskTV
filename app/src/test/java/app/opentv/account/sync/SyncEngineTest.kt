package app.opentv.account.sync

import app.opentv.account.AccountCrypto
import app.opentv.account.HttpAccountServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * The sync engine across several devices: random edit and delivery orders, duplicates, devices going offline,
 * catalogues not loaded yet, a fresh device joining, a clock that's wrong. The server here follows the same rule as
 * the real one (server/src/account.ts putRecords): keep the newest stamp per record, number every accepted write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncEngineTest {
    /** The server's rule, in memory. */
    class FakeServer(val now: () -> Long = System::currentTimeMillis) {
        val records = LinkedHashMap<String, WireRecord>()
        var seq = 0L
        val listeners = mutableListOf<Pair<String, (List<WireRecord>) -> Unit>>()
        var online = true
        fun transport(device: String) = object : RecordTransport {
            override suspend fun put(records: List<WireRecord>): PutResult {
                check(online) { "offline" }
                val acc = ArrayList<Accepted>(); val stale = ArrayList<WireRecord>(); val rej = ArrayList<Rejected>(); val pushed = ArrayList<WireRecord>()
                for (r in records) {
                    if (StampClock.timeOf(r.stamp) > now() + 5 * 60_000) { rej += Rejected(r.id, "Stamp is in the future (check the device clock)"); continue }
                    val cur = this@FakeServer.records[r.id]
                    if (cur != null && cur.stamp >= r.stamp) { if (cur.stamp != r.stamp) stale += cur else acc += Accepted(r.id, cur.seq); continue }
                    val held = r.copy(seq = ++seq)
                    this@FakeServer.records.remove(r.id); this@FakeServer.records[r.id] = held
                    acc += Accepted(r.id, held.seq); pushed += held
                }
                if (pushed.isNotEmpty()) pending += device to pushed
                return PutResult(seq, acc, stale, rej, now())
            }
            override suspend fun pull(since: Long, limit: Int): PullPage {
                check(online) { "offline" }
                val after = this@FakeServer.records.values.filter { it.seq > since }.sortedBy { it.seq }
                return PullPage(seq, after.take(limit), after.size > limit, now())
            }
        }
        /** Live pushes not yet delivered (delivered in a random order by the test, sometimes twice, sometimes never). */
        val pending = ArrayList<Pair<String, List<WireRecord>>>()
    }

    /**
     * A device's data for one collection: [data] is what it holds; [loaded] is which items its catalogue has
     * (a favourite on an item it hasn't loaded can't be applied, and a missing one there isn't a removal).
     */
    class Device(val name: String, server: FakeServer, key: ByteArray, var clockSkew: Long = 0, dbName: String = "sync-$name-${System.nanoTime()}.db",
        mayStart: Boolean = true) {
        val data = HashMap<String, String>()
        val loaded = HashSet<String>()
        val store = SyncStore(RuntimeEnvironment.getApplication(), dbName)
        val coll = object : SyncCollection {
            override val name = "fv"
            override suspend fun snapshot(): Map<String, JsonElement> = data.filterKeys { it in loaded }.mapValues { JsonPrimitive(it.value) }
            override suspend fun apply(key: String, value: JsonElement?): Boolean {
                if (key !in loaded) return false
                if (value == null) data.remove(key) else data[key] = value.toString().trim('"')
                return true
            }
            override suspend fun removedHere(key: String) = key in loaded && key !in data
        }
        val clock = StampClock(name.padEnd(8, '0').take(8)) { System.currentTimeMillis() + clockSkew }
        val engine = SyncEngine(store, clock, key, listOf(coll), server.transport(name), mayStartAccount = { mayStart })
        fun visible() = data.filterKeys { it in loaded }
    }

    private val key = AccountCrypto.newAccountKey()

    @Test fun randomInterleavingsConverge() = runBlocking {
        repeat(25) { round ->
            val rnd = Random(round)
            val server = FakeServer()
            val devices = List(3) { Device("dev$it$round", server, key) }
            val items = List(6) { "item$it" }
            devices.forEach { it.loaded += items }
            val removed = HashMap<String, Long>() // item → when it was last removed (by anyone)
            repeat(60) {
                val d = devices[rnd.nextInt(devices.size)]
                when (rnd.nextInt(6)) {
                    0, 1 -> d.data[items[rnd.nextInt(items.size)]] = "v" + rnd.nextInt(1000)
                    2 -> d.data.remove(items[rnd.nextInt(items.size)])
                    3 -> runCatching { d.engine.sync() }
                    4 -> { // deliver a random live push, maybe twice
                        if (server.pending.isNotEmpty()) {
                            val (from, recs) = server.pending.removeAt(rnd.nextInt(server.pending.size))
                            devices.filter { it.name != from }.forEach { t -> t.engine.receive(recs); if (rnd.nextBoolean()) t.engine.receive(recs) }
                        }
                    }
                    5 -> server.online = !server.online
                }
                Thread.sleep(1) // stamps need time to move
            }
            server.online = true
            repeat(3) { devices.forEach { it.engine.sync() } }
            val first = devices.first().visible()
            devices.forEach { assertEquals("round $round: ${it.name} agrees", first, it.visible()) }
            // …and with the server's newest record for every item (a removal is a record with no value).
            val serverView = items.mapNotNull { item ->
                val rec = server.records[devices[0].engine.idOf("fv", item)] ?: return@mapNotNull null
                val v = kotlinx.serialization.json.Json.parseToJsonElement(AccountCrypto.open(key, "r2", rec.id, rec.data))
                    .let { it as kotlinx.serialization.json.JsonObject }["v"]?.toString()?.trim('"') ?: return@mapNotNull null
                item to v
            }.toMap()
            assertEquals("round $round: matches the server", serverView, first)
            assertTrue(devices.all { it.engine.unsentCount() == 0 })
        }
    }

    @Test fun removalReachesEveryDeviceAndStaysRemoved() = runBlocking {
        val server = FakeServer()
        val a = Device("phoneaaa", server, key); val b = Device("tvbbbbbb", server, key)
        listOf(a, b).forEach { it.loaded += "m1" }
        a.data["m1"] = "fav"; a.engine.sync(); b.engine.sync()
        assertEquals("fav", b.data["m1"])
        Thread.sleep(2)
        a.data.remove("m1"); a.engine.sync()
        b.engine.sync()
        assertNull(b.data["m1"])
        // b syncs again and again: the removal doesn't come back from it.
        repeat(3) { b.engine.sync(); a.engine.sync() }
        assertNull(a.data["m1"]); assertNull(b.data["m1"])
    }

    @Test fun anUnloadedCatalogueNeverRemovesAnything() = runBlocking {
        val server = FakeServer()
        val phone = Device("phoneaaa", server, key); val tv = Device("tvbbbbbb", server, key)
        phone.loaded += setOf("m1", "m2"); phone.data["m1"] = "fav"; phone.data["m2"] = "fav"
        phone.engine.sync()
        tv.engine.sync() // the TV hasn't loaded its catalogue: nothing applies yet, and nothing is removed
        assertTrue(tv.data.isEmpty())
        assertEquals(2, tv.engine.pendingCount())
        phone.engine.sync()
        assertEquals(setOf("m1", "m2"), phone.data.keys)
        tv.loaded += setOf("m1", "m2"); tv.engine.retryPending()
        assertEquals(setOf("m1", "m2"), tv.data.keys)
        assertEquals(0, tv.engine.pendingCount())
    }

    @Test fun aFreshDeviceTakesTheAccountsValuesNotItsDefaults() = runBlocking {
        val server = FakeServer()
        val phone = Device("phoneaaa", server, key); val tv = Device("tvbbbbbb", server, key)
        phone.loaded += setOf("theme", "only-phone"); tv.loaded += setOf("theme", "only-tv", "only-phone")
        phone.data["theme"] = "DARK"; phone.data["only-phone"] = "x"
        phone.engine.sync()
        Thread.sleep(5)
        tv.data["theme"] = "SYSTEM"; tv.data["only-tv"] = "y" // its defaults and its own data, set after the phone's
        tv.engine.sync()
        assertEquals("DARK", tv.data["theme"])  // the account's value wins over a joining device's
        assertEquals("y", tv.data["only-tv"])   // and what only the TV has goes up
        phone.loaded += "only-tv"; phone.engine.sync()
        assertEquals("y", phone.data["only-tv"]); assertEquals("DARK", phone.data["theme"])
        // A real edit on the TV afterwards wins as usual.
        Thread.sleep(2); tv.data["theme"] = "LIGHT"; tv.engine.sync(); phone.engine.sync()
        assertEquals("LIGHT", phone.data["theme"])
    }

    @Test fun aFailedFirstSyncThenALocalEditDoesNotOverwriteTheAccount() = runBlocking {
        val server = FakeServer()
        val phone = Device("phoneaaa", server, key); val tv = Device("tvbbbbbb", server, key)
        phone.loaded += setOf("tmdbKey", "theme"); tv.loaded += setOf("tmdbKey", "theme")
        phone.data["tmdbKey"] = "real-key"; phone.data["theme"] = "DARK"; phone.engine.sync()
        tv.data["tmdbKey"] = ""; tv.data["theme"] = "SYSTEM" // a fresh TV's defaults
        server.online = false
        runCatching { tv.engine.sync() } // first pass fails: offline
        server.online = true
        Thread.sleep(5); tv.data["theme"] = "LIGHT" // the user changes one setting before the TV ever synced
        tv.engine.push(setOf("fv"))           // must not send the TV's defaults as new edits
        assertTrue(server.records.size == 2)
        tv.engine.sync(); phone.engine.sync()
        assertEquals("real-key", tv.data["tmdbKey"]); assertEquals("real-key", phone.data["tmdbKey"])
    }

    @Test fun aFreshTvSigningInBeforeThePhoneWaitsForIt() = runBlocking {
        val server = FakeServer()
        val tv = Device("tvbbbbbb", server, key, mayStart = false); val phone = Device("phoneaaa", server, key)
        tv.loaded += setOf("tmdbKey"); phone.loaded += setOf("tmdbKey")
        tv.data["tmdbKey"] = ""; tv.engine.sync() // empty account, and the TV has nothing of its own: sends nothing
        assertTrue(server.records.isEmpty())
        Thread.sleep(5); phone.data["tmdbKey"] = "real-key"; phone.engine.sync()
        tv.engine.sync()
        assertEquals("real-key", tv.data["tmdbKey"])
        phone.engine.sync(); assertEquals("real-key", phone.data["tmdbKey"])
    }

    @Test fun recordsCrossTheLanDirectly() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val got = kotlinx.coroutines.CompletableDeferred<List<WireRecord>>()
        val presence = kotlinx.coroutines.CompletableDeferred<LanLink.Presence>()
        val tv = LanLink(scope, key, "bbbb-tv", onRecords = { got.complete(it) }, onPresence = { presence.complete(it) })
        val phone = LanLink(scope, key, "aaaa-phone", onRecords = {}, onPresence = {})
        val stranger = LanLink(scope, AccountCrypto.newAccountKey(), "0000-other", onRecords = { error("must not read") }, onPresence = {})
        val ep = tv.start("127.0.0.1")!!
        stranger.connect(ep) // another account on the same network: its frames don't open with this key
        phone.connect(ep)
        val deadline = System.currentTimeMillis() + 3000
        while (phone.connected().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(100)
        phone.sendRecords(listOf(WireRecord("rec0000000000000001", "0000000000001-0000-phoneaaa", "sealed")))
        phone.sendPresence(LanLink.Presence("aaaa-phone", "Phone", "Heat", null, "movie"))
        assertEquals("rec0000000000000001", kotlinx.coroutines.withTimeout(3000) { got.await() }.single().id)
        assertEquals("Heat", kotlinx.coroutines.withTimeout(3000) { presence.await() }.title)
        assertFalse("0000-other" in tv.connected())
        listOf(tv, phone, stranger).forEach { it.stop() }
    }

    @Test fun aClockAheadDoesNotWinForever() = runBlocking {
        val server = FakeServer()
        val fast = Device("fastaaaa", server, key, clockSkew = 3_600_000); val ok = Device("okbbbbbb", server, key)
        listOf(fast, ok).forEach { it.loaded += "s" }
        // The fast clock learns the server's time on its first contact, then stamps with it.
        fast.engine.sync()
        fast.data["s"] = "from-fast"; fast.engine.sync()
        assertTrue(server.records.isNotEmpty())
        ok.engine.sync(); assertEquals("from-fast", ok.data["s"])
        Thread.sleep(5); ok.data["s"] = "later"; ok.engine.sync(); fast.engine.sync()
        assertEquals("later", fast.data["s"])
    }

    @Test fun concurrentEditsAgreeOnOneWinner() = runBlocking {
        val server = FakeServer()
        val a = Device("aaaaaaaa", server, key); val b = Device("bbbbbbbb", server, key)
        listOf(a, b).forEach { it.loaded += "x"; it.engine.sync() }
        a.data["x"] = "A"; Thread.sleep(2); b.data["x"] = "B" // b's edit is later…
        b.engine.sync(); a.engine.sync() // …and wins even though a sent second
        assertEquals("B", a.data["x"]); b.engine.sync(); assertEquals("B", b.data["x"])
    }

    /** Two engines through the real server code (`wrangler dev`, DEV_AUTH=1). Skipped when it isn't running. */
    @Test fun twoDevicesThroughTheLocalServer() = runBlocking {
        val base = "http://127.0.0.1:8787"
        assumeTrue(runCatching { (java.net.URL("$base/v1/health").openConnection() as java.net.HttpURLConnection).run { connectTimeout = 1000; readTimeout = 1000; inputStream.bufferedReader().readText() }.contains("local") }.getOrDefault(false))
        val api = HttpAccountServer(base)
        val sub = "v2-" + System.nanoTime()
        val phone = api.signInWithGoogle("dev:$sub:tester@example.com", "Phone", "phone")
        val tv = api.signInWithGoogle("dev:$sub:tester@example.com", "TV", "tv")
        fun device(name: String, token: String) = Device(name, FakeServer(), key).let { d ->
            object { val dev = d; val engine = SyncEngine(d.store, d.clock, key, listOf(d.coll), api.records(token)) }
        }
        val p = device("phoneaaa", phone.token); val t = device("tvbbbbbb", tv.token)
        p.dev.loaded += setOf("a", "b"); t.dev.loaded += setOf("a", "b")
        p.dev.data["a"] = "1"; p.dev.data["b"] = "2"; p.engine.sync()
        t.engine.sync(); assertEquals(mapOf("a" to "1", "b" to "2"), t.dev.data)
        Thread.sleep(2); t.dev.data.remove("a"); t.dev.data["b"] = "3"; t.engine.sync()
        p.engine.sync(); assertEquals(mapOf("b" to "3"), p.dev.data)
        // Nothing left to send on either side, and a further pass sends nothing.
        assertEquals(0, p.engine.unsentCount()); assertEquals(0, t.engine.unsentCount())
        assertFalse(p.dev.data.containsKey("a"))
    }
}
