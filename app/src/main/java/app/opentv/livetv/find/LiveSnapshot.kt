package app.opentv.livetv.find

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Live TV's last results, kept in the app's private storage so a fresh launch shows them at once instead of
 * re-sorting the provider's channels and re-checking sports first. Both are checked again in the background and
 * replaced only when they differ. Holds provider channel names and stream ids, like the database; never leaves
 * the device.
 */
object LiveSnapshot {
    private val json = Json { ignoreUnknownKeys = true }
    private const val VERSION = 1

    @Serializable private class StoredIndex(val version: Int, val signature: Long, val channels: List<FindChannel>, val loops: List<FindChannel>,
        val slots: List<SlotStream>, val regionCounts: Map<String, Int>)

    @Serializable private class StoredSports(val version: Int, val savedAt: Long, val followed: Set<String>, val regions: Set<String>,
        val cards: List<EventCard>, val sportChannels: Map<String, List<FindChannel>>)

    private fun indexFile(context: Context) = File(context.filesDir, "jask-live-index.json")
    private fun sportsFile(context: Context) = File(context.filesDir, "jask-live-sports.json")

    fun loadIndex(context: Context): Pair<Long, LiveFindIndex>? = runCatching {
        val stored = json.decodeFromString<StoredIndex>(indexFile(context).takeIf { it.isFile }?.readText() ?: return null)
        if (stored.version != VERSION) return null
        stored.signature to LiveFindIndex(stored.channels, stored.loops, stored.slots, stored.regionCounts)
    }.getOrNull()

    fun saveIndex(context: Context, signature: Long, index: LiveFindIndex) {
        write(indexFile(context), json.encodeToString(StoredIndex(VERSION, signature, index.channels, index.loops, index.slots, index.regionCounts)))
    }

    /** The last sports cards for these sports and regions. Live flags older than 20 minutes are dropped: the clock decides until the refresh. */
    fun loadSports(context: Context, followed: Set<String>, regions: Set<String>, now: Long): Pair<List<EventCard>, Map<String, List<FindChannel>>>? = runCatching {
        val stored = json.decodeFromString<StoredSports>(sportsFile(context).takeIf { it.isFile }?.readText() ?: return null)
        if (stored.version != VERSION || stored.followed != followed || stored.regions != regions) return null
        val stale = now - stored.savedAt > 20 * 60_000
        val cards = stored.cards.map { c ->
            val event = if (stale && c.event.live) c.event.copy(live = false) else c.event
            c.copy(event = EventMatcher.settle(event, now))
        }
        cards to stored.sportChannels
    }.getOrNull()

    fun saveSports(context: Context, followed: Set<String>, regions: Set<String>, cards: List<EventCard>, sportChannels: Map<String, List<FindChannel>>, now: Long) {
        write(sportsFile(context), json.encodeToString(StoredSports(VERSION, now, followed, regions, cards, sportChannels)))
    }

    /** Written to a temporary file then renamed, so a crash mid-write never leaves half a file. */
    private fun write(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
