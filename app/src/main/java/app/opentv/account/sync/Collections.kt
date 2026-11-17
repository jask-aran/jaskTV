package app.opentv.account.sync

import app.opentv.account.AccountPrefs
import app.opentv.account.SyncedSource
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.local.isLocalPlaylist
import app.opentv.data.model.ChannelAliasOverride
import app.opentv.data.model.CollectionEntry
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.Source
import app.opentv.data.model.UserCollection
import app.opentv.livetv.SavedTvChannel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * Stable ids for local rows that differ per device: playlists, profiles and collections. A record names a
 * playlist by its uid, minted once by whichever device added it and carried in the playlist's own record, so a
 * provider changing its address doesn't orphan anything. Profile 1 (every device has it) is always "main".
 */
class SyncIds(private val store: SyncStore) {
    fun uid(kind: String, local: Long): String? = if (kind == PROFILE && local == 1L) MAIN else store.uidFor(kind, local.toString())
    fun local(kind: String, uid: String): Long? = if (kind == PROFILE && uid == MAIN) 1L else store.localFor(kind, uid)?.toLongOrNull()
    fun mint(kind: String, local: Long): String = uid(kind, local) ?: newUid().also { store.link(kind, local.toString(), it) }
    fun link(kind: String, local: Long, uid: String) = store.link(kind, local.toString(), uid)
    fun unlink(kind: String, local: Long) = store.unlink(kind, local.toString())
    /** Remembers which local row a record was last seen as (so a missing row can be told from a renumbered one). */
    fun note(kind: String, local: String, key: String) { if (store.uidFor(kind, local) != key) store.link(kind, local, key) }
    fun noted(kind: String, key: String): String? = store.localFor(kind, key)

    companion object {
        const val SOURCE = "source"; const val PROFILE = "profile"; const val COLLECTION = "collection"
        const val MAIN = "main"
        private val random = SecureRandom()
        fun newUid(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}

private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) when (v) {
        null -> Unit
        is String -> put(k, v)
        is Number -> put(k, v)
        is Boolean -> put(k, v)
        is JsonElement -> put(k, v)
    }
}
private val JsonElement.o get() = jsonObject
private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull ?: false

/**
 * The collections an account syncs. Keys name things portably ("<playlist uid>|<provider id>"), values are
 * small JSON. Each says what a missing key means: a favourite on a channel this device has, now off, was
 * removed here; one on a channel it hasn't loaded wasn't.
 */
