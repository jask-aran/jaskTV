package app.opentv.account.sync

import android.util.Base64
import android.util.Log
import app.opentv.account.AccountCrypto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One kind of synced data, as records "key → value" (null value: removed). The engine finds what changed by
 * comparing [snapshot] with the replica, so nothing in the app has to report its own changes.
 */
interface SyncCollection {
    /** Short name, part of every record's identity: "wp" (resume points), "fv" (favourites)… */
    val name: String
    /** This device's current state: only things it can name portably (a playlist it knows, a channel it has). */
    suspend fun snapshot(): Map<String, JsonElement>
    /** Puts another device's value in place (null: remove it). False: can't yet (what it names hasn't loaded here). */
    suspend fun apply(key: String, value: JsonElement?): Boolean
    /**
     * A key the replica holds but [snapshot] doesn't: was it really removed here? Only when what it names still
     * exists here and the mark is gone; an item that just isn't loaded (or was dropped by the provider) is not a removal.
     */
    suspend fun removedHere(key: String): Boolean
    /** When a value was really made (a resume point's watch time), to stamp it with; null: now. */
    fun madeAt(value: JsonElement): Long? = null
}

/** A record on the wire: opaque id, stamp, sealed data (and the server's sequence number once held). */
@Serializable data class WireRecord(val id: String, val stamp: String, val data: String, val seq: Long = 0)
@Serializable data class Accepted(val id: String, val seq: Long)
@Serializable data class Rejected(val id: String, val reason: String)
@Serializable data class PutResult(val seq: Long, val accepted: List<Accepted>, val stale: List<WireRecord>, val rejected: List<Rejected> = emptyList(), val now: Long)
@Serializable data class PullPage(val seq: Long, val records: List<WireRecord>, val more: Boolean, val now: Long)

/** How records reach the server: over the live socket when it's open, else HTTP. */
interface RecordTransport {
    suspend fun put(records: List<WireRecord>): PutResult
    suspend fun pull(since: Long, limit: Int): PullPage
}

/**
 * The sync engine (protocol v2). Every fact is its own record (one resume point, one favourite, one setting),
 * sealed with the account key; the server keeps the newest stamp per record and pushes changes to the other
 * devices at once. So:
 *  - only what changed is sent, a few hundred bytes per change;
 *  - concurrent edits can't race: the newest stamp wins, the same on every device and on the server;
 *  - removals are records too (tombstones), so they reach every device and never come back;
 *  - a device that can't apply something yet (a resume point for a movie it hasn't loaded) keeps it and retries.
 *
 * A device joining an account that already has records takes them first; what only it has goes up with seed
 * stamps that any real edit beats.
 */
