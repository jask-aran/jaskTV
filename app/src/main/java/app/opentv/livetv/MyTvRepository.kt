package app.opentv.livetv

import android.content.Context
import androidx.room.withTransaction
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Room-backed saved identities; resolves only their bounded stream groups, never all channels. */
class MyTvRepository(context: Context) {
    private val graph = ServiceLocator.get(context)
    private val db = graph.database
    data class Entry(val saved: SavedTvChannel, val variants: List<Channel>) {
        val primary: Channel? get() = variants.firstOrNull {
            it.sourceId == saved.preferredSourceId && it.streamId == saved.preferredStreamId
        } ?: variants.firstOrNull()
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val saved = graph.settings.activeProfileId.flatMapLatest { db.savedTv().observe(it) }

    suspend fun resolve(saved: SavedTvChannel): Entry = withContext(Dispatchers.IO) {
        val preferred = if (saved.preferredSourceId != null && saved.preferredStreamId != null)
            db.channels().byProviderKey(saved.preferredSourceId, saved.preferredStreamId) else null
        val mapped = if (saved.identity.startsWith("iptv:"))
            db.channels().myTvMappedVariants(saved.identity.removePrefix("iptv:")) else emptyList()
        val canonical = saved.identity.takeIf { it.startsWith("iptv:") }
            ?.removePrefix("iptv:")?.let { graph.iptvOrgCatalog.cachedIdentity(it) }
        val inferred = if (preferred == null && canonical != null) (listOf(canonical.name) + canonical.aliases).take(8)
            .flatMap { name -> db.channels().myTvVariants(app.opentv.data.parser.ChannelNameNormalizer.normalize(name).groupKey) }
            else emptyList()
        val group = preferred?.groupKey?.takeIf(String::isNotBlank)
            ?: saved.identity.removePrefix("group:").substringAfter(":").takeIf { saved.identity.startsWith("group:") }
        val enabledSources = db.sources().all().filter { it.enabled }.mapTo(hashSetOf()) { it.id }
        val variants = (mapped + inferred + if (group != null) db.channels().myTvVariants(group) else listOfNotNull(preferred))
            .distinctBy { it.sourceId to it.streamId }.filterNot { it.hidden || it.sourceId !in enabledSources }
            .filter { candidate ->
                val expectedRegion = preferred?.let { LiveChannelKeys.region(it.name) }
                    ?: saved.identity.removePrefix("group:").substringBefore(":").takeIf { saved.identity.startsWith("group:") }
                    ?: canonical?.country?.uppercase()?.let { if (it == "UK") "GB" else it }
                val override = db.channelAliases().find(candidate.sourceId, candidate.streamId)
                if (override != null) saved.identity == "iptv:${override.iptvOrgId}"
                else expectedRegion == null || LiveChannelKeys.region(candidate.name) == expectedRegion
            }.sortedByDescending { it.qualityRank }
        Entry(saved, variants)
    }

    suspend fun add(channel: Channel) = saveMutex.withLock {
        withContext(Dispatchers.IO) {
            val profile = graph.settings.activeProfileId.value
            val manual = db.channelAliases().find(channel.sourceId, channel.streamId)
            val canonical = manual?.iptvOrgId ?: try { graph.iptvOrgCatalog.identify(channel)?.id }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            val identity = canonical?.let { "iptv:$it" } ?: LiveChannelKeys.provisional(channel)
            val existing = db.savedTv().observe(profile).first()
            val previous = existing.firstOrNull { it.identity == identity || it.identity == LiveChannelKeys.provisional(channel) }
            if (previous != null && previous.identity != identity && identity.startsWith("iptv:")) {
                db.withTransaction {
                    db.savedTv().remove(profile, previous.identity)
                    db.savedTv().save(previous.copy(identity = identity))
                }
            } else if (previous == null) db.savedTv().save(SavedTvChannel(profile, identity,
                manual?.label?.takeIf(String::isNotBlank) ?: channel.shownName,
                channel.sourceId, channel.streamId, (existing.maxOfOrNull { it.sortIndex } ?: -1) + 1))
        }
    }
    suspend fun applyManualMapping(saved: SavedTvChannel, channel: Channel) {
        val manual = db.channelAliases().find(channel.sourceId, channel.streamId) ?: return
        val updated = saved.copy(identity = "iptv:${manual.iptvOrgId}", label = manual.label.takeIf(String::isNotBlank) ?: channel.shownName)
        db.withTransaction {
            db.savedTv().remove(saved.profileId, saved.identity)
            db.savedTv().save(updated)
        }
    }
    suspend fun remove(saved: SavedTvChannel) = db.savedTv().remove(saved.profileId, saved.identity)
    suspend fun prefer(saved: SavedTvChannel, channel: Channel) = db.savedTv().save(saved.copy(
        preferredSourceId = channel.sourceId, preferredStreamId = channel.streamId))
    suspend fun move(entries: List<SavedTvChannel>, index: Int, delta: Int) {
        val target = index + delta
        if (target !in entries.indices) return
        val ordered = entries.toMutableList().apply { add(target, removeAt(index)) }
        db.withTransaction { ordered.forEachIndexed { i, item -> db.savedTv().save(item.copy(sortIndex = i)) } }
    }
    companion object { private val saveMutex = Mutex() }
}