class AppCollections(
    private val graph: ServiceLocator.Graph,
    private val settings: AppSettings,
    private val prefs: AccountPrefs,
    private val ids: SyncIds,
    private val onPlaylistsChanged: () -> Unit,
    private val onGuideChanged: () -> Unit,
) {
    private val db get() = graph.database
    private val fetched = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private suspend fun sources(): List<Source> = db.sources().all().filterNot { it.isLocalPlaylist }
    private fun sourceUid(id: Long) = ids.uid(SyncIds.SOURCE, id)
    private fun sourceId(uid: String) = ids.local(SyncIds.SOURCE, uid)
    private fun profileUid(id: Long) = ids.uid(SyncIds.PROFILE, id)
    private fun profileId(uid: String) = ids.local(SyncIds.PROFILE, uid)
    private fun split(key: String, n: Int) = key.split('|', limit = n).takeIf { it.size == n }

    /** Playlists, credentials included (every record is sealed with the account key). Key: playlist uid. */
    val playlists = object : SyncCollection {
        override val name = "pl"
        override suspend fun snapshot(): Map<String, JsonElement> = sources().associate { s ->
            ids.mint(SyncIds.SOURCE, s.id) to json.encodeToJsonElement(SyncedSource.serializer(), SyncedSource.of(s))
        }
        override suspend fun apply(key: String, value: JsonElement?): Boolean {
            val all = db.sources().all()
            val here = sourceId(key)?.let { id -> all.firstOrNull { it.id == id } }
            if (value == null) {
                here?.let { graph.catalogRepository.deleteSource(it.id); ids.unlink(SyncIds.SOURCE, it.id); onPlaylistsChanged() }
                return true
            }
            val synced = json.decodeFromJsonElement(SyncedSource.serializer(), value)
            // The same playlist added by hand on this device too: adopt it rather than adding it twice.
            val same = here ?: all.firstOrNull { s ->
                s.kind.name == synced.kind && s.url.trimEnd('/') == synced.url.trimEnd('/') && s.username == synced.username &&
                    s.macAddress == synced.macAddress && sourceUid(s.id) == null
            }
            val next = synced.toSource(same?.id ?: 0, same)
            if (same != null && next == same) { ids.link(SyncIds.SOURCE, same.id, key); return true }
            val id = graph.sourceRepository.save(next)
            ids.link(SyncIds.SOURCE, if (same != null) same.id else id, key)
            onPlaylistsChanged()
            return true
        }
        override suspend fun removedHere(key: String) = sourceId(key)?.let { id -> db.sources().all().none { it.id == id } } ?: false
    }

    /** Every synced setting, one record each, so two devices changing different settings never collide. */
    val settingsColl = object : SyncCollection {
        override val name = "st"
        override suspend fun snapshot() = prefs.read()
        override suspend fun apply(key: String, value: JsonElement?): Boolean { if (value != null) prefs.apply(mapOf(key to value)); return true }
        override suspend fun removedHere(key: String) = false
    }

    /** Profiles (names). Key: profile uid; "main" is every device's first profile. */
    val profiles = object : SyncCollection {
        override val name = "pr"
        override suspend fun snapshot() = db.profiles().all().associate { p -> ids.mint(SyncIds.PROFILE, p.id) to obj("name" to p.name) as JsonElement }
        override suspend fun apply(key: String, value: JsonElement?): Boolean {
            val id = profileId(key)
            if (value == null) {
                if (id != null && id != 1L) { db.profiles().deletePositions(id); db.collections().deleteProfileCollections(id); db.profiles().delete(id); ids.unlink(SyncIds.PROFILE, id) }
                return true
            }
            val name = value.o.str("name") ?: return true
            if (id != null) { if (db.profiles().all().firstOrNull { it.id == id }?.name != name) db.profiles().rename(id, name); return true }
            val newId = db.profiles().byName(name)?.id ?: db.profiles().insert(Profile(name = name, createdAtMillis = System.currentTimeMillis()))
            ids.link(SyncIds.PROFILE, newId, key)
            return true
        }
        override suspend fun removedHere(key: String) = profileId(key)?.let { id -> db.profiles().all().none { it.id == id } } ?: false
    }

    /**
     * Where you are in each film and episode, per profile. Key: "<profile>|movie|<playlist>|<stream id>" or
     * "<profile>|ep|<playlist>|<series id>/<episode id>" (the series, so a device that hasn't opened it can fetch its episodes).
     */
    val positions = object : SyncCollection {
        override val name = "wp"
        override suspend fun snapshot(): Map<String, JsonElement> {
            val out = HashMap<String, JsonElement>()
            val rows = db.positions().all()
            val movies = db.movies().byIds(rows.mapNotNull { it.mediaKey.removePrefix("movie:").takeIf { k -> it.mediaKey.startsWith("movie:") }?.toLongOrNull() })
                .associateBy { it.id }
            for (p in rows) {
                val prof = profileUid(p.profileId) ?: continue
                val (kind, local) = p.mediaKey.split(':', limit = 2).takeIf { it.size == 2 } ?: continue
                val key = when (kind) {
                    "movie" -> movies[local.toLongOrNull()]?.let { m -> sourceUid(m.sourceId)?.let { "$prof|movie|$it|${m.streamId}" } }
                    "ep" -> local.toLongOrNull()?.let { db.episodes().byId(it) }?.let { e -> sourceUid(e.sourceId)?.let { "$prof|ep|$it|${e.seriesId}/${e.episodeId}" } }
                    else -> null
                } ?: continue
                ids.note("wp", "${p.profileId}|${p.mediaKey}", key)
                out[key] = obj("p" to p.positionMillis, "d" to p.durationMillis, "t" to p.updatedAtMillis)
            }
            return out
        }
        private suspend fun localKey(key: String, fetch: Boolean = false): Pair<Long, String>? {
            val (prof, kind, pl, id) = split(key, 4) ?: return null
            val profile = profileId(prof) ?: return null
            val source = sourceId(pl) ?: return null
            val local = when (kind) {
                "movie" -> db.movies().byProviderKey(source, id)?.let { "movie:${it.id}" }
                "ep" -> {
                    val (series, episode) = id.split('/', limit = 2).takeIf { it.size == 2 } ?: return null
                    var e = db.episodes().byProviderKey(source, episode)
                    // Episodes load when a show is opened; this device may never have opened it.
                    // At most once every 6 hours per show, so a provider that's down isn't asked again on every retry.
                    val tried = fetched["$source|$series"]
                    if (e == null && fetch && (tried == null || System.currentTimeMillis() - tried > 6 * 3_600_000L)) {
                        fetched["$source|$series"] = System.currentTimeMillis()
                        db.sources().all().firstOrNull { it.id == source }?.let { s ->
                            graph.catalogRepository.ensureEpisodes(s, series); e = db.episodes().byProviderKey(source, episode)
                        }
                    }
                    e?.let { "ep:${it.id}" }
                }
                else -> null
            } ?: return null
            return profile to local
        }
        override suspend fun apply(key: String, value: JsonElement?): Boolean {
            val (profile, media) = localKey(key, fetch = value != null) ?: return value == null
            if (value == null) { db.positions().delete(profile, media); return true }
            val v = value.o
            graph.playbackPositions.upsert(PlaybackPosition(profile, media, v["p"]!!.jsonPrimitive.long, v["d"]!!.jsonPrimitive.long, v["t"]!!.jsonPrimitive.long))
            ids.note("wp", "$profile|$media", key)
            return true
        }
        /** Only when the same row this record was last seen as is gone (a re-fetched episode with a new number isn't a removal). */
        override suspend fun removedHere(key: String): Boolean {
            val (p, m) = localKey(key) ?: return false
            return ids.noted("wp", key) == "$p|$m" && db.positions().get(p, m) == null
        }
        override fun madeAt(value: JsonElement): Long? = value.o["t"]?.jsonPrimitive?.long
    }

    /** Your marks on channels: favourite, hidden, renamed. Key: "<playlist>|<stream id>". */
    val channelMarks = object : SyncCollection {
        override val name = "cm"
        override suspend fun snapshot() = db.channels().userMarks().mapNotNull { r ->
            sourceUid(r.sourceId)?.let { "$it|${r.streamId}" to obj("f" to r.favourite, "h" to r.hidden, "n" to r.customName) as JsonElement }
        }.toMap()
        override suspend fun apply(key: String, value: JsonElement?): Boolean {
            val (pl, stream) = split(key, 2) ?: return true
            val ch = sourceId(pl)?.let { db.channels().byProviderKey(it, stream) } ?: return value == null
            val v = value?.o
            val fav = v?.bool("f") ?: false; val hidden = v?.bool("h") ?: false; val nm = v?.str("n")
            if (ch.favourite != fav) db.channels().setFavourite(ch.id, fav)
            if (ch.hidden != hidden) db.channels().setHidden(ch.id, hidden)
            if (ch.customName != nm) db.channels().setCustomName(ch.id, nm)
            return true
        }
        override suspend fun removedHere(key: String): Boolean {
            val (pl, stream) = split(key, 2) ?: return false
            val ch = sourceId(pl)?.let { db.channels().byProviderKey(it, stream) } ?: return false
            return !ch.favourite && !ch.hidden && ch.customName == null
        }
    }

    /** Favourite films and shows. Key: "movie|<playlist>|<id>" or "show|…". */
    val favourites = object : SyncCollection {
        override val name = "fv"
        override suspend fun snapshot(): Map<String, JsonElement> {
            val out = HashMap<String, JsonElement>()
            db.movies().favouriteKeys().forEach { r -> sourceUid(r.sourceId)?.let { out["movie|$it|${r.providerId}"] = JsonPrimitive(true) } }
            db.series().favouriteKeys().forEach { r -> sourceUid(r.sourceId)?.let { out["show|$it|${r.providerId}"] = JsonPrimitive(true) } }
            return out
        }
        private suspend fun find(key: String): Pair<String, Pair<Long, Boolean>>? {
            val (kind, pl, id) = split(key, 3) ?: return null
            val source = sourceId(pl) ?: return null
            return when (kind) {
                "movie" -> db.movies().byProviderKey(source, id)?.let { kind to (it.id to it.favourite) }
                "show" -> db.series().byProviderId(source, id)?.let { kind to (it.id to it.favourite) }
                else -> null
            }
        }
        override suspend fun apply(key: String, value: JsonElement?): Boolean {
            val (kind, row) = find(key) ?: return value == null
            val want = value != null
            if (row.second != want) if (kind == "movie") db.movies().setFavourite(row.first, want) else db.series().setFavourite(row.first, want)
            return true
        }
        override suspend fun removedHere(key: String) = find(key)?.second?.second == false
    }

    /** Collections (name, profile). Key: collection uid. */
    val collections = object : SyncCollection {
        override val name = "co"
        override suspend fun snapshot() = db.collections().allCollections().mapNotNull { c ->
            profileUid(c.profileId)?.let { prof -> ids.mint(SyncIds.COLLECTION, c.id) to obj("name" to c.name, "pr" to prof, "at" to c.createdMillis) as JsonElement }
        }.toMap()
        override suspend fun apply(key: String, value: JsonElement?): Boolean {
            val id = ids.local(SyncIds.COLLECTION, key)
            if (value == null) { id?.let { db.collections().delete(it); ids.unlink(SyncIds.COLLECTION, it) }; return true }
            val v = value.o
            val name = v.str("name") ?: return true
            if (id != null) { if (db.collections().allCollections().firstOrNull { it.id == id }?.name != name) db.collections().rename(id, name); return true }
            val profile = profileId(v.str("pr") ?: SyncIds.MAIN) ?: return false
            // The same collection already here without a link (signed out and back in): adopt it, don't add a copy.
            val newId = db.collections().allCollections().firstOrNull { it.profileId == profile && it.name == name && ids.uid(SyncIds.COLLECTION, it.id) == null }?.id
                ?: db.collections().create(UserCollection(name = name, createdMillis = v["at"]?.jsonPrimitive?.long ?: 0, profileId = profile))
            ids.link(SyncIds.COLLECTION, newId, key)
            return true
        }
        override suspend fun removedHere(key: String) = ids.local(SyncIds.COLLECTION, key)?.let { id -> db.collections().allCollections().none { it.id == id } } ?: false
    }

    /** What's in each collection. Key: "<collection>|<kind>|<playlist>|<item id>". */
    val collectionEntries = object : SyncCollection {
        override val name = "ce"
        override suspend fun snapshot() = db.collections().allEntries().mapNotNull { e ->
            val c = ids.uid(SyncIds.COLLECTION, e.collectionId) ?: return@mapNotNull null
            val s = sourceUid(e.sourceId) ?: return@mapNotNull null
            "$c|${e.kind}|$s|${e.itemKey}" to (JsonPrimitive(true) as JsonElement)
        }.toMap()
        private fun local(key: String): CollectionEntry? {
            val (c, kind, s, item) = split(key, 4) ?: return null
            return CollectionEntry(ids.local(SyncIds.COLLECTION, c) ?: return null, kind, sourceId(s) ?: return null, item)
        }
        override suspend fun apply(key: String, value: JsonElement?): Boolean {
            val e = local(key) ?: return value == null
            if (value == null) db.collections().remove(e.collectionId, e.kind, e.sourceId, e.itemKey) else db.collections().add(e)
            return true
        }
        override suspend fun removedHere(key: String): Boolean {
            val e = local(key) ?: return false
            return db.collections().allEntries().none { it == e }
        }
    }

    /** My TV: the channels you saved, per profile. Key: "<profile>|<identity>". */
    val myTv = object : SyncCollection {
        override val name = "tv"
        override suspend fun snapshot() = db.savedTv().all().mapNotNull { c ->
            val prof = profileUid(c.profileId) ?: return@mapNotNull null
            "$prof|${c.identity}" to obj("l" to c.label, "s" to c.preferredSourceId?.let(::sourceUid), "id" to c.preferredStreamId, "i" to c.sortIndex) as JsonElement
        }.toMap()
        override suspend fun apply(key: String, value: JsonElement?): Boolean {
            val (prof, identity) = split(key, 2) ?: return true
            val profile = profileId(prof) ?: return value == null
            if (value == null) { db.savedTv().remove(profile, identity); return true }
            val v = value.o
            db.savedTv().save(SavedTvChannel(profile, identity, v.str("l").orEmpty(), v.str("s")?.let(::sourceId), v.str("id"), v["i"]?.jsonPrimitive?.int ?: 0))
            return true
        }
        override suspend fun removedHere(key: String): Boolean {
            val (prof, identity) = split(key, 2) ?: return false
            val profile = profileId(prof) ?: return false
            return db.savedTv().all().none { it.profileId == profile && it.identity == identity }
        }
    }

    /** Live TV's recent channels, newest first. One record: the list. */
    val recentChannels = object : SyncCollection {
        override val name = "rc"
        override suspend fun snapshot(): Map<String, JsonElement> {
            val ids = settings.recentChannels.value
            if (ids.isEmpty()) return emptyMap()
            val chans = db.channels().byIds(ids).associateBy { it.id }
            val keys = ids.mapNotNull { id -> chans[id]?.let { c -> sourceUid(c.sourceId)?.let { "$it|${c.streamId}" } } }
            // Unresolvable entries (a playlist not synced): the list isn't portable yet; don't send a partial one.
            if (keys.size < ids.size) return emptyMap()
            return mapOf("list" to JsonArray(keys.map(::JsonPrimitive)))
        }
        override suspend fun apply(key: String, value: JsonElement?): Boolean {
            val keys = value?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return true
            val local = keys.mapNotNull { k -> split(k, 2)?.let { (pl, s) -> sourceId(pl)?.let { db.channels().byProviderKey(it, s)?.id } } }
            if (local.size < keys.size) return false // channels not loaded yet: try again after the catalogue loads
            settings.setRecentChannels(local)
            return true
        }
        override suspend fun removedHere(key: String) = settings.recentChannels.value.isEmpty()
    }

    /** Guide sources and hand-made guide matches. Keys: "url|<feed url>", "builtin|<name>", "provider|<playlist>",
     *  "guide|<playlist>|<stream>" (chosen guide), "ident|<playlist>|<stream>" (iptv-org identity). */
    val guide = object : SyncCollection {
        override val name = "gd"
        override suspend fun snapshot(): Map<String, JsonElement> {
            val out = HashMap<String, JsonElement>()
            for (f in db.epgFeeds().all()) {
                when {
                    f.providerSourceId != null -> sourceUid(f.providerSourceId!!)?.let { out["provider|$it"] = obj("on" to f.enabled) }
                    f.builtIn -> out["builtin|${f.name}"] = obj("on" to f.enabled)
                    f.url != null -> out["url|${f.url}"] = obj("on" to f.enabled, "name" to f.name)
                }
            }
            db.channels().epgOverrides().forEach { o -> sourceUid(o.sourceId)?.let { out["guide|$it|${o.streamId}"] = JsonPrimitive(o.epgOverrideId) } }
            db.channelAliases().observeAll().first().forEach { a -> sourceUid(a.sourceId)?.let { out["ident|$it|${a.streamId}"] = obj("id" to a.iptvOrgId, "l" to a.label) } }
            return out
        }
        override suspend fun apply(key: String, value: JsonElement?): Boolean {
            val (kind, rest) = key.split('|', limit = 2).takeIf { it.size == 2 } ?: return true
            val feeds = db.epgFeeds()
            when (kind) {
                "url" -> {
                    val f = feeds.byUrl(rest)
                    if (value == null) { f?.let { feeds.delete(it.id); onGuideChanged() }; return true }
                    val on = value.o.bool("on")
                    if (f == null) { feeds.insert(EpgFeed(name = value.o.str("name") ?: rest, url = rest, enabled = on)); onGuideChanged() }
                    else if (f.enabled != on) { feeds.setEnabled(f.id, on); onGuideChanged() }
                }
                "builtin", "provider" -> {
                    val f = if (kind == "builtin") feeds.all().firstOrNull { it.builtIn && it.name == rest }
                        else sourceId(rest)?.let { feeds.forProvider(it) }
                    if (f == null) return value == null
                    val on = value?.o?.bool("on") ?: return true
                    if (f.enabled != on) { feeds.setEnabled(f.id, on); onGuideChanged() }
                }
                "guide", "ident" -> {
                    val (pl, stream) = split(rest, 2) ?: return true
                    val source = sourceId(pl) ?: return value == null
                    val ch = db.channels().byProviderKey(source, stream) ?: return value == null
                    if (kind == "guide") {
                        val epg = value?.jsonPrimitive?.contentOrNull
                        if (ch.epgOverrideId != epg) db.channels().setEpgOverride(ch.id, epg)
                    } else if (value == null) db.channelAliases().remove(source, stream)
                    else db.channelAliases().save(ChannelAliasOverride(source, stream, value.o.str("id").orEmpty(), value.o.str("l").orEmpty()))
                }
            }
            return true
        }
        override suspend fun removedHere(key: String): Boolean {
            val (kind, rest) = key.split('|', limit = 2).takeIf { it.size == 2 } ?: return false
            return when (kind) {
                "url" -> db.epgFeeds().byUrl(rest) == null
                "guide", "ident" -> {
                    val (pl, stream) = split(rest, 2) ?: return false
                    val source = sourceId(pl) ?: return false
                    val ch = db.channels().byProviderKey(source, stream) ?: return false
                    if (kind == "guide") ch.epgOverrideId == null else db.channelAliases().find(source, stream) == null
                }
                else -> false
            }
        }
    }

    /** Playlists first: everything else names them. */
    fun all(): List<SyncCollection> = listOf(playlists, profiles, settingsColl, collections, positions, channelMarks, favourites,
        collectionEntries, myTv, recentChannels, guide)
}
