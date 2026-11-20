package app.opentv.account

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Every jasktv-core this account has, as this device knows them. Each core writes a record to the account (sync
 * collection "core", keyed by core's device id) with its addresses, port, role, capabilities and access secret; this
 * keeps those records, probes them all at once and keeps every core that answers, so nothing has to be typed and a
 * newly signed-in device starts using them straight away.
 *
 * A core's address on the home network also comes from network discovery ([seen]: cores announce themselves like a
 * device), so a core whose address changed is still found. Order of preference: a full core (the home server) before
 * a helper, and for each core its home-network address before its https one.
 *
 * Everything that uses core falls back to doing the work here when no core with the capability answers.
 */
object CoreDirectory {
    @Serializable data class Record(
        val name: String = "jasktv-core", val version: String = "", val addresses: List<String> = emptyList(),
        val port: Int = 7000, val secret: String = "", val capabilities: List<String> = emptyList(),
        /** "full" (the home core) or "helper" (matching and lists only, e.g. on a VPS for when the home core is off). */
        val role: String = "full",
        /** Full addresses that reach core from anywhere ("https://…"), tried after [addresses]. */
        val urls: List<String> = emptyList(),
    )
    /** A core that answered: its record id, base address ("http://192.168.1.20:7000") and the secret to send. */
    data class Endpoint(val id: String, val base: String, val secret: String, val name: String, val capabilities: List<String>,
                        val role: String = "full") {
        /** Its Stremio add-on (torrent and other copies of a title, played through its TorrServer). */
        val addonManifest: String get() = "$base/s/$secret/manifest.json"
    }

