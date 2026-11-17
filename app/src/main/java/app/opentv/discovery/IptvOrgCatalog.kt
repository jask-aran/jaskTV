/*
 * jaskTV — GPL-3.0-or-later.
 * IPTV-org channel identities/aliases (metadata only; no stream URLs).
 * Source: https://iptv-org.github.io/api/channels.json (Unlicense).
 */
package app.opentv.discovery

import android.content.Context
import app.opentv.data.model.Channel
import app.opentv.data.parser.ChannelNameNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ChannelIdentity(
    val id: String,
    val name: String,
    val country: String,
    val aliases: List<String>,
    val network: String?,
    /** IPTV-org category ids (`news`, `sports`, `kids`…). */
    val categories: List<String> = emptyList(),
)

internal object AliasKeys {
    fun key(text: String): String =
        ChannelNameNormalizer.groupKeyOf(ChannelNameNormalizer.normalize(text).baseName)

    fun country(region: String?): String? = when (region?.uppercase(Locale.ROOT)) {
        "UK" -> "GB"
        "USA" -> "US"
        else -> region?.uppercase(Locale.ROOT)
    }

    /** Don't silently alias an unrelated same-named channel in another country. */
    fun preferred(candidates: List<ChannelIdentity>, region: String?): ChannelIdentity? {
        val distinct = candidates.distinctBy { it.id }
        if (distinct.isEmpty()) return null
        val country = country(region)
        val matches = if (country == null) distinct else distinct.filter { it.country == country }
        return when {
            matches.size == 1 -> matches.single()
            country == null && distinct.size == 1 -> distinct.single()
            else -> null
        }
    }
}

class IptvOrgCatalog(context: Context) {
    private val cache = File(context.applicationContext.cacheDir, "jasktv-iptvorg-channels.json")
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private var loaded: List<ChannelIdentity>? = null
    private var retryAfterMillis: Long = 0
    private var index: Map<String, List<ChannelIdentity>> = emptyMap()
    private var byId: Map<String, ChannelIdentity> = emptyMap()
    private val maxBytes = 15 * 1024 * 1024
    private val refreshMillis = TimeUnit.DAYS.toMillis(1)

    suspend fun identify(channel: Channel): ChannelIdentity? {
        val manuallyChosen = channel.name // Caller resolves manual override from Room separately.
        return identify(manuallyChosen)
    }

    suspend fun identify(name: String): ChannelIdentity? {
        ensureLoaded()
        val normalized = ChannelNameNormalizer.normalize(name)
        return AliasKeys.preferred(index[AliasKeys.key(normalized.baseName)].orEmpty(),
            normalized.region)
    }

    /** Read persisted identity metadata without starting a network request while opening My TV. */
    suspend fun cachedIdentity(id: String): ChannelIdentity? = withContext(Dispatchers.IO) {
        if (loaded == null && cache.isFile && cache.length() <= maxBytes) {
            runCatching { parse(cache.readText()) }.getOrNull()?.let(::install)
        }
        byId[id]
    }

    suspend fun byCanonicalId(id: String): ChannelIdentity? {
        ensureLoaded()
        return byId[id]
    }

    /** Names likely to refer to one canonical broadcaster, not every channel owned by a network. */
    suspend fun aliasesFor(name: String): List<String> {
        ensureLoaded()
        val choices = index[AliasKeys.key(name)].orEmpty().distinctBy { it.id }.take(8)
        return (listOf(name) + choices.flatMap { listOf(it.name) + it.aliases.take(8) })
            .filter { it.isNotBlank() }.distinct().take(30)
    }

    /**
     * Strict, region-bound identity for Find: the cleaned name (or an alias) must equal one identity's
     * key in [country]. Short keys and ambiguous matches give null rather than a guess.
     */
    suspend fun strictIn(name: String, country: String): ChannelIdentity? {
        ensureLoaded()
        val key = AliasKeys.key(name)
        if (key.length < 3) return null
        val matches = index[key].orEmpty().filter { it.country == country }.distinctBy { it.id }
        return matches.singleOrNull()
    }

    // ---- Logos (iptv-org logos.json, ~6 MB, cached a day) -------------------------------------
    private val logoCache = File(context.applicationContext.cacheDir, "jasktv-iptvorg-logos.json")
    @Volatile private var logos: Map<String, String>? = null

    /** A channel's own logo by IPTV-org id: in use, not feed-specific, raster (SVG can't be drawn), widest first. */
    fun logoFor(id: String): String? = logos?.get(id)

    /** Logos from disk only, or with [network] also downloading when missing or older than a day. */
    suspend fun warmLogos(network: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (logos != null) return@withContext true
        val fresh = logoCache.isFile && System.currentTimeMillis() - logoCache.lastModified() < refreshMillis
        val text = (if (network && !fresh) runCatching {
            http.newCall(Request.Builder().url("https://iptv-org.github.io/api/logos.json").header("Accept", "application/json").build())
                .execute().use { r -> if (!r.isSuccessful) null else r.body?.charStream()?.readText()?.takeIf { it.length <= maxBytes } }
        }.getOrNull()?.also { runCatching { logoCache.writeText(it) } } else null)
            ?: logoCache.takeIf { it.isFile && it.length() <= maxBytes }?.let { runCatching { it.readText() }.getOrNull() }
            ?: return@withContext false
        logos = runCatching { parseLogos(text) }.getOrNull()
        logos != null
    }

