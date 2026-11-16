package app.opentv.livetv

import android.content.Context
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.discovery.BroadcastDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Evidence is per session and available playlist channel, never global streaming rights. */
class F1Discovery(context: Context) {
    enum class Evidence(val label: String) {
        GUIDE_LISTED("Guide-listed"), EXPECTED_BROADCASTER("Expected broadcaster"), POSSIBLE_CHANNEL("Possible channel")
    }
    data class Candidate(val channel: Channel, val evidence: Evidence, val explanation: String)
    private val graph = ServiceLocator.get(context)
    private val advisory = BroadcastDiscovery(context)
    private var advisoryCache: Pair<Long, List<BroadcastDiscovery.Hit>>? = null

    suspend fun candidates(session: F1Session): List<Candidate> = withContext(Dispatchers.IO) {
        val listings = graph.database.programmes().f1Window(session.startUtcMillis - 60 * 60_000,
            session.searchWindowEnd).filter { matches(session, it) }
        val found = mutableListOf<Candidate>()
        if (listings.isNotEmpty()) {
            val channels = graph.database.channels().matchingEpgIds(listings.map { it.epgChannelId }.distinct())
            val aliases = channels.flatMap { it.epgCandidates }.distinct().chunked(900)
                .flatMap { graph.database.epgAliases().forIds(it) }
            channels.forEach { channel ->
                val trusted = GuideEvidence.ids(channel, aliases)
                val programme = listings.firstOrNull { it.epgChannelId in trusted }
                if (programme != null) found += Candidate(channel, Evidence.GUIDE_LISTED, programme.title)
            }
        }
        // Existing IPTV-org/manual/broadcaster discovery provides candidates. Downgrade every
        // broad association here: it has not established rights to this particular session.
        val now = System.currentTimeMillis()
        val possible = advisoryCache?.takeIf { now - it.first < 15 * 60_000 }?.second
            ?: advisory.lookup("Formula 1", includeExternal = true).also { advisoryCache = now to it }
        possible.take(60).forEach { hit ->
            found += Candidate(hit.channel, Evidence.POSSIBLE_CHANNEL, "Broadcaster identity only · coverage unverified")
        }
        found.sortedWith(compareBy<Candidate> { it.evidence.ordinal }.thenByDescending { it.channel.qualityRank })
            .distinctBy { LiveChannelKeys.provisional(it.channel) }.take(25)
    }
    companion object {
        fun matches(session: F1Session, programme: Programme): Boolean {
            if (programme.startUtcMillis < session.startUtcMillis - 60 * 60_000 ||
                programme.startUtcMillis > session.startUtcMillis + 60 * 60_000 ||
                programme.endUtcMillis <= session.startUtcMillis) return false
            val title = programme.title.lowercase()
            if (!F1Schedule.recognizes(title) || Regex("\\b(f2|f3|replay|highlights|repeat)\\b").containsMatchIn(title)) return false
            return when (session.session) {
                "Sprint qualifying" -> "sprint" in title && ("qualifying" in title || "shootout" in title)
                "Qualifying" -> "qualifying" in title && "sprint" !in title
                "Sprint" -> "sprint" in title && "qualifying" !in title && "shootout" !in title
                "Practice 1" -> Regex("practice\\s*1|fp1").containsMatchIn(title)
                "Practice 2" -> Regex("practice\\s*2|fp2").containsMatchIn(title)
                "Practice 3" -> Regex("practice\\s*3|fp3").containsMatchIn(title)
                "Race" -> ("race" in title || "grand prix" in title) && listOf("practice", "qualifying", "sprint", "preview").none { it in title }
                else -> false
            }
        }
    }
}