class SyncEngine(
    private val store: SyncStore,
    val clock: StampClock,
    private val key: ByteArray,
    private val collections: List<SyncCollection>,
    private val transport: RecordTransport,
    /** Records about to go to the server, also sent straight to devices on the same network. */
    private val alsoSend: (List<WireRecord>) -> Unit = {},
    /**
     * Whether this device may be the first to fill an empty account. A device with nothing of its own (a fresh TV,
     * no playlists) mustn't: its defaults would become the account's newest values and beat the phone's real
     * settings when the phone syncs. It waits until another device fills the account, or it gets playlists.
     */
    private val mayStartAccount: suspend () -> Boolean = { true },
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val byName = collections.associateBy { it.name }
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    @Serializable private data class Sealed(val c: String, val k: String, val s: String, val v: JsonElement? = null)

    /** A record's id: an HMAC of collection and key, so the server learns nothing from it. */
    fun idOf(coll: String, key: String): String = synchronized(mac) {
        Base64.encodeToString(mac.doFinal("$coll\u0000$key".toByteArray()).copyOf(18), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** A full pass: catch up, take in this device's changes, send them, retry what couldn't be applied. */
    suspend fun sync(only: Set<String>? = null) = lock.withLock {
        catchUpLocked()
        retryPendingLocked()
        if (store.meta("seeded") == null) seedLocked() else captureLocked(only)
        flushLocked()
    }

    /** Whether this device has taken part yet (first full pass done): until then, local changes wait for [sync]. */
    fun seeded() = store.meta("seeded") != null

    /** Takes in local changes for some collections and sends them (after an edit here). Before the first full pass,
     *  nothing: capturing then would stamp a fresh device's defaults as new edits. */
    suspend fun push(only: Set<String>) = lock.withLock { if (seeded()) { captureLocked(only); flushLocked() } }

    /** Takes in local changes and sends them to nearby devices only (the server gets them on the next push). */
    suspend fun shareNearby(only: Set<String>) = lock.withLock {
        if (!seeded()) return@withLock
        captureLocked(only)
        alsoSend(store.withState(SyncStore.UNSENT).filter { it.coll in only }.map(::wire))
    }

    /** Records another device wrote, pushed by the server or a direct peer. */
    suspend fun receive(records: List<WireRecord>) = lock.withLock { receiveLocked(records) }

    /** Unapplied records, tried again (after a catalogue load). */
    suspend fun retryPending() = lock.withLock { retryPendingLocked() }

    fun pendingCount() = store.withState(SyncStore.PENDING).size
    fun unsentCount() = store.withState(SyncStore.UNSENT).size

    // ---- Inside the lock ----

    private suspend fun catchUpLocked() {
        var since = store.meta("seq")?.toLong() ?: 0
        while (true) {
            val page = transport.pull(since, 1000)
            clock.observeServer(page.now)
            receiveLocked(page.records)
            // A page holds every record after [since], so the whole range is now known.
            since = maxOf(since, page.records.maxOfOrNull { it.seq } ?: since)
            store.setMeta("seq", maxOf(since, store.meta("seq")?.toLong() ?: 0).toString())
            if (!page.more || page.records.isEmpty()) break
        }
    }

    /**
     * Moves the catch-up point past [seqs] only while they continue it without a gap: a push can arrive before an
     * earlier record it missed (while reconnecting), and that one must still come in the next catch-up.
     */
    private fun advance(seqs: List<Long>) {
        var cur = store.meta("seq")?.toLong() ?: 0
        for (q in seqs.filter { it > 0 }.sorted()) { if (q == cur + 1) cur = q else if (q > cur) break }
        store.setMeta("seq", cur.toString())
    }

    private suspend fun receiveLocked(records: List<WireRecord>) {
        for (w in records) {
            val held = store.byId(w.id)
            if (held != null && held.stamp >= w.stamp) {
                if (held.stamp == w.stamp && held.state == SyncStore.UNSENT && w.seq > 0) store.acked(w.id, w.stamp, w.seq)
                continue
            }
            val sealed = runCatching { json.decodeFromString(Sealed.serializer(), AccountCrypto.open(key, "r2", w.id, w.data)) }
                .onFailure { Log.w(TAG, "Unreadable record ${w.id}", it) }.getOrNull() ?: continue
            // The stamp is inside the sealed data: the server can't re-stamp a record to make it win.
            if (sealed.s != w.stamp || idOf(sealed.c, sealed.k) != w.id) { Log.w(TAG, "Record ${w.id} doesn't match its seal"); continue }
            val value = sealed.v?.toString()
            val coll = byName[sealed.c]
            val applied = coll == null || runCatching { coll.apply(sealed.k, sealed.v) }.getOrElse { Log.w(TAG, "Apply ${sealed.c}/${sealed.k} failed", it); false }
            store.put(SyncStore.Rec(w.id, sealed.c, sealed.k, w.stamp, value, w.seq, if (applied) SyncStore.SYNCED else SyncStore.PENDING))
        }
        advance(records.map { it.seq })
    }

    private suspend fun retryPendingLocked() {
        for (r in store.withState(SyncStore.PENDING)) {
            val coll = byName[r.coll] ?: continue
            val ok = runCatching { coll.apply(r.key, r.value?.let { json.parseToJsonElement(it) }) }.getOrDefault(false)
            if (ok) store.setState(r.id, SyncStore.SYNCED)
        }
    }

    /** First pass on this device: remote first; what only this device has goes up, seed-stamped when joining. */
    private suspend fun seedLocked() {
        val joining = store.count() > 0
        if (!joining && !mayStartAccount()) return
        for (coll in collections) {
            val local = runCatching { coll.snapshot() }.onFailure { Log.w(TAG, "Snapshot ${coll.name} failed", it) }.getOrNull() ?: continue
            for ((k, v) in local) {
                val held = store.byKey(coll.name, k)
                // Values with a real time (a resume point) compete by that time; anything else from a joining device
                // loses to the account's (its defaults mustn't override settings made elsewhere).
                val made = coll.madeAt(v)
                when {
                    held == null -> write(coll.name, k, v, if (joining && made == null) clock.seed() else stampFor(coll, v))
                    held.state == SyncStore.PENDING || held.value == v.toString() -> Unit
                    made != null && made + clock.offset > StampClock.timeOf(held.stamp) -> write(coll.name, k, v, stampFor(coll, v))
                    else -> runCatching { coll.apply(k, held.value?.let { json.parseToJsonElement(it) }) }
                }
            }
        }
        store.setMeta("seeded", "1")
    }

    private suspend fun captureLocked(only: Set<String>?) {
        for (coll in collections) {
            if (only != null && coll.name !in only) continue
            val local = runCatching { coll.snapshot() }.onFailure { Log.w(TAG, "Snapshot ${coll.name} failed", it) }.getOrNull() ?: continue
            for ((k, v) in local) {
                val held = store.byKey(coll.name, k)
                if (held == null) { write(coll.name, k, v, stampFor(coll, v)); continue }
                if (held.state == SyncStore.PENDING || held.value == v.toString()) continue
                // Changed here. A value made before the one held (an older resume point) loses: put the held one back.
                val made = coll.madeAt(v)
                if (made != null && made + clock.offset < StampClock.timeOf(held.stamp)) {
                    runCatching { coll.apply(k, held.value?.let { json.parseToJsonElement(it) }) }; continue
                }
                write(coll.name, k, v, stampFor(coll, v))
            }
            for (held in store.inColl(coll.name)) {
                if (held.value == null || held.state == SyncStore.PENDING || held.key in local) continue
                if (runCatching { coll.removedHere(held.key) }.getOrDefault(false)) write(coll.name, held.key, null, clock.next())
            }
        }
    }

    private fun stampFor(coll: SyncCollection, v: JsonElement): String = coll.madeAt(v)?.let { clock.next(it + clock.offset) } ?: clock.next()

    private fun write(coll: String, k: String, v: JsonElement?, stamp: String) {
        val id = idOf(coll, k)
        val held = store.byId(id)
        // Never stamp below what's held: the newest-wins order must hold for this device's own edits too.
        val s = if (held != null && held.stamp >= stamp) clock.next() else stamp
        store.put(SyncStore.Rec(id, coll, k, s, v?.toString(), held?.seq ?: 0, SyncStore.UNSENT))
    }

    private fun wire(r: SyncStore.Rec): WireRecord {
        val plain = json.encodeToString(Sealed.serializer(), Sealed(r.coll, r.key, r.stamp, r.value?.let { json.parseToJsonElement(it) }))
        return WireRecord(r.id, r.stamp, AccountCrypto.seal(key, "r2", r.id, plain))
    }

    private suspend fun flushLocked() {
        val unsent = store.withState(SyncStore.UNSENT)
        for (batch in unsent.chunked(500)) {
            val wire = batch.map(::wire)
            alsoSend(wire)
            val result = transport.put(wire)
            clock.observeServer(result.now)
            val stamps = batch.associate { it.id to it.stamp }
            for (a in result.accepted) stamps[a.id]?.let { store.acked(a.id, it, a.seq) }
            advance(result.accepted.map { it.seq })
            // Someone wrote a newer value first: take it (it replaces ours here).
            receiveLocked(result.stale)
            for (r in result.rejected) {
                Log.w(TAG, "Server refused ${r.id}: ${r.reason}")
                // A stamp from a clock that was ahead: restamp with the corrected clock and try again next time.
                if (r.reason.contains("future")) store.byId(r.id)?.let { store.put(it.copy(stamp = clock.next())) }
            }
        }
    }

    companion object { private const val TAG = "jaskSync" }
}
