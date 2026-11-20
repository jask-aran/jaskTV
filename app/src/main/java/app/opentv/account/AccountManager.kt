package app.opentv.account

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import app.opentv.BuildConfig
import app.opentv.core.AppSettings
import app.opentv.core.get
import app.opentv.data.local.isLocalPlaylist
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Source
import app.opentv.data.work.SyncWorker
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import app.opentv.account.sync.AppCollections
import app.opentv.account.sync.LanDiscovery
import app.opentv.account.sync.LanLink
import kotlinx.coroutines.flow.first
import app.opentv.account.sync.StampClock
import app.opentv.account.sync.SyncEngine
import app.opentv.account.sync.SyncIds
import app.opentv.account.sync.SyncStore
import app.opentv.account.sync.WireRecord
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The jaskTV account on this device. Signing in is optional: signed out, nothing here runs and the app works as
 * before. Signed in, playlists, preferences and watch history/Library sync (sealed with the account key) whenever they change here,
 * when another device changes them (heard over the live connection), and when the app starts.
 *
 * Setup: the first device to sign in makes the account key and a recovery code, shown once. Every later device
 * signs in with Google, then unlocks with the recovery code (approving from a signed-in device comes with TV pairing).
 */
class AccountManager private constructor(private val context: Context) {
    sealed interface Status {
        /** Signed out. [available]: this build has an account server; [google]: Google sign-in is set up too. */
        data class SignedOut(val available: Boolean, val error: String? = null, val google: Boolean = false) : Status
        data class Working(val message: String) : Status
        /** Signed in on a device that doesn't have the account key yet: needs the recovery code. */
        data class Locked(val email: String, val error: String? = null) : Status
        /** First setup done: the recovery code to save, shown until acknowledged. */
        data class SaveCode(val email: String, val code: String) : Status
        data class Ready(val email: String, val name: String?, val deviceId: String, val devices: List<AccountDevice>,
            val lastSyncAt: Long, val syncing: Boolean, val error: String?) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = AccountStateFile(context)
    private val box = KeystoreBox()
    private val settings = AppSettings.get(context)
    private val prefs = AccountPrefs(context, settings, deviceKind())
    // Under unit tests (Robolectric reports "robolectric" as the build fingerprint) the app never talks to a deployed
    // server: a test that shows the account card can't run up Cloudflare usage. Tests that need a server use a local one.
    private val serverUrl = if (Build.FINGERPRINT == "robolectric") "" else BuildConfig.ACCOUNT_SERVER
    private val server: HttpAccountServer? = serverUrl.takeIf { it.isNotBlank() }?.let { HttpAccountServer(it) }
    /** This build has an account server (sign-in is offered). */
    val available = server != null
    private val google = available && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
    /** The password's unlock key from this session's sign-in, until the account key is set up with it. */
    private var pendingWrap: ByteArray? = null
    private val syncLock = Mutex()
    /**
     * The saved account, if this device can still open its sealed secrets. The Keystore key that sealed them
     * does not travel: a restore from backup onto another device, or a reset of the device's key storage,
     * leaves secrets nothing can open. Opening them then threw on every launch and crashed the app; now
     * this device signs out cleanly and says why (synced data stays on the server).
     */
    private var lostKeys = false
    private var state: AccountState? = file.read()?.takeIf { it.server == serverUrl }?.let { saved ->
        val opened = runCatching { box.open(saved.tokenSealed); saved.keySealed?.let(box::open) }
        val error = opened.exceptionOrNull()
        // Only errors that mean the key really is gone sign this device out for good. A missing key is regenerated by
        // KeystoreBox, so it shows up as a bad tag. Anything else (the keystore not answering yet right after boot)
        // leaves the saved account in place for the next launch, and this run starts signed out.
        val gone = error is javax.crypto.AEADBadTagException || error is java.security.UnrecoverableKeyException ||
            error is android.security.keystore.KeyPermanentlyInvalidatedException || error is IllegalArgumentException
        when {
            error == null -> saved
            gone -> { lostKeys = true; runCatching { file.write(null) }; box.forget(); null }
            else -> { android.util.Log.w("jaskAccount", "secure storage not ready: ${error::class.simpleName}"); null }
        }
    }
    private var devices: List<AccountDevice> = emptyList()
    private var syncing = false
    private var watchers: Job? = null
    private var socket: WebSocket? = null

    private val _status = MutableStateFlow<Status>(Status.SignedOut(available, google = google))
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun token(s: AccountState) = String(box.open(s.tokenSealed))
    private fun key(s: AccountState) = s.keySealed?.let(box::open)

    private fun publish(error: String? = null) {
        val s = state
        _status.value = when {
            s == null -> Status.SignedOut(available, error ?: if (lostKeys) "This device's secure storage was reset, so it was signed out. Sign in again: your synced data is safe on the server." else null, google)
            s.keySealed == null -> Status.Locked(s.email, error)
            else -> Status.Ready(s.email, s.name, s.deviceId, devices, s.lastSyncAt, syncing, error ?: s.lastError)
        }
    }

    /** At app start: if signed in, sync now and keep syncing. */
    fun start() {
        publish()
        if (state?.keySealed != null) begin()
    }

    // ---- Sign in ----

    /** Google sign-in through Android's account picker, then this device joins the account. */
    fun signInWithGoogle(activity: Activity) {
        val api = server ?: return
        scope.launch {
            _status.value = Status.Working("Signing in…")
            val result = runCatching {
                val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
                val credential = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
                }.credential
                require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Unexpected sign-in result" }
                val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                api.signInWithGoogle(idToken, deviceName(), deviceKind())
            }
            result.onFailure { e ->
                Log.w(TAG, "Sign-in failed", e)
                publish(when {
                    e is AccountError && e.code == "not_invited" -> "This Google account hasn't been invited to jaskTV yet."
                    e is androidx.credentials.exceptions.GetCredentialCancellationException -> null
                    else -> "Couldn't sign in: ${e.message}"
                })
            }
            val signed = result.getOrNull() ?: return@launch
            state = AccountState(serverUrl, signed.user.id, signed.user.email, signed.user.name, signed.deviceId, box.seal(signed.token.toByteArray()))
            file.write(state)
            setUpKey()
        }
    }

