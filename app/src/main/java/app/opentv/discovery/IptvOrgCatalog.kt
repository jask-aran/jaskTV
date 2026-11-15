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
            if (loaded != null) return@synchronized
            val cached = if (cache.isFile) runCatching {
                cache.readText().takeIf { it.length <= maxBytes }
            }.getOrNull() else null
            val fresh = if (System.currentTimeMillis() - cache.lastModified() < refreshMillis)
                cached else null
            val data = fresh ?: fetch() ?: cached
            install(runCatching { parse(data ?: "[]") }.getOrDefault(emptyList()))
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
            result += ChannelIdentity(id, name, item.optString("country"), aliases,
                item.optString("network").takeIf { it.isNotBlank() && it != "null" })
        }
        return result
    }
}
