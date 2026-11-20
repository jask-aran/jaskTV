package app.opentv.livetv.find

import android.content.Context
import app.opentv.core.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A guide programme on one of the provider's channels: a followed sport, or a matchup ("BLACKCAPS v India"). */
data class GuideAiring(val channel: FindChannel, val sport: String?, val title: String, val startUtc: Long, val endUtc: Long,
    /** Highlights, classics, previews, magazine shows: about the sport, not a game being played. */
    val replay: Boolean)

/**
 * What the guide says is on the provider's channels for the next day, kept to sport programmes and matchups.
 * This is how a game on a general sports channel is found when neither the channel's name nor an event slot
 * says so (Sky Sport 1 NZ showing "BLACKCAPS v India", Fox Sports 506 showing "Formula 1").
 *
 * A programme counts for a channel only through the provider's own guide id for that channel (or the user's
 * manual guide choice), never a name guess: the provider's guide and its channel list come from one place.
 * Reloaded at most every five minutes.
 */
object SportsGuide {
    /** Guide words per sport (title or category). Sports whose games are always written as a matchup need none. */
    val WORDS: Map<String, Regex> = mapOf(
        "cricket" to Regex("""cricket|\bashes\b|\btest\b.{0,24}\bday\b|\bday \d\b.{0,24}\btest\b|\bW?BBL\b|big bash|\bIPL\b|\bPSL\b|\bCPL\b|\bSA20\b|\bILT20\b|\bT20|\bT10\b|\bODI\b|one[- ]day int|sheffield shield|ranji trophy|the hundred|blackcaps|white ferns|proteas|windies""", RegexOption.IGNORE_CASE),
        "f1" to Regex("""formula 1|formula one|\bF1\b(?!\s*academy)""", RegexOption.IGNORE_CASE),
        "motogp" to Regex("""moto ?gp|\bmoto[23]\b""", RegexOption.IGNORE_CASE),
        "indycar" to Regex("""indy ?car|indy 500""", RegexOption.IGNORE_CASE),
        "nascar" to Regex("""nascar""", RegexOption.IGNORE_CASE),
        "nba" to Regex("""\bNBA\b""", RegexOption.IGNORE_CASE),
        "nfl" to Regex("""\bNFL\b""", RegexOption.IGNORE_CASE),
        "nhl" to Regex("""\bNHL\b""", RegexOption.IGNORE_CASE),
        "mlb" to Regex("""\bMLB\b""", RegexOption.IGNORE_CASE),
        "afl" to Regex("""\bAFLW?\b""", RegexOption.IGNORE_CASE),
        "nrl" to Regex("""\bNRLW?\b|state of origin""", RegexOption.IGNORE_CASE),
        "ufc" to Regex("""\bUFC\b""", RegexOption.IGNORE_CASE),
    )
    private val REPLAY = Regex("""highlights|\bhlts\b|classic|reload|replay|rewind|review|best of|in 30|preview|magazine|news|podcast|documentary|greatest|countdown|\bshow\b|talk|academy|\(R\)""", RegexOption.IGNORE_CASE)
    private val MATCHUP = Regex("""\S\s+(v|vs\.?)\s+\S""", RegexOption.IGNORE_CASE)

    @Volatile private var cache: Triple<LiveFindIndex, Long, List<GuideAiring>>? = null

    suspend fun load(context: Context, index: LiveFindIndex, now: Long = System.currentTimeMillis()): List<GuideAiring> = withContext(Dispatchers.IO) {
        cache?.takeIf { it.first === index && now - it.second < 5 * 60_000 }?.let { return@withContext it.third }
        val result = runCatching { read(context, index, now) }.getOrDefault(emptyList())
        cache = Triple(index, now, result)
        result
    }

    private fun read(context: Context, index: LiveFindIndex, now: Long): List<GuideAiring> {
        val db = ServiceLocator.get(context).database.openHelper.readableDatabase
        val byStream = HashMap<Long, FindChannel>(index.channels.size * 2)
        index.channels.forEach { c -> c.streamIds.forEach { byStream[it] = c } }
        val byGuideId = HashMap<String, MutableSet<FindChannel>>()
        db.query("SELECT id, epgChannelId, epgOverrideId FROM channels WHERE (epgChannelId IS NOT NULL AND epgChannelId != '') OR (epgOverrideId IS NOT NULL AND epgOverrideId != '')").use { c ->
            while (c.moveToNext()) {
                val channel = byStream[c.getLong(0)] ?: continue
                val id = c.getString(2)?.takeIf(String::isNotBlank) ?: c.getString(1) ?: continue
                byGuideId.getOrPut(id) { HashSet() } += channel
            }
        }
        // The guide matcher's pairing, when a guide lists this exact channel name (the network's guide lists every
        // stream it was matched to by name, so local stations and the rest get their games found too).
        db.query("SELECT c.id, c.matchedEpgId FROM channels c JOIN epg_channels a ON a.epgId = c.matchedEpgId AND a.normalizedKey = c.groupKey WHERE c.matchedEpgId IS NOT NULL").use { c ->
            while (c.moveToNext()) {
                val channel = byStream[c.getLong(0)] ?: continue
                byGuideId.getOrPut(c.getString(1)) { HashSet() } += channel
            }
        }
        // A card's real-channel identity is a guide id too (providers use IPTV-org's ids), for copies with none.
        index.channels.forEach { c -> c.identity?.let { byGuideId.getOrPut(it) { HashSet() } += c } }
        if (byGuideId.isEmpty()) return emptyList()
        val out = ArrayList<GuideAiring>()
        // The next day: what's on now, and what's on later for the games the schedules list.
        db.query("SELECT epgChannelId, startUtcMillis, endUtcMillis, title, category FROM programmes WHERE endUtcMillis > ? AND startUtcMillis < ?",
            arrayOf<Any>(now, now + 24 * 3_600_000L)).use { c ->
            while (c.moveToNext()) {
                val channels = byGuideId[c.getString(0)] ?: continue
                val title = c.getString(3) ?: continue
                val text = title + " " + c.getString(4).orEmpty()
                val sport = WORDS.entries.firstOrNull { it.value.containsMatchIn(text) }?.key
                if (sport == null && !MATCHUP.containsMatchIn(title)) continue
                val replay = REPLAY.containsMatchIn(title)
                channels.forEach { out += GuideAiring(it, sport, title.trim(), c.getLong(1), c.getLong(2), replay) }
            }
        }
        // Several guides can list one channel: one airing per channel, time and title.
        return out.distinctBy { Triple(it.channel.key, it.startUtc, it.title.lowercase()) }
    }
}
