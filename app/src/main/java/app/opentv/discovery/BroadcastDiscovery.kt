/*
 * jaskTV — GPL-3.0-or-later. Derived from OpenTV Mobile.
 * Live discovery joins local XMLTV listings with advisory external broadcaster metadata.
 */
package app.opentv.discovery

import android.content.Context
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A result is a candidate, not proof that the IPTV stream currently carries the event. */
class BroadcastDiscovery(context: Context) {
    data class Hit(val channel: Channel, val explanation: String, val level: Level) {
        enum class Level { GUIDE_LISTING, USER_ALIAS, EVENT_BROADCASTER, BROADCASTER_HINT }
    }

    private val graph = ServiceLocator.get(context)
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    // Keep free API usage far below its request quota while re-using results across searches.
    private val cache = mutableMapOf<String, Pair<Long, List<Pair<String, String>>>>()
    private val cacheMillis = 15 * 60_000L

    suspend fun lookup(text: String, includeExternal: Boolean = false): List<Hit> = withContext(Dispatchers.IO) {
        val query = text.trim()
        if (query.length < 2) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val programmes = graph.database.programmes()
            .searchTitle(query, now - 60 * 60_000L, now + 36 * 60 * 60_000L, 150)
        val listings = programmes.groupBy { it.epgChannelId }
        val channels = if (listings.isEmpty()) emptyList()
            else graph.database.channels().matchingEpgIds(listings.keys.toList())
        val found = linkedMapOf<Long, Hit>()
        channels.forEach { channel ->
            val programme = channel.epgCandidates.flatMap { listings[it].orEmpty() }
                .sortedWith(compareByDescending<Programme> { it.isLiveAt(now) }
                    .thenBy { it.startUtcMillis })
                .firstOrNull() ?: return@forEach
            val whenText = if (programme.isLiveAt(now)) "On now (EPG)" else "Scheduled (EPG)"
            found[channel.id] = Hit(channel, "$whenText · ${programme.title}",
                Hit.Level.GUIDE_LISTING)
        }

        // Look up exact channel identities independent of sports schedules, so searching
        // "Sky F1" can find "Sky Sports F1" even if the provider's EPG is empty.
        val knownIds = runCatching { graph.iptvOrgCatalog.idsFor(query) }
            .getOrDefault(emptySet())
        val knownNames = runCatching { graph.iptvOrgCatalog.aliasesFor(query) }
            .getOrDefault(listOf(query))
        val userMaps = graph.database.channelAliases().observeAll().first()
        userMaps.filter { mapping ->
            mapping.iptvOrgId in knownIds ||
                (mapping.label.isNotBlank() && mapping.label.contains(query, ignoreCase = true))
        }.take(60).forEach { mapping ->
            val channel = graph.database.channels().byProviderKey(mapping.sourceId, mapping.streamId)
            if (channel != null && !channel.hidden && channel.id !in found) {
                found[channel.id] = Hit(channel,
                    "Your channel mapping · ${mapping.iptvOrgId}", Hit.Level.USER_ALIAS)
            }
        }
        if (knownIds.isNotEmpty()) {
            knownNames.take(12).forEach { alias ->
                graph.catalogRepository.searchChannels(alias).first().asSequence().filter {
                    AliasKeys.key(it.shownName) == AliasKeys.key(alias) ||
                        AliasKeys.key(it.name) == AliasKeys.key(alias)
                }.take(35).forEach { channel ->
                    if (channel.id !in found) {
                        found[channel.id] = Hit(channel,
                            "Matched channel identity · IPTV-org", Hit.Level.USER_ALIAS)
                    }
                }
            }
        }

        // TheSportsDB is advisory. One free event result and two free broadcaster results may
        // be returned. An empty response is common, especially for generic sport queries.
        val external = if (includeExternal && query.length >= 3) runCatching {
            val key = query.lowercase(Locale.ROOT)
            val existing = synchronized(cache) { cache[key] }
            if (existing != null && now - existing.first < cacheMillis) existing.second
            else sportsDbBroadcasts(query).also { result ->
                synchronized(cache) { cache[key] = now to result }
            }
        }.getOrDefault(emptyList()) else emptyList()

        val broadcasters = if (external.isNotEmpty()) external
            else broadHints(query).map { it to "" }
        val manualOverrides = graph.database.channelAliases().observeAll().first()
        broadcasters.take(8).forEach { (name, country) ->
            if (name.length < 3) return@forEach
            // IPTV-org supplies canonical names and alt_names, not streams. Search only our own
            // configured playlist for the aliases, keeping a manual mapping higher priority.
            val aliasNames = runCatching { graph.iptvOrgCatalog.aliasesFor(name) }
                .getOrDefault(listOf(name))
            val canonicalIds = runCatching { graph.iptvOrgCatalog.idsFor(name) }
                .getOrDefault(emptySet())
            val candidates = linkedMapOf<Long, Channel>()
            aliasNames.take(20).forEach { alias ->
                graph.catalogRepository.searchChannels(alias).first().asSequence()
                    .filter { channel ->
                        BroadcasterMatcher.matches(channel.shownName, alias) ||
                            BroadcasterMatcher.matches(channel.name, alias) ||
                            AliasKeys.key(channel.shownName) == AliasKeys.key(alias)
                    }.take(30).forEach { channel -> candidates[channel.id] = channel }
            }
            manualOverrides.filter { it.iptvOrgId in canonicalIds }.take(50).forEach { mapping ->
                graph.database.channels().byProviderKey(mapping.sourceId, mapping.streamId)
                    ?.takeUnless { it.hidden }?.let { candidates[it.id] = it }
            }
            candidates.values.forEach { channel ->
                if (channel.id !in found) {
                    val manual = manualOverrides.any { mapping ->
                        mapping.sourceId == channel.sourceId && mapping.streamId == channel.streamId &&
                            mapping.iptvOrgId in canonicalIds
                    }
                    val explanation = when {
                        manual -> "Your channel mapping · $name (broadcast unverified)"
                        external.isNotEmpty() -> "External event listing · $name${if (country.isNotBlank()) " ($country)" else ""}"
                        else -> "Possible broadcaster · $name"
                    }
                    found[channel.id] = Hit(channel, explanation,
                        if (external.isNotEmpty()) Hit.Level.EVENT_BROADCASTER
                        else Hit.Level.BROADCASTER_HINT)
                }
            }
        }
        found.values.sortedWith(compareBy<Hit> { it.level.ordinal }
            .thenByDescending { it.channel.favourite }
            .thenByDescending { it.channel.qualityRank }
            .thenBy { it.channel.shownName }).take(100)
    }