    private fun parseLogos(text: String): Map<String, String> {
        val data = JSONArray(text)
        val best = HashMap<String, Pair<Int, String>>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val channel = item.optString("channel").takeIf(String::isNotBlank) ?: continue
            val url = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            if (item.optString("format").equals("SVG", true) || url.endsWith(".svg", true)) continue
            if (!item.optBoolean("in_use", true)) continue
            // Feed-specific logos ("+1", regional) rank below the channel's own.
            val score = item.optInt("width", 0) - if (item.optString("feed").let { it.isNotBlank() && it != "null" }) 10_000 else 0
            if ((best[channel]?.first ?: Int.MIN_VALUE) < score) best[channel] = score to url
        }
        return best.mapValues { it.value.second }
    }

    /** Loads the catalogue, downloading it if missing or older than a day. */
    suspend fun warm(): Boolean = runCatching { ensureLoaded(); loaded != null }.getOrDefault(false)

    /** In memory or on disk only: never touches the network (Find's first paint must not wait on it). */
    suspend fun warmFromDisk(): Boolean = withContext(Dispatchers.IO) {
        if (loaded == null && cache.isFile && cache.length() <= maxBytes) {
            runCatching { parse(cache.readText()) }.getOrNull()?.let(::install)
        }
        loaded != null
    }

    suspend fun idsFor(name: String): Set<String> {
        ensureLoaded()
        return index[AliasKeys.key(name)].orEmpty().map { it.id }.toSet()
    }

    suspend fun suggestions(query: String, limit: Int = 25): List<ChannelIdentity> {
        ensureLoaded()
        val k = AliasKeys.key(query)
        if (k.length < 2) return emptyList()
        val exact = index[k].orEmpty()
        val partial = loaded.orEmpty().asSequence().filter { identity ->
            AliasKeys.key(identity.name).contains(k) ||
                identity.aliases.any { AliasKeys.key(it).contains(k) }
        }.take(limit * 4).toList()
        return (exact + partial).distinctBy { it.id }.take(limit)
    }

    @Synchronized
    private fun install(value: List<ChannelIdentity>) {
        loaded = value
        byId = value.associateBy { it.id }
        val map = mutableMapOf<String, MutableList<ChannelIdentity>>()
        value.forEach { identity ->
            (listOf(identity.name) + identity.aliases).forEach { name ->
                val k = AliasKeys.key(name)
                if (k.isNotEmpty()) map.getOrPut(k) { mutableListOf() }.add(identity)
            }
        }
        index = map
    }

    private suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (loaded != null) return@withContext
        synchronized(this@IptvOrgCatalog) {
            if (loaded != null || System.currentTimeMillis() < retryAfterMillis) return@synchronized
            val cached = if (cache.isFile) runCatching {
                cache.readText().takeIf { it.length <= maxBytes }
            }.getOrNull() else null
            val fresh = if (System.currentTimeMillis() - cache.lastModified() < refreshMillis)
                cached else null
            val data = fresh ?: fetch() ?: cached
            val decoded = data?.let { runCatching { parse(it) }.getOrNull() }
            if (decoded == null) {
                // Offline at startup is common. Retry later instead of persisting an empty map
                // for the entire process lifetime.
                retryAfterMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)
            } else install(decoded)
        }
    }

    private fun fetch(): String? {
        val request = Request.Builder()
            .url("https://iptv-org.github.io/api/channels.json")
            .header("Accept", "application/json").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val text = response.body?.charStream()?.readText() ?: return null
                if (text.length > maxBytes) return null
                cache.writeText(text)
                text
            }
        }.getOrNull()
    }

    private fun parse(text: String): List<ChannelIdentity> {
        val data = JSONArray(text)
        val result = ArrayList<ChannelIdentity>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (item.optString("closed").isNotBlank() &&
                item.optString("closed") != "null") continue
            val id = item.optString("id")
            val name = item.optString("name")
            if (id.isBlank() || name.isBlank()) continue
            val alt = item.optJSONArray("alt_names") ?: JSONArray()
            val aliases = (0 until alt.length()).mapNotNull { j ->
                alt.optString(j).takeIf { it.isNotBlank() }
            }
            val cats = item.optJSONArray("categories") ?: JSONArray()
            // IPTV-org files the UK as "UK"; everything else in the app (AliasKeys.country) uses GB.
            val country = item.optString("country").let { if (it == "UK") "GB" else it }
            result += ChannelIdentity(id, name, country, aliases,
                item.optString("network").takeIf { it.isNotBlank() && it != "null" },
                (0 until cats.length()).mapNotNull { j -> cats.optString(j).takeIf { it.isNotBlank() } })
        }
        return result
    }
}
