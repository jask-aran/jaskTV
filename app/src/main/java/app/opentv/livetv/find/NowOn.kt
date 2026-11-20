package app.opentv.livetv.find

import android.content.Context
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Programme
import app.opentv.livetv.GuideEvidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * What is on now for Find's channel cards, from the guide. Only trusted guide links count (a manual
 * mapping, or an exact name match: [GuideEvidence]); a wrong programme is worse than none.
 * Results are kept for a minute per card.
 */
class NowOn(context: Context) {
    private val db = ServiceLocator.get(context).database

    suspend fun lookup(channels: List<FindChannel>, now: Long = System.currentTimeMillis()): Map<String, Programme> = withContext(Dispatchers.IO) {
        val fresh = channels.filter { (checked[it.key] ?: 0) < now - 60_000 }.take(60)
        if (fresh.isNotEmpty()) {
            // Every copy of a card counts (its best copy often has no guide id when another does), and so does the
            // card's real-channel identity: providers use IPTV-org's ids for their guides, so it names the guide channel.
            val rows = db.channels().byIds(fresh.flatMap { it.streamIds.take(8) }).associateBy { it.id }
            val candidateIds = rows.values.flatMap { listOfNotNull(it.epgOverrideId, it.epgChannelId, it.matchedEpgId) }.distinct()
            val aliases = if (candidateIds.isEmpty()) emptyList() else db.epgAliases().forIds(candidateIds)
            val idsByCard = fresh.associate { card ->
                card.key to (card.streamIds.take(8).flatMap { id -> rows[id]?.let { GuideEvidence.ids(it, aliases) }.orEmpty() } +
                    listOfNotNull(card.identity)).distinct()
            }
            val programmes = idsByCard.values.flatten().distinct().takeIf { it.isNotEmpty() }
                ?.let { db.programmes().nowOn(it, now) }.orEmpty().groupBy { it.epgChannelId }
            for ((key, ids) in idsByCard) {
                checked[key] = now
                val programme = ids.firstNotNullOfOrNull { programmes[it]?.firstOrNull() }
                if (programme != null) found[key] = programme else found.remove(key)
            }
        }
        channels.mapNotNull { c -> found[c.key]?.takeIf { it.endUtcMillis > now }?.let { c.key to it } }.toMap()
    }

    companion object {
        private val checked = ConcurrentHashMap<String, Long>()
        private val found = ConcurrentHashMap<String, Programme>()
    }
}