    /**
     * Email and password. The password never leaves the device: it becomes a login key (sent) and an unlock key
     * (kept), so signing in on a new device also unlocks its synced data. [create]: a new account (invited emails only).
     */
    fun signInWithPassword(email: String, password: String, create: Boolean) {
        val api = server ?: return
        scope.launch {
            _status.value = Status.Working(if (create) "Creating your account…" else "Signing in…")
            val keys = kotlinx.coroutines.withContext(Dispatchers.Default) { AccountCrypto.passwordKeys(email, password) }
            val result = runCatching {
                if (create) api.register(email.trim(), keys.loginKey, deviceName(), deviceKind())
                else api.login(email.trim(), keys.loginKey, deviceName(), deviceKind())
            }
            val signed = result.getOrElse { e ->
                Log.w(TAG, "Password sign-in failed", e)
                publish(if (e is AccountError) e.message else "Couldn't reach the account server: ${e.message}")
                return@launch
            }
            state = AccountState(serverUrl, signed.user.id, signed.user.email, signed.user.name, signed.deviceId, box.seal(signed.token.toByteArray()))
            file.write(state)
            pendingWrap = keys.wrapKey
            setUpKey()
        }
    }

    /**
     * After sign-in: unlock with the password if the vault has a password copy; a new account makes the key and a
     * recovery code; otherwise the recovery code is needed.
     */
    private suspend fun setUpKey() {
        val s = state ?: return
        val api = server ?: return
        val me = runCatching { api.me(token(s)) }.getOrElse { publish("Signed in, but the server didn't answer: ${it.message}"); return }
        devices = me.devices
        val wrap = pendingWrap
        if (me.vault != null) {
            val vault = AccountCrypto.Vault.parse(me.vault)
            val accountKey = if (wrap != null) vault.password?.let { AccountCrypto.unwrapWithKey(it, wrap) } else null
            if (accountKey != null) { unlocked(accountKey); return }
            publish(); return
        }
        val accountKey = AccountCrypto.newAccountKey()
        val code = AccountCrypto.newRecoveryCode()
        val vault = AccountCrypto.Vault(wrap?.let { AccountCrypto.wrapWithKey(accountKey, it) }, AccountCrypto.wrap(accountKey, code))
        val stored = api.putVault(token(s), vault.encode(), replace = false)
        if (!stored.stored) { setUpKey(); return } // Another device set the account up first.
        pendingWrap = null
        state = s.copy(keySealed = box.seal(accountKey))
        file.write(state)
        _status.value = Status.SaveCode(s.email, code)
    }

    private fun unlocked(accountKey: ByteArray) {
        val s = state ?: return
        pendingWrap = null
        state = s.copy(keySealed = box.seal(accountKey))
        file.write(state)
        publish()
        begin()
    }

    fun acknowledgeCode() {
        publish()
        begin()
    }

    /** Unlocks this device with the recovery code. */
    fun unlock(code: String) {
        val s = state ?: return
        val api = server ?: return
        scope.launch {
            _status.value = Status.Working("Unlocking…")
            val text = runCatching { api.me(token(s)).vault }.getOrElse { publish("Couldn't reach the server: ${it.message}"); return@launch }
            if (text == null) { setUpKey(); return@launch }
            val vault = AccountCrypto.Vault.parse(text)
            // PBKDF2 at 210k rounds takes a moment on a TV: off the main thread.
            val accountKey = vault.recovery?.let { AccountCrypto.unwrap(it, code) }
            if (accountKey == null) { _status.value = Status.Locked(s.email, "That recovery code doesn't match."); return@launch }
            // Signed in with a password the vault doesn't know yet: add it, so next time the password alone unlocks.
            pendingWrap?.let { wrap ->
                runCatching { api.putVault(token(s), vault.copy(password = AccountCrypto.wrapWithKey(accountKey, wrap)).encode(), replace = true) }
            }
            unlocked(accountKey)
        }
    }

    /** A new recovery code (the old one stops working). Shown once, like the first. */
    /**
     * Sets a new password from this signed-in device (the old one isn't needed: forgot it is fine). The account key is
     * re-wrapped for the new password; the recovery code's copy stays. [done] gets null on success, else the problem.
     */
    fun changePassword(password: String, done: (String?) -> Unit) {
        val s = state ?: return done("Not signed in")
        val accountKey = key(s) ?: return done("This device is locked")
        val api = server ?: return done("No account server")
        scope.launch {
            val error = runCatching {
                val keys = kotlinx.coroutines.withContext(Dispatchers.Default) { AccountCrypto.passwordKeys(s.email, password) }
                val current = api.me(token(s)).vault?.let(AccountCrypto.Vault::parse)
                api.changePassword(token(s), keys.loginKey, AccountCrypto.Vault(AccountCrypto.wrapWithKey(accountKey, keys.wrapKey), current?.recovery).encode())
            }.exceptionOrNull()?.let { it.message ?: "Couldn't change the password" }
            kotlinx.coroutines.withContext(Dispatchers.Main) { done(error) }
        }
    }

    fun newRecoveryCode() {
        val s = state ?: return
        val accountKey = key(s) ?: return
        val api = server ?: return
        scope.launch {
            val code = AccountCrypto.newRecoveryCode()
            // Keeps the password's copy; only the recovery code's copy changes.
            runCatching {
                val current = api.me(token(s)).vault?.let(AccountCrypto.Vault::parse)
                api.putVault(token(s), AccountCrypto.Vault(current?.password, AccountCrypto.wrap(accountKey, code)).encode(), replace = true)
            }
                .onSuccess { _status.value = Status.SaveCode(s.email, code) }
                .onFailure { publish("Couldn't change the recovery code: ${it.message}") }
        }
    }

    // ---- Sync (protocol v2: one record per fact, see sync/SyncEngine) ----

    private val store by lazy { SyncStore(context) }
    private var engine: SyncEngine? = null
    private var lan: LanLink? = null
    @Volatile private var myNet: String? = null
    private val nearby = MutableStateFlow<Map<String, LanLink.Presence>>(emptyMap())
    /** What each of the account's devices is playing now (by device id), from the live connection or the LAN. */
    val nowPlaying: StateFlow<Map<String, LanLink.Presence>> = nearby.asStateFlow()

    fun syncNow() { scope.launch { sync() } }

    private var coreWatch: kotlinx.coroutines.Job? = null