    private const val TAG = "jaskCore"
    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: android.content.SharedPreferences? = null
    private var copies: android.content.SharedPreferences? = null
    private val records = java.util.concurrent.ConcurrentHashMap<String, Record>()
    /** Home-network addresses found by discovery, by core id (newest first, a few kept). */
    private val lanSeen = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var checkedAt = 0L
    private val probing = kotlinx.coroutines.sync.Mutex()
    private val _available = MutableStateFlow<List<Endpoint>>(emptyList())
    /** The cores answering right now, best first. Changes as cores come and go (rechecked at most once a minute). */
    val available: StateFlow<List<Endpoint>> = _available
    private val _checked = MutableStateFlow(false)
    /** Whether the cores have been checked since launch (until then, "none available" isn't known yet). */
    val checked: StateFlow<Boolean> = _checked
    @Volatile private var retries = 0
    /** Short timeouts: a core that's off or away must cost a moment, not a stalled screen. */
    private val probe = OkHttpClient.Builder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(3, TimeUnit.SECONDS).build()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("jask_core", Context.MODE_PRIVATE)
        prefs = p
        copies = context.applicationContext.getSharedPreferences("jask_core_lists", Context.MODE_PRIVATE)
        for ((id, text) in p.all) runCatching { records[id] = json.decodeFromString(Record.serializer(), text as String) }
        recheck()
        // Joining or leaving a network (home Wi-Fi, mobile data) changes which cores answer: check again once it settles.
        runCatching {
            val cm = context.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = soon()
                override fun onLost(network: android.net.Network) = soon()
            })
        }
    }

    private var pending: kotlinx.coroutines.Job? = null
    /** A recheck in a second or so (a network change comes as several callbacks; one check covers them). */
    private fun soon() {
        pending?.cancel()
        pending = scope.launch { kotlinx.coroutines.delay(1_200); retries = 0; recheck() }
    }

    /** The app came to the front: recheck if the last check is more than 15 s old (the phone may have moved or slept). */
    fun foreground() { if (System.currentTimeMillis() - checkedAt > 15_000) recheck() }

    /** From sync: another core record, or one removed (null). Probed straight away. */
    fun put(id: String, value: JsonElement?) {
        val r = value?.let { runCatching { json.decodeFromJsonElement(Record.serializer(), it) }.getOrNull() }
        val before = records[id]
        if (r == null) { records.remove(id); prefs?.edit()?.remove(id)?.apply() }
        else { records[id] = r; prefs?.edit()?.putString(id, json.encodeToString(Record.serializer(), r))?.apply() }
        if (r != before) recheck()
    }

    /** Network discovery found device [id] at [host]: if it's a core, that's its current home-network address. */
    fun seen(id: String, host: String) {
        val held = lanSeen[id].orEmpty()
        if (held.firstOrNull() == host) return
        lanSeen[id] = (listOf(host) + held.filter { it != host }).take(3)
        if (records.containsKey(id)) { Log.i(TAG, "${records[id]?.name} is on this network"); recheck() }
    }

    fun known(): Boolean = records.isNotEmpty()

    /** Probes every core now, in the background. */
    fun recheck() { checkedAt = 0; scope.launch { refresh() } }

    /**
     * The best core answering right now, or null; with [capability], the best one that has it. Checked at most once a
     * minute (a miss too, so a core that's off isn't asked on every call). Blocking: call off the main thread.
     */
    fun endpoint(capability: String? = null): Endpoint? {
        if (records.isEmpty()) return null
        if (System.currentTimeMillis() - checkedAt >= 60_000) runBlocking { refresh() }
        return _available.value.firstOrNull { capability == null || capability in it.capabilities }
    }

    private suspend fun refresh() = probing.lock().let {
        try {
            if (System.currentTimeMillis() - checkedAt < 60_000) return@let
            val found = kotlinx.coroutines.coroutineScope {
                records.entries.map { (id, r) -> async(Dispatchers.IO) { reach(id, r) } }.awaitAll()
            }.filterNotNull().sortedBy { if (it.role == "helper") 1 else 0 }
            checkedAt = System.currentTimeMillis()
            _checked.value = true
            // A core seen on this network that didn't answer (often the first request after the phone wakes): try
            // again in a few seconds rather than in a minute, a few times.
            // Likewise when no full core answers at all (a home core is often a moment away after a wake): 5, 10, 15 s…
            val missing = lanSeen.keys.any { id -> records.containsKey(id) && found.none { it.id == id } } ||
                (records.values.any { it.role != "helper" } && found.none { it.role != "helper" })
            if (missing && retries < 5) { retries++; val wait = 5_000L * retries; scope.launch { kotlinx.coroutines.delay(wait); recheck() } }
            if (!missing) retries = 0
            if (found != _available.value) {
                Log.i(TAG, if (found.isEmpty()) "No jasktv-core reachable" else "Using " + found.joinToString { "${it.name} (${it.base.substringBefore("/s/")})" })
                _available.value = found
            }
        } finally { probing.unlock() }
    }

    /** The first of [r]'s addresses that answers as that core: discovered home-network ones, recorded ones, then https. */
    private fun reach(id: String, r: Record): Endpoint? {
        val lan = (lanSeen[id].orEmpty() + r.addresses).distinct().map { a -> "http://${if (':' in a) "[$a]" else a}:${r.port}" }
        // Every address at once (not one after another, each with its own timeout); the best that answers wins.
        val bases = (lan + r.urls.map { it.trimEnd('/') }).distinct()
        val answers = kotlinx.coroutines.runBlocking(Dispatchers.IO) { bases.map { base -> async { base to answers(base, r) } }.awaitAll() }
        return answers.firstOrNull { it.second }?.let { (base, _) -> Endpoint(id, base, r.secret, r.name, r.capabilities, r.role) }
    }

    private fun answers(base: String, r: Record): Boolean {
        return run {
            val ok = runCatching {
                probe.newCall(Request.Builder().url("$base/health").header("Authorization", "Bearer ${r.secret}").build()).execute()
                    .use { it.isSuccessful && it.body?.string()?.contains("\"core\":true") == true }
            }.getOrDefault(false)
            ok
        }
    }

    /** Core's time from this device's last complete copy of a list (0: none), to ask for only what changed since. */
    fun since(key: String): Long = copies?.getLong(key, 0L) ?: 0L
    fun setSince(key: String, at: Long) { copies?.edit()?.putLong(key, at)?.apply() }
    /** Forget every "changes since" cursor for one playlist (key "core|sourceKey|action"), so its next copy is whole. */
    fun forgetCopies(sourceKey: String) {
        val prefs = copies ?: return
        val edit = prefs.edit()
        prefs.all.keys.filter { it.split('|').getOrNull(1) == sourceKey }.forEach(edit::remove)
        edit.apply()
    }

    /** After a failed call: look again (in the background) instead of trusting the last answer for a minute. */
    fun forget() = recheck()
}