    private fun sportsDbBroadcasts(query: String): List<Pair<String, String>> {
        val response = request("searchevents.php", "e", query) ?: return emptyList()
        val events = response.optJSONArray("event") ?: response.optJSONArray("events")
            ?: return emptyList()
        val today = LocalDate.now(ZoneOffset.UTC)
        val event = (0 until events.length()).asSequence().mapNotNull(events::optJSONObject)
            .firstOrNull { e ->
                val date = runCatching { LocalDate.parse(e.optString("dateEvent")) }.getOrNull()
                    ?: return@firstOrNull false
                ChronoUnit.DAYS.between(today, date) in -1L..7L
            } ?: return emptyList()
        val id = event.optString("idEvent").takeIf(String::isNotBlank) ?: return emptyList()
        val responseTv = request("lookuptv.php", "id", id) ?: return emptyList()
        // TheSportsDB uses the singular "tvevent" for the array (not "tvevents").
        val television = responseTv.optJSONArray("tvevent")
            ?: responseTv.optJSONArray("tvevents")
            ?: responseTv.optJSONArray("tv") ?: return emptyList()
        return (0 until television.length()).mapNotNull { index ->
            val row = television.optJSONObject(index) ?: return@mapNotNull null
            val name = row.optString("strChannel").ifBlank {
                row.optString("strTVChannel")
            }
            if (name.isBlank()) null else name to row.optString("strCountry")
        }.distinctBy { it.first.lowercase(Locale.ROOT) }
    }

    private fun request(endpoint: String, key: String, value: String): JSONObject? {
        val url = "https://www.thesportsdb.com/api/v1/json/123/$endpoint".toHttpUrl()
            .newBuilder().addQueryParameter(key, value).build()
        return http.newCall(Request.Builder().url(url).get().build()).execute().use {
            response -> if (!response.isSuccessful) null else response.body?.string()?.let(::JSONObject)
        }
    }

    /** Explicitly uncertain rights-holder hints, used only when event-specific listings fail. */
    private fun broadHints(query: String): List<String> {
        val q = query.lowercase(Locale.ROOT)
        return when {
            listOf("f1", "formula 1", "grand prix", "qualifying").any(q::contains) ->
                listOf("Sky Sports F1", "Fox Sports", "ESPN")
            listOf("nba", "basketball", "lakers", "warriors", "celtics", "knicks").any(q::contains) ->
                listOf("NBA TV", "ESPN", "ABC", "NBC", "Sky Sports NBA")
            else -> emptyList()
        }
    }
}