    /**
     * A core that starts answering (just signed in, back on the home network, the PC switched on) is used at once:
     * its TMDB matches are copied for every provider playlist (only what's new since the last copy from that core).
     * Lists, the guide, posters and other copies of a title go to the best answering core whenever they're needed.
     */
    private fun watchCores(graph: ServiceLocator.Graph) {
        if (coreWatch != null) return
        coreWatch = scope.launch {
            var answering = emptySet<String>()
            CoreDirectory.available.collect { cores ->
                val arrived = cores.filter { it.id !in answering && "tmdb" in it.capabilities }
                answering = cores.mapTo(hashSetOf()) { it.id }
                if (arrived.isEmpty()) return@collect
                for (source in graph.sourceRepository.enabled()) {
                    if (source.kind != app.opentv.data.model.SourceKind.XTREAM) continue
                    runCatching { fillFromCore(source, app.opentv.account.sync.LanCatalogue.TMDB) }
                }
            }
        }
    }

    private fun engine(s: AccountState, accountKey: ByteArray): SyncEngine? {
        engine?.let { return it }
        val api = server ?: return null
        // The replica belongs to one account: another account's records never mix in.
        if (store.meta("user") != s.userId) { store.clear(); store.setMeta("user", s.userId) }
        val tag = store.meta("tag") ?: SyncIds.newUid().take(8).also { store.setMeta("tag", it) }
        // Core records taken in before this version knew the collection were kept but never applied.
        CoreDirectory.init(context)
        for (r in store.inColl("core")) CoreDirectory.put(r.key, r.value?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) })
        AccountShared.init(context)
        NetworkGuide.init(context)
        for (coll in listOf("lease", "snap")) for (r in store.inColl(coll)) AccountShared.put(coll, r.key, r.value?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) })
        val graph = ServiceLocator.get(context)
        watchCores(graph)
        val colls = AppCollections(graph, settings, prefs, SyncIds(store),
            onPlaylistsChanged = { SyncWorker.refreshNow(context) }, onGuideChanged = { SyncWorker.refreshNow(context) }).all()
        return SyncEngine(store, StampClock(tag), accountKey, colls, api.records(token(s)), alsoSend = { lan?.sendRecords(it) },
            mayStartAccount = { graph.database.sources().all().any { !it.isLocalPlaylist } }).also { engine = it }
    }

    /** A full pass: catch up with the server, take in and send this device's changes. */
    private suspend fun sync() = syncLock.withLock {
        val s = state ?: return@withLock
        val accountKey = key(s) ?: return@withLock
        val api = server ?: return@withLock
        val e = engine(s, accountKey) ?: return@withLock
        syncing = true; publish()
        app.opentv.core.StatusBus.start("account", "Syncing your library and settings", app.opentv.core.StatusBus.Origin.ACCOUNT, s.email)
        val result = runCatching {
            devices = api.me(token(s)).devices
            lan?.prune()
            e.sync()
        }
        syncing = false
        app.opentv.core.StatusBus.end("account")
        result.onSuccess {
            state = s.copy(lastSyncAt = System.currentTimeMillis(), lastError = null); file.write(state)
            publish()
        }.onFailure { failed(s, it) }
    }

    private fun failed(s: AccountState, e: Throwable) {
        Log.w(TAG, "Sync failed", e)
        if (e is AccountError && e.code == "signed_out") { forget(); publish("This device was signed out."); return }
        state = s.copy(lastError = "Last sync failed: ${e.message}"); file.write(state); publish()
    }

    /** Sends this device's changes to some collections (after an edit here). Left for the next pass when offline. */
    private suspend fun push(colls: Set<String>) {
        val s = state ?: return
        val e = engine ?: return
        runCatching { e.push(colls) }.onFailure { failed(s, it) }.onSuccess {
            if (s.lastError != null) { state = s.copy(lastError = null); file.write(state); publish() }
        }
    }

    /** Collections each table feeds. */
    private val tables = mapOf(
        "sources" to setOf("pl"), "profiles" to setOf("pr"), "playback_positions" to setOf("wp"),
        "channels" to setOf("cm", "gd", "rc"), "movies" to setOf("fv"), "series" to setOf("fv"),
        "user_collections" to setOf("co"), "collection_entries" to setOf("ce"), "saved_tv_channels" to setOf("tv"),
        "epg_feeds" to setOf("gd"), "channel_alias_overrides" to setOf("gd"),
    )

    /** Signed in with the key: sync now, push each local change within about a second, take others' changes live. */
    @OptIn(FlowPreview::class)
    private fun begin() {
        if (watchers?.isActive == true) return
        watchers = scope.launch {
            launch { sync() }
            val dirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val kick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
            fun mark(colls: Collection<String>) { if (dirty.addAll(colls)) kick.trySend(Unit) else kick.trySend(Unit) }
            launch {
                val graph = ServiceLocator.get(context)
                graph.database.invalidationTracker.createFlow(*tables.keys.toTypedArray(), "episodes", emitInitialState = false)
                    .collect { changed ->
                        mark(changed.flatMap { tables[it].orEmpty() })
                        // A catalogue load may let waiting records apply (a resume point for a film that just arrived).
                        if ((changed.contains("movies") || changed.contains("channels") || changed.contains("episodes")) && (engine?.pendingCount() ?: 0) > 0) retry.trySend(Unit)
                    }
            }
            launch { prefs.changes().drop(1).collect { mark(setOf("st")) } }
            launch { settings.recentChannels.drop(1).collect { mark(setOf("rc")) } }
            launch { retry.receiveAsFlow().debounce(5_000).collect { runCatching { engine?.retryPending() } } }
            // The pusher: settings, favourites and the rest go up a second after they stop changing. Resume points save
            // every 15 s while playing: they reach nearby devices at once, the server when playback stops (or once a minute).
            launch {
                var lastPositionsUp = 0L
                while (true) {
                    kick.receive()
                    val started = System.currentTimeMillis()
                    while (true) {
                        val onlyPositions = dirty.all { it == "wp" }
                        val quiet = if (onlyPositions) 20_000L else 1_000L
                        if (onlyPositions && (lan?.connected()?.isNotEmpty() == true)) runCatching { engine?.shareNearby(setOf("wp")) }
                        if (onlyPositions && System.currentTimeMillis() - lastPositionsUp > 60_000 && System.currentTimeMillis() - started > 1_000) break
                        kotlinx.coroutines.withTimeoutOrNull(quiet) { kick.receive() } ?: break
                    }
                    val colls = dirty.toSet().also { dirty.removeAll(it) }
                    if (colls.isEmpty()) continue
                    if ("wp" in colls) lastPositionsUp = System.currentTimeMillis()
                    push(colls)
                }
            }
            launch { live() }
            startLan(); _lanReady.value++
            launch { presenceLoop() }
            // A safety net for a missed push (a socket that died quietly while the TV slept): catch up every 10 minutes.
            launch { while (true) { delay(10 * 60_000); sync() } }
        }
    }

    /** The app came to the front: catch up (cheap when nothing changed). */
    fun foreground() { if (watchers?.isActive == true) { syncNow(); lan?.redialNow(); kickPresence() } }

    private val retry = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Holds the live connection while signed in, reconnecting with backoff. */
    private suspend fun live() {
        var backoff = 2_000L
        while (true) {
            val s = state ?: return
            val api = server ?: return
            val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val ws = api.live(token(s), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch { runCatching { onLive(webSocket, text) }.onFailure { Log.w(TAG, "Live message failed", it) } }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
            })
            socket = ws
            lastPong = System.currentTimeMillis()
            // "pong" answers every "ping"; when they stop, the connection is dead even if nobody said so: reconnect.
            val pinger = scope.launch {
                while (true) {
                    delay(30_000); ws.send("ping")
                    if (System.currentTimeMillis() - lastPong > 75_000) { ws.cancel(); closed.complete(Unit); break }
                }
            }
            val opened = System.currentTimeMillis()
            closed.await()
            pinger.cancel()
            socket = null
            // A connection that held a minute or more starts the wait over; one that keeps dropping waits longer each
            // time (each reconnect also catches up with the server, so a flapping network mustn't loop quickly).
            backoff = if (System.currentTimeMillis() - opened > 60_000) 2_000L else (backoff * 2).coerceAtMost(5 * 60_000)
            delay(backoff)
        }
    }

    private val wire = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** [sealed]: what a device is doing and how to reach it on its network, sealed with the account key (the server
     *  relays it without being able to read it); only [net], the network hash, is visible to the server. */
    @kotlinx.serialization.Serializable private data class LiveMsg(val t: String, val net: String? = null, val from: String? = null,
        val records: List<WireRecord> = emptyList(), val sealed: String? = null)
    @kotlinx.serialization.Serializable private data class Announce(val endpoint: LanLink.Endpoint? = null, val now: LanLink.Presence? = null)
    private val announcedTo = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile private var lastPong = 0L

    private suspend fun onLive(ws: WebSocket, text: String) {
        lastPong = System.currentTimeMillis()
        if (text == "pong") return
        val m = wire.decodeFromString(LiveMsg.serializer(), text)
        when (m.t) {
            // (Re)connected: catch up on anything missed, and tell nearby devices how to reach this one.
            "hello" -> { myNet = m.net; announcedTo.clear(); startLan(); sync(); announce() }
            "records" -> engine?.receive(m.records)
            "presence" -> {
                val from = m.from ?: return
                val k = state?.let(::key) ?: return
                val a = m.sealed?.let { runCatching { wire.decodeFromString(Announce.serializer(), AccountCrypto.open(k, "presence", from, it)) }.getOrNull() } ?: return
                a.now?.let(::heard)
                val ep = a.endpoint
                if (ep != null && !m.net.isNullOrEmpty() && m.net == myNet) {
                    lan?.connect(ep)
                    // A device that just came online doesn't know this one yet: answer once.
                    if (announcedTo.add(from)) announce()
                }
            }
        }
    }

    private var discovery: LanDiscovery? = null
    private val _lanReady = MutableStateFlow(0)

    /** Starts the direct Wi-Fi link and network discovery (needs no internet). */
    private fun startLan() {
        val s = state ?: return
        val k = key(s) ?: return
        val link = lan ?: LanLink(scope, k, s.deviceId, onRecords = { engine?.receive(it) },
            // Over the LAN the delay is milliseconds: stamp it with this device's receive time (no clock differences).
            onPresence = { heard(it.copy(at = System.currentTimeMillis())) },
            now = { engine?.clock?.now() ?: System.currentTimeMillis() },
            onCommand = { from, c ->
                scope.launch { app.opentv.remote.RemoteReceiver.handle(context, from, c, ::resolveCast); kickPresence() }
            },
            onLinked = { kickPresence(); app.opentv.catalogue.MatchingProgress.deviceLinked(context) },
            onEndpoint = { ep -> lanPrefs.edit().putString("ep:" + ep.device, "${ep.host}:${ep.port}").apply() },
            // Only devices still on the account (a removed one keeps the key until it next reaches the server).
            allowed = { id -> devices.isEmpty() || devices.any { it.id == id } },
            // LAN sync: another device asks for a source's catalogue that this one already has.
            onBulk = { req, emit ->
                val graph = ServiceLocator.get(context)
                app.opentv.account.sync.LanCatalogue.export(graph.database.openHelper.readableDatabase, graph.sourceRepository.enabled(), req, emit)
            })
            .also { lan = it; _lanReady.value++; app.opentv.data.repo.CatalogRepository.fromNearby = ::fillFromNearby }
        val ep = link.start() ?: return
        // Addresses that worked last time: dial them now rather than waiting for discovery.
        lanPrefs.all.forEach { (k, v) ->
            val (host, port) = (v as? String)?.split(':')?.takeIf { it.size == 2 } ?: return@forEach
            port.toIntOrNull()?.let { link.connect(LanLink.Endpoint(k.removePrefix("ep:"), host, it)) }
        }
        if (discovery == null) discovery = LanDiscovery(context, s.deviceId) { link.connect(it) }.also { it.start(ep.port) }
        watchNetwork()
    }

    /**
     * LAN sync, the asking side: copies a source's channels ([LanCatalogue.LIVE]) or movies and shows
     * ([LanCatalogue.VOD]) from the first linked device that has them. Waits a few seconds for links when the app
     * has only just started. False: nobody nearby has it (the provider download runs instead).
     */
    private suspend fun fillFromNearby(source: app.opentv.data.model.Source, part: String): Boolean {
        // TMDB matches: jasktv-core, when one answers, has matched the whole playlist already.
        if (part == app.opentv.account.sync.LanCatalogue.TMDB && fillFromCore(source, part)) return true
        // No core answers: the builder's snapshot in the account's files (works away from home, with every core off).
        if (part == app.opentv.account.sync.LanCatalogue.TMDB && fillFromAccount(source)) return true
        val link = lan ?: return false
        kotlinx.coroutines.withTimeoutOrNull(4_000) { link.linked.first { it.isNotEmpty() } }
        if (link.linked.value.isEmpty()) { Log.i(TAG, "LAN sync $part for ${source.name}: no device of this account linked on this network"); return false }
        val room = ServiceLocator.get(context).database
        val request = LanLink.BulkRequest(app.opentv.account.sync.LanCatalogue.sourceKey(source), part)
        for (device in link.linked.value) {
            val name = devices.firstOrNull { it.id == device }?.name ?: "another device"
            val id = "nearby-${source.id}-$part"
            val title = when (part) {
                app.opentv.account.sync.LanCatalogue.LIVE -> "Copying channels"
                app.opentv.account.sync.LanCatalogue.TMDB -> "Copying TMDB matches"
                else -> "Copying movies and shows"
            }
            app.opentv.core.StatusBus.start(id, title, app.opentv.core.StatusBus.Origin.NEARBY, "From $name")
            val import = app.opentv.account.sync.LanCatalogue.Import(room, source.id)
            val started = System.currentTimeMillis()
            val ok = try {
                link.fetch(device, request) { chunk ->
                    import.put(chunk)
                    app.opentv.core.StatusBus.update(id, detail = "From $name · ${import.rows} items")
                }
            } finally { app.opentv.core.StatusBus.end(id) }
            Log.i(TAG, "LAN sync $part for ${source.name} from $name: ok=$ok, ${import.rows} rows in ${System.currentTimeMillis() - started} ms")
            if (ok && import.rows > 0) return true
        }
        return false
    }

    /**
     * Copies [part] of [source] from jasktv-core: the same bulk chunks a nearby device sends, one JSON object a line,
     * into the same importer (which only adds rows this device doesn't have). False: no core, or it had nothing.
     */
    /**
     * The account's copy of the TMDB match table: the builder core's snapshot of [source]'s table in the account's
     * files (a base, then the changes since). Only what this device hasn't taken yet is downloaded. True: this device
     * is up to date with it (nothing to match here). False: there's no snapshot, or it couldn't be read.
     */
    private suspend fun fillFromAccount(source: app.opentv.data.model.Source): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val s = state ?: return@withContext false
        val key = key(s) ?: return@withContext false
        val snap = AccountShared.get("snap", app.opentv.account.sync.LanCatalogue.sourceKey(source)) ?: return@withContext false
        fun str(k: String) = snap[k]?.jsonPrimitive?.contentOrNull
        fun num(k: String) = snap[k]?.jsonPrimitive?.longOrNull ?: 0L
        val cursor = "since:snap:${str("by")}:${source.id}"
        var at = corePrefs.getLong(cursor, 0)
        val files = listOfNotNull(str("base")?.let { it to num("base_now") }, str("delta")?.let { it to num("delta_now") })
            // Anything newer than this device's copy, a rebuilt base too (its delta is written against it).
            .filter { (_, upto) -> upto > at }
        if (files.isEmpty()) return@withContext true
        val id = "account-${source.id}"
        app.opentv.core.StatusBus.start(id, "Copying TMDB matches", app.opentv.core.StatusBus.Origin.NEARBY, "From your account")
        val import = app.opentv.account.sync.LanCatalogue.Import(ServiceLocator.get(context).database, source.id, replace = true)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val started = System.currentTimeMillis()
        try {
            for ((file, upto) in files) {
                val request = okhttp3.Request.Builder().url("${serverUrl.trimEnd('/')}/v1/blobs/$file").header("Authorization", "Bearer ${token(s)}").build()
                val sealed = ServiceLocator.get(context).httpClient.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    r.body!!.bytes()
                }
                val plain = AccountCrypto.openBytes(key, "blob", file, sealed)
                java.util.zip.GZIPInputStream(plain.inputStream()).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        val chunk = json.decodeFromString(LanLink.BulkChunk.serializer(), line)
                        if (chunk.done) break
                        import.put(chunk)
                        app.opentv.core.StatusBus.update(id, detail = "From your account · ${import.rows} items")
                    }
                }
                at = upto
                corePrefs.edit().putLong(cursor, at).apply()
            }
            Log.i(TAG, "Copied tmdb for ${source.name} from the account's snapshot: ${import.rows} rows in ${System.currentTimeMillis() - started} ms")
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            Log.w(TAG, "Copy from the account's snapshot failed: ${e.javaClass.simpleName}")
            false
        } finally { app.opentv.core.StatusBus.end(id) }
    }

    /** One of the account's files (sealed by a core: the match-table snapshot, the network guide), opened. */
    fun accountFile(name: String): ByteArray? {
        val s = state ?: return null
        val key = key(s) ?: return null
        val request = okhttp3.Request.Builder().url("${serverUrl.trimEnd('/')}/v1/blobs/$name").header("Authorization", "Bearer ${token(s)}").build()
        val sealed = runCatching {
            ServiceLocator.get(context).httpClient.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull() ?: return null
        return runCatching { AccountCrypto.openBytes(key, "blob", name, sealed) }.getOrNull()
    }

    private suspend fun fillFromCore(source: app.opentv.data.model.Source, part: String): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val core = CoreDirectory.endpoint("tmdb") ?: return@withContext false
        val key = app.opentv.account.sync.LanCatalogue.sourceKey(source)
        val id = "core-${source.id}-$part"
        app.opentv.core.StatusBus.start(id, "Copying TMDB matches", app.opentv.core.StatusBus.Origin.NEARBY, "From ${core.name}")
        // A core's table is the account's (it's the builder, or a copy of the builder's): its rows replace stale ones.
        val import = app.opentv.account.sync.LanCatalogue.Import(ServiceLocator.get(context).database, source.id, replace = true)
        val started = System.currentTimeMillis()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val ok = try {
            // Only what changed since the last copy (core's clock, from the end of that copy).
            val since = corePrefs.getLong("since:${core.id}:$part:$key", 0)
            val request = okhttp3.Request.Builder().url("${core.base}/catalogue/$key/$part?since=$since").header("Authorization", "Bearer ${core.secret}").build()
            ServiceLocator.get(context).httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val reader = response.body?.charStream()?.buffered() ?: return@use false
                var done = false
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val chunk = json.decodeFromString(LanLink.BulkChunk.serializer(), line)
                    if (chunk.done) {
                        done = true
                        json.parseToJsonElement(line).jsonObject["now"]?.jsonPrimitive?.longOrNull?.let { corePrefs.edit().putLong("since:${core.id}:$part:$key", it).apply() }
                        break
                    }
                    import.put(chunk)
                    app.opentv.core.StatusBus.update(id, detail = "From ${core.name} · ${import.rows} items")
                }
                done
            }
        } catch (e: Exception) {
            Log.w(TAG, "Copy $part from jasktv-core failed: ${e.javaClass.simpleName}")
            CoreDirectory.forget()
            false
        } finally { app.opentv.core.StatusBus.end(id) }
        Log.i(TAG, "Copied $part for ${source.name} from ${core.name}: ok=$ok, ${import.rows} rows in ${System.currentTimeMillis() - started} ms")
        ok
    }

    private val corePrefs by lazy { context.getSharedPreferences("jask_core_copies", Context.MODE_PRIVATE) }

    private val lanPrefs by lazy { context.getSharedPreferences("jask_lan", Context.MODE_PRIVATE) }
    private var networkWatch: android.net.ConnectivityManager.NetworkCallback? = null

    /** Wi-Fi back or changed: dial again at once, and re-advertise on the new network. */
    private fun watchNetwork() {
        if (networkWatch != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        networkWatch = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                scope.launch {
                    delay(500)
                    val ep = lan?.start() ?: return@launch
                    discovery?.start(ep.port)
                    lan?.redialNow()
                }
            }
        }.also { runCatching { cm.registerDefaultNetworkCallback(it) } }
    }

    /** Round-trip time to each device linked on this Wi-Fi (ms). */
    val lanRtt: StateFlow<Map<String, Long>> get() = lan?.rtt ?: MutableStateFlow(emptyMap<String, Long>()).asStateFlow()
    fun probe(device: String) = lan?.probe(device)

    /** Another device's presence, from the server or the LAN: keep the newest. */
    private fun heard(p: LanLink.Presence) {
        val cur = nearby.value[p.device]
        if (cur == null || p.at >= cur.at) nearby.value = nearby.value + (p.device to p)
    }

    /** Devices linked directly on this Wi-Fi now (the remote works only with these). */
    val lanLinked: StateFlow<Set<String>> get() = lan?.linked ?: MutableStateFlow(emptySet<String>()).asStateFlow()
    /** Bumps when the LAN link object is (re)created, so the UI re-reads [lanLinked]. */
    val lanReady: StateFlow<Int> = _lanReady.asStateFlow()

    /** What this device is doing, for presence. */
    private suspend fun presenceNow(): LanLink.Presence? {
        val s = state ?: return null
        val session = app.opentv.remote.NowPlaying.session.value
        val base = LanLink.Presence(s.deviceId, deviceName(), deviceKind = deviceKind(), at = engine?.clock?.now() ?: System.currentTimeMillis(),
            remote = app.opentv.remote.RemoteReceiver.inFront)
        if (session == null) return base
        val (pos, dur, playing) = kotlinx.coroutines.withContext(Dispatchers.Main) {
            Triple(session.engine.positionMillis, session.engine.durationMillis, session.engine.status.value.isPlaying)
        }
        val db = ServiceLocator.get(context).database
        val ids = SyncIds(store)
        val item = session.item
        return when {
            item.channelId != null -> db.channels().byId(item.channelId)?.let { c ->
                base.copy(title = c.customName ?: c.displayName, kind = "live", playing = playing,
                    item = ids.uid(SyncIds.SOURCE, c.sourceId)?.let { app.opentv.remote.CastItem("live", it, c.streamId, title = c.customName ?: c.displayName) })
            } ?: base
            item.mediaKey?.startsWith("movie:") == true -> db.movies().byId(item.mediaKey.substringAfter(':').toLong())?.let { m ->
                base.copy(title = item.title.ifBlank { m.name }, kind = "movie", positionMs = pos, durationMs = dur, playing = playing,
                    item = ids.uid(SyncIds.SOURCE, m.sourceId)?.let { app.opentv.remote.CastItem("movie", it, m.streamId, title = item.title, positionMs = pos, durationMs = dur) })
            } ?: base
            item.mediaKey?.startsWith("ep:") == true -> db.episodes().byId(item.mediaKey.substringAfter(':').toLong())?.let { e ->
                val show = db.series().byProviderId(e.sourceId, e.seriesId)?.name
                base.copy(title = show ?: e.title, detail = "S${e.season} · E${e.episodeNumber}", kind = "episode", positionMs = pos, durationMs = dur,
                    playing = playing, item = ids.uid(SyncIds.SOURCE, e.sourceId)?.let {
                        app.opentv.remote.CastItem("episode", it, e.episodeId, e.seriesId, item.title, pos, dur) })
            } ?: base
            else -> base.copy(title = item.title, kind = "recording", positionMs = pos, durationMs = dur, playing = playing)
        }
    }

    /**
     * Tells the account's other devices what this one is doing: over the Wi-Fi link every second while something
     * plays (cheap, instant), and through the server when it changes or once a minute while playing (so devices
     * elsewhere see it too, a little later). Also says how to reach this device on its network.
     */
    private suspend fun presenceLoop() = kotlinx.coroutines.coroutineScope {
        // Sent the moment anything changes: a new title, play/pause/buffering, a remote command applied, a new link.
        launch {
            app.opentv.remote.NowPlaying.session.collectLatest { session ->
                kickPresence()
                session?.engine?.status?.map { it.isPlaying to it.playWhenReady }?.distinctUntilChanged()?.collect { kickPresence() }
            }
        }
        var lastCloud = 0L
        var lastKey: String? = null
        var playingSomething = false
        while (true) {
            // Otherwise every 2 s while playing (receivers move the progress bar on themselves in between).
            kotlinx.coroutines.withTimeoutOrNull(if (playingSomething) 2_000L else 30_000L) { presenceKick.receive() }
            val p = runCatching { presenceNow() }.getOrNull() ?: continue
            playingSomething = p.title != null
            lan?.sendPresence(p)
            val key = "${p.title}|${p.detail}|${p.playing}|${p.remote}"
            val now = System.currentTimeMillis()
            if (key != lastKey || (playingSomething && now - lastCloud > 60_000) || now - lastCloud > 15 * 60_000) {
                announce(p); lastCloud = now; lastKey = key
            }
        }
    }

    private val presenceKick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private fun kickPresence() { presenceKick.trySend(Unit) }

    /** Sends presence (and this device's LAN address) through the server, sealed so the server can't read it. */
    private fun announce(p: LanLink.Presence? = null) {
        val s = state ?: return
        scope.launch {
            val presence = p ?: presenceNow() ?: return@launch
            val ep = runCatching { lan?.start() }.getOrNull()
            val k = key(s) ?: return@launch
            val sealed = AccountCrypto.seal(k, "presence", s.deviceId, wire.encodeToString(Announce.serializer(), Announce(ep, presence)))
            socket?.send(wire.encodeToString(LiveMsg.serializer(), LiveMsg("presence", sealed = sealed)))
        }
    }

    // ---- Remote and casting (over the Wi-Fi link only) ----

    /** Sends a remote command to a device linked on this Wi-Fi. False: not linked (the remote needs the LAN). */
    fun sendCommand(device: String, c: app.opentv.remote.RemoteCommand): Boolean = lan?.sendCommand(device, c) ?: false

    /** Plays what this device is playing on [device] from the same spot, and pauses it here. */
    fun castTo(device: String, onResult: (Boolean) -> Unit = {}) {
        val session = app.opentv.remote.NowPlaying.session.value ?: return onResult(false)
        scope.launch {
            // The spot is read at the moment of sending, so the other device starts where this one is now.
            val item = presenceNow()?.item
            val sent = item != null && sendCommand(device, app.opentv.remote.RemoteCommand("cast", cast = item))
            // Stopped here, not paused: a provider allowing one stream at a time must see this one closed.
            kotlinx.coroutines.withContext(Dispatchers.Main) { if (sent) session.engine.stop(); onResult(sent) }
        }
    }

    /** Sends a movie or episode this device isn't playing ("movie:<id>" / "ep:<id>") to [device], from its saved spot here. */
    fun castKeyTo(device: String, mediaKey: String, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            val item = castItemFor(mediaKey)
            val sent = item != null && sendCommand(device, app.opentv.remote.RemoteCommand("cast", cast = item))
            kotlinx.coroutines.withContext(Dispatchers.Main) { onResult(sent) }
        }
    }

    private suspend fun castItemFor(mediaKey: String): app.opentv.remote.CastItem? {
        val db = ServiceLocator.get(context).database
        val ids = SyncIds(store)
        val saved = db.positions().get(settings.activeProfileId.value, mediaKey)?.takeIf { !it.isFinished }
        val pos = saved?.positionMillis ?: 0; val dur = saved?.durationMillis ?: 0
        val id = mediaKey.substringAfter(':').toLongOrNull() ?: return null
        return when {
            mediaKey.startsWith("movie:") -> db.movies().byId(id)?.let { m ->
                ids.uid(SyncIds.SOURCE, m.sourceId)?.let { app.opentv.remote.CastItem("movie", it, m.streamId, title = m.name, positionMs = pos, durationMs = dur) } }
            mediaKey.startsWith("ep:") -> db.episodes().byId(id)?.let { e ->
                ids.uid(SyncIds.SOURCE, e.sourceId)?.let { app.opentv.remote.CastItem("episode", it, e.episodeId, e.seriesId, e.title, pos, dur) } }
            else -> null
        }
    }

    /** Plays here what [p]'s device is playing, from its spot, and pauses it there. */
    fun watchHere(p: LanLink.Presence) {
        val item = p.item ?: return
        // Where it is now: the last report plus the time since, while it plays.
        val pos = (p.positionMs ?: item.positionMs) + if (p.playing) System.currentTimeMillis() - p.at else 0
        // Release the other device's stream first (one-stream providers), then start here.
        sendCommand(p.device, app.opentv.remote.RemoteCommand("release"))
        scope.launch {
            val play = resolveCast(item.copy(positionMs = pos)) ?: return@launch
            app.opentv.remote.RemoteSignals.play(play)
        }
    }

    /** Readies [p]'s item to play here (fetches a show's episodes if this device hasn't), so "Watch here" starts at once. */
    fun prepare(p: LanLink.Presence) { val item = p.item ?: return; scope.launch { runCatching { resolveCast(item.copy(positionMs = 0)) } } }

    /** A cast item, named portably, as something this device can play (null: its playlist or title isn't here). */
    private suspend fun resolveCast(c: app.opentv.remote.CastItem): app.opentv.remote.RemoteSignals.Play? {
        val db = ServiceLocator.get(context).database
        val source = SyncIds(store).local(SyncIds.SOURCE, c.playlist)?.let { id -> db.sources().all().firstOrNull { it.id == id } } ?: return null
        return when (c.kind) {
            "live" -> db.channels().byProviderKey(source.id, c.id)?.let { app.opentv.remote.RemoteSignals.Play.Live(it.id) }
            "movie", "episode" -> {
                var key: String? = null; var url: String? = null; var title = c.title
                if (c.kind == "movie") db.movies().byProviderKey(source.id, c.id)?.let { key = "movie:${it.id}"; url = it.streamUrl; if (title.isBlank()) title = it.name }
                else {
                    var e = db.episodes().byProviderKey(source.id, c.id)
                    if (e == null && c.series != null) { runCatching { ServiceLocator.get(context).catalogRepository.ensureEpisodes(source, c.series) }; e = db.episodes().byProviderKey(source.id, c.id) }
                    e?.let { key = "ep:${it.id}"; url = it.streamUrl; if (title.isBlank()) title = it.title }
                }
                val k = key ?: return null
                // The player resumes from the saved spot: put the sender's there first.
                // Always written, even near the start, so this device doesn't resume from an older spot of its own.
                // Duration: the sender's, else this device's, else unknown (0, which never reads as finished; the
                // old "position + 1" made a title this device had never played look finished, so it started over).
                db.positions().get(settings.activeProfileId.value, k).let { old ->
                    ServiceLocator.get(context).playbackPositions.upsert(app.opentv.data.model.PlaybackPosition(settings.activeProfileId.value, k,
                        c.positionMs, c.durationMs.takeIf { it > 0 } ?: old?.durationMillis?.takeIf { it > c.positionMs } ?: 0L, System.currentTimeMillis()))
                }
                app.opentv.remote.RemoteSignals.Play.Vod(k, url!!, title, source.userAgent)
            }
            else -> null
        }
    }

    // ---- TV sign-in by QR ----

    /** A TV's sign-in code on show: [link] goes in the QR code; [fingerprint] is shown beside the code to check against. */
    data class TvPairing(val code: String, val link: String, val fingerprint: String, val expired: Boolean = false)
    /** A TV asking to be signed in, waiting for this phone's yes. */
    data class PairOffer(val code: String, val tvName: String, val fingerprint: String, val tvKey: String?, val error: String? = null, val working: Boolean = false)

    private val _tvPairing = MutableStateFlow<TvPairing?>(null)
    val tvPairing: StateFlow<TvPairing?> = _tvPairing.asStateFlow()
    private val _pairOffer = MutableStateFlow<PairOffer?>(null)
    val pairOffer: StateFlow<PairOffer?> = _pairOffer.asStateFlow()
    private var pairJob: Job? = null

    /** On a signed-out device (the TV): shows a code and waits for a phone to approve it, renewing it as it expires. */
    fun startPairing() {
        val api = server ?: return
        if (pairJob?.isActive == true || state != null) return
        pairJob = scope.launch {
            // Three codes (30 minutes), then it stops and offers a new one: a TV left on this screen mustn't ask forever.
            repeat(3) {
                if (state != null) return@launch
                val kp = kotlinx.coroutines.withContext(Dispatchers.Default) { AccountCrypto.pairKeyPair() }
                val tvKey = AccountCrypto.publicKeyText(kp)
                val start = runCatching { api.pairStart(tvKey, deviceName(), deviceKind()) }.getOrNull()
                if (start == null) { delay(10_000); return@repeat }
                _tvPairing.value = TvPairing(start.code, serverUrl.trimEnd('/') + "/pair#c=${start.code}&k=$tvKey", AccountCrypto.pairFingerprint(tvKey))
                // Asks every 2 seconds for the first 3 minutes, then every 5, until the phone says yes or the code runs out.
                val shown = System.currentTimeMillis()
                while (System.currentTimeMillis() < start.expiresAt) {
                    delay(if (System.currentTimeMillis() - shown < 180_000) 2_000 else 5_000)
                    val take = runCatching { api.pairTake(start.code, start.secret) }.getOrNull() ?: continue
                    if (take.state == "expired") break
                    val r = take.result ?: continue
                    val key = runCatching { AccountCrypto.openFromPhone(kp, r.phoneKey, start.code, r.sealedKey) }.getOrNull()
                    state = AccountState(serverUrl, r.user.id, r.user.email, r.user.name, r.deviceId, box.seal(r.token.toByteArray()),
                        key?.let(box::seal))
                    file.write(state)
                    _tvPairing.value = null
                    publish()
                    if (key != null) begin()
                    return@launch
                }
            }
            _tvPairing.value = (_tvPairing.value ?: TvPairing("", "", "")).copy(expired = true)
        }
    }

    /** The code is off screen: stop asking. */
    fun stopPairing() { pairJob?.cancel(); pairJob = null; _tvPairing.value = null }

    /** On the phone: a TV's code (from its QR code, with its key; or typed, without) — look it up and ask. */
    fun offerPairing(code: String, tvKey: String?) {
        val c = code.uppercase().filter { it.isLetterOrDigit() }
        val s = state
        if (s == null || key(s) == null) { _pairOffer.value = PairOffer(c, "the TV", "", tvKey, "Sign in on this phone first, then scan the TV's code again."); return }
        val api = server ?: return
        _pairOffer.value = PairOffer(c, "the TV", "", tvKey, working = true)
        scope.launch {
            runCatching { api.pairInfo(token(s), c) }.onSuccess { info ->
                // From the QR code: the key the TV showed must be the one the server holds (else someone swapped it).
                if (tvKey != null && tvKey != info.tvKey) { _pairOffer.value = PairOffer(c, info.device.name, "", tvKey, "That code doesn't match the TV. Show a new code on the TV."); return@launch }
                _pairOffer.value = PairOffer(c, info.device.name, AccountCrypto.pairFingerprint(info.tvKey), info.tvKey)
            }.onFailure { _pairOffer.value = PairOffer(c, "the TV", "", tvKey, it.message ?: "Couldn't find that code.") }
        }
    }

    fun approvePairing() {
        val offer = _pairOffer.value ?: return
        val s = state ?: return
        val k = key(s) ?: return
        val tvKey = offer.tvKey ?: return
        val api = server ?: return
        _pairOffer.value = offer.copy(working = true, error = null)
        scope.launch {
            runCatching {
                val (phoneKey, sealed) = AccountCrypto.sealForTv(k, tvKey, offer.code)
                api.pairApprove(token(s), offer.code, phoneKey, sealed)
            }.onSuccess { _pairOffer.value = null; sync() }
                .onFailure { _pairOffer.value = offer.copy(working = false, error = it.message ?: "Couldn't sign in the TV.") }
        }
    }

    fun dismissPairing() { _pairOffer.value = null }

    // ---- Devices, sign out, delete ----

    fun removeDevice(id: String) {
        val s = state ?: return
        scope.launch { runCatching { server?.removeDevice(token(s), id) }; sync() }
    }

    /** Signs this device out. Playlists and settings stay on the device; they just stop syncing. */
    fun signOut() {
        val s = state ?: return
        scope.launch {
            runCatching { server?.signOut(token(s)) }
            forget()
            publish()
        }
    }

    /** Deletes the account and everything synced on the server. This device keeps its playlists and settings. */
    fun deleteAccount() {
        val s = state ?: return
        scope.launch {
            runCatching { server?.deleteAccount(token(s)) }
                .onSuccess { forget(); publish() }
                .onFailure { publish("Couldn't delete the account: ${it.message}") }
        }
    }

    private fun forget() {
        watchers?.cancel(); watchers = null
        socket?.close(1000, "Signed out"); socket = null
        lan?.stop(); lan = null; discovery?.stop(); discovery = null; engine = null; nearby.value = emptyMap()
        runCatching { store.clear() }
        state = null; devices = emptyList(); pendingWrap = null
        file.write(null)
    }

    private fun deviceName(): String = (android.provider.Settings.Global.getString(context.contentResolver, "device_name")
        ?: "${Build.MANUFACTURER} ${Build.MODEL}").take(60)

    private fun deviceKind(): String = when {
        // Fire TV and Google TV: the TV UI mode or the TV features (the same test the app uses everywhere).
        app.opentv.isRunningOnTelevision(context) -> "tv"
        context.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
        else -> "phone"
    }

    companion object {
        private const val TAG = "jaskAccount"
        @Volatile private var instance: AccountManager? = null
        /** Whether this device is signed in to an account (read without starting anything). */
        fun signedIn(context: Context): Boolean = AccountStateFile(context.applicationContext).read() != null

        fun get(context: Context): AccountManager = instance ?: synchronized(this) {
            instance ?: AccountManager(context.applicationContext).also { instance = it }
        }
    }
}
