package app.opentv.livetv.find

import android.content.Context
import app.opentv.core.ServiceLocator
import app.opentv.data.db.LiveFindRow
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.discovery.IptvOrgCatalog
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One real channel as Find shows it: quality variants and duplicate copies collapsed. */
@kotlinx.serialization.Serializable
data class FindChannel(
    /** `region:groupKey`; stable across refreshes while the provider keeps the name. */
    val key: String,
    val name: String,
    val logoUrl: String?,
    val region: String,
    val service: String?,
    val genre: LiveGenre,
    /** Stream ids, best quality first. */
    val streamIds: List<Long>,
    /** IPTV-org id when strictly matched in the same region. */
    val identity: String?,
    /** The best variant's provider category, superscripts folded ("WT| WORLD CRICKET"). */
    val category: String = "",
)

/** A provider event slot: renamed per event, never saved to My TV. */
@kotlinx.serialization.Serializable
data class SlotStream(val id: Long, val name: String, val region: String, val categoryName: String?, val logoUrl: String?,
    val sourceId: Long = 0, val categoryId: String? = null)

class LiveFindIndex(
    val channels: List<FindChannel>,
    val loops: List<FindChannel>,
    val slots: List<SlotStream>,
    /** Channels per region (after collapsing), for the region picker. */
    val regionCounts: Map<String, Int>,
)

/**
 * Builds Live TV's Find index from the local catalogue. [index] never waits on the network: it uses
 * the IPTV-org catalogue only when it is in memory or on disk. [prepareIdentities] may download it.
 */
class LiveFinder(context: Context) {
    private val graph = ServiceLocator.get(context)
    private val appContext = context.applicationContext
    // One catalogue for the process: parsing its 8 MB on every visit made Find re-sort each time it opened.
    private val catalog = sharedCatalog ?: synchronized(Companion) { sharedCatalog ?: IptvOrgCatalog(context.applicationContext).also { sharedCatalog = it } }

    /** The last index built in this process, shown at once while [index] checks it is still current. */
    fun lastIndex(): LiveFindIndex? = cached?.second

    /** Whether identities (and so IPTV-org genres) were available for the last [index]. */
    @Volatile var identitiesReady = false
        private set

    suspend fun index(protectedKeys: Set<String>): LiveFindIndex = withContext(Dispatchers.Default) {
        mutex.withLock {
            val rows = withContext(Dispatchers.IO) { graph.database.channels().liveFindRows() }
            // Names are in the signature: a sync that renames event slots must rebuild.
            val signature = rows.fold(rows.size.toLong()) { acc, row -> acc * 31 + row.id * 17 + row.name.hashCode() } + protectedKeys.hashCode()
            // The index saved by an earlier launch counts too: if the channels haven't changed, nothing is rebuilt.
            if (cached == null) storedIndex()
            cached?.takeIf { it.first == signature }?.let { identitiesReady = true; return@withLock it.second }
            identitiesReady = catalog.warmFromDisk()
            catalog.warmLogos(network = false)
            val lookup: (suspend (String, String) -> app.opentv.discovery.ChannelIdentity?)? =
                if (identitiesReady) { name, country -> catalog.strictIn(name, country) } else null
            buildIndex(rows.filterNot { isProtected(it, protectedKeys) }, lookup, catalog::logoFor)
                .also { built ->
                    if (identitiesReady) {
                        cached = signature to built
                        withContext(Dispatchers.IO) { LiveSnapshot.saveIndex(appContext, signature, built) }
                    }
                }
        }
    }

    /**
     * The index saved by the last launch, read once per process (about a second's work instead of a rebuild).
     * Shown straight away; [index] then checks it against the channels and rebuilds only if they changed.
     */
    suspend fun storedIndex(): LiveFindIndex? = cached?.second ?: diskMutex.withLock {
        cached?.second ?: if (diskTried) null else withContext(Dispatchers.IO) {
            diskTried = true
            LiveSnapshot.loadIndex(appContext)?.also { (signature, index) -> if (cached == null) cached = signature to index }?.second
        }
    }

    /**
     * Re-reads the names of a few event-slot categories from the provider (one small request each)
     * and renames the stored slots. At most every 15 minutes per category. Returns whether any name changed.
     */
    suspend fun refreshSlotNames(categories: Set<Pair<Long, String>>): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val due = categories.filter { now - (slotRefreshedAt[it] ?: 0) > 15 * 60_000 }.take(12)
        var changed = false
        val dao = graph.database.channels()
        for ((sourceId, categoryId) in due) {
            slotRefreshedAt[sourceId to categoryId] = now
            val source = graph.database.sources().byId(sourceId) ?: continue
            if (!source.enabled || source.kind != app.opentv.data.model.SourceKind.XTREAM) continue
            val names = runCatching { graph.xtreamApi.liveNamesInCategory(source, categoryId) }.getOrNull() ?: continue
            val stored = dao.namesInCategory(sourceId, categoryId).associate { it.streamId to it.name }
            // Only streams we already have whose name changed: one transaction, nothing inserted or deleted.
            val renamed = names.filter { (id, name) -> stored[id] != null && stored[id] != name }
            if (renamed.isNotEmpty()) {
                graph.database.withTransaction {
                    renamed.forEach { (id, name) -> dao.rename(sourceId, id, name, ChannelNameNormalizer.normalize(name).baseName) }
                }
                changed = true
            }
        }
        changed
    }

    /** Downloads the IPTV-org catalogue if it is missing or stale (about 8 MB, daily at most). */
    suspend fun prepareIdentities(): Boolean = catalog.warm().also { catalog.warmLogos(network = true) }

    companion object {
        private val mutex = Mutex()
        private val diskMutex = Mutex()
        @Volatile private var diskTried = false
        @Volatile private var sharedCatalog: IptvOrgCatalog? = null
        private val slotRefreshedAt = java.util.concurrent.ConcurrentHashMap<Pair<Long, String>, Long>()

        /** Pure index build, shared with tests. [identity] resolves a cleaned name in a country strictly, or is null. */
        suspend fun buildIndex(rows: List<LiveFindRow>, identity: (suspend (String, String) -> app.opentv.discovery.ChannelIdentity?)?,
            logoOf: (String) -> String? = { null }): LiveFindIndex {
            val channels = HashMap<String, MutableList<Pair<LiveFindRow, LiveClass>>>()
            val loops = HashMap<String, MutableList<Pair<LiveFindRow, LiveClass>>>()
            val slots = ArrayList<SlotStream>()
            for (row in rows) {
                val cls = LiveClassifier.classify(row.name, row.categoryName)
                when (cls.kind) {
                    LiveKind.HEADER -> Unit
                    LiveKind.EVENT_SLOT -> slots += SlotStream(row.id, row.name, cls.region, row.categoryName, row.logoUrl, row.sourceId, row.categoryId)
                    LiveKind.CHANNEL, LiveKind.LOOP -> {
                        val name = ChannelNameNormalizer.groupKeyOf(cleanName(row.name))
                        // Language feeds of one brand stay apart; names with no Latin letters never merge.
                        val key = when {
                            name.isEmpty() -> "${cls.region}:id${row.id}"
                            else -> cls.region + ":" + (languageTag(row.name)?.let { "$it:" } ?: "") + name
                        }
                        (if (cls.kind == LiveKind.LOOP) loops else channels).getOrPut(key) { mutableListOf() } += row to cls
                    }
                }
            }
            // Two provider spellings of one identified channel ("C-SPAN", "C-SPAN 1") become one card.
            val found = channels.map { (key, members) -> collapse(key, members, identity, logoOf) }
                .groupBy { channel -> channel.identity?.let { "${channel.region}:$it:${languageTagOf(channel.name)}" } ?: channel.key }
                .map { (_, same) -> if (same.size == 1) same.single() else same.first().copy(streamIds = same.flatMap { it.streamIds }) }
            // A logo shared by many different channels is the bundle's ("AT&T", "Prime Video"), not the channel's.
            val loopCards = loops.map { (key, members) -> collapse(key, members, null) }
            val logoUse = (found + loopCards).mapNotNull { it.logoUrl }.groupingBy { it }.eachCount()
            // Only provider logos are judged: an identity's own logo legitimately repeats across its regional cards.
            fun own(c: FindChannel) = if ((logoUse[c.logoUrl] ?: 0) > 4 && c.identity?.let(logoOf) != c.logoUrl) c.copy(logoUrl = null) else c
            return LiveFindIndex(
                found.map(::own).sortedBy { it.name.lowercase() },
                loopCards.map(::own).sortedBy { it.name.lowercase() },
                slots,
                found.groupingBy { it.region }.eachCount(),
            )
        }

        private suspend fun collapse(key: String, members: List<Pair<LiveFindRow, LiveClass>>,
            identityOf: (suspend (String, String) -> app.opentv.discovery.ChannelIdentity?)?, logoOf: (String) -> String? = { null }): FindChannel {
            val sorted = members.sortedByDescending { it.first.qualityRank }
            val (best, cls) = sorted.first()
            val name = cleanName(best.name)
            val identity = identityOf?.invoke(name, cls.region)
            val language = languageTag(best.name)
            return FindChannel(
                key = key,
                name = (identity?.name ?: displayCase(name)) + (language?.let { " · " + it.lowercase().replaceFirstChar(Char::uppercase) } ?: ""),
                // The identity's own logo beats the provider's, which is often a bundle's or another channel's.
                logoUrl = identity?.id?.let(logoOf) ?: sorted.firstNotNullOfOrNull { it.first.logoUrl?.takeIf(String::isNotBlank) },
                region = cls.region,
                service = sorted.firstNotNullOfOrNull { it.second.service },
                // A call-sign station stays Local even when IPTV-org calls it general.
                genre = if (cls.genre == LiveGenre.LOCAL) cls.genre
                    else identity?.let { LiveClassifier.genreOf(it.categories) } ?: cls.genre.takeUnless { it == LiveGenre.OTHER } ?: LiveClassifier.genreFromName(name),
                streamIds = sorted.map { it.first.id },
                identity = identity?.id,
                category = ChannelNameNormalizer.foldSuperscripts(best.categoryName.orEmpty()),
            )
        }

        private fun languageTagOf(cardName: String): String = cardName.substringAfter(" · ", "")

        private val ACRONYMS = setOf("ESPN", "ESPN2", "ESPNU", "CNBC", "MSNBC", "HGTV", "WWE", "NBA", "NFL", "NHL", "MLB", "UFC", "PBS",
            "QVC", "HSN", "AMC", "TLC", "BET", "CMT", "TCM", "FXX", "TBS", "TNT", "HBO", "MTV", "VH1", "CNN", "ABC", "NBC", "CBS", "BBC",
            "ITV", "SBS", "NHK", "RTE", "TVNZ", "PTC", "ETV", "NDTV", "DD", "WION", "ZEE", "SONY", "STAR", "UK", "USA", "US", "TV", "HD")
        /** Short everyday words that are not acronyms. */
        private val COMMON_WORDS = setOf("THE", "AND", "OF", "FOR", "ONE", "TWO", "NEW", "OLD", "FOX", "SKY", "BAY", "LAS", "LOS",
            "SAN", "ART", "KID", "GO", "MY", "BIG", "TOP", "HIT", "POP", "GEO", "AAJ", "HUM", "WAR", "CAR", "DOG", "PET", "LAW", "AIR", "SEA", "SUN", "BOX")
        private val CALL = Regex("""^[KW][A-Z]{2,3}(-[A-Z0-9]+)?$""")

        /** "BOSTON 25 NEWS" → "Boston 25 News"; acronyms, call signs and mixed-case names are left alone. */
        fun displayCase(name: String): String {
            if (name.any(Char::isLowerCase)) return name
            return name.split(' ').joinToString(" ") { word ->
                val bare = word.trim('(', ')', ',', '.')
                when {
                    bare in COMMON_WORDS -> word.lowercase().replaceFirstChar(Char::uppercase)
                    bare.length <= 3 || bare in ACRONYMS || CALL.matches(bare) || '-' in bare || '+' in bare || bare.any(Char::isDigit) -> word
                    else -> word.lowercase().replaceFirstChar(Char::uppercase)
                }
            }
        }

        private val LANGUAGES = setOf("HINDI", "TAMIL", "TELUGU", "TELEGU", "PUNJABI", "MALAYALAM", "MARATHI", "KANNADA",
            "BENGALI", "GUJARATI", "BHOJPURI", "ODIA", "URDU")

        /** `TELUGU: TV9` → `TELUGU`: the feed's language, when the provider writes it as the name's tag. */
        fun languageTag(raw: String): String? =
            TAG.find(raw)?.groupValues?.get(1)?.uppercase()?.takeIf { it in LANGUAGES }

        @Volatile private var cached: Pair<Long, LiveFindIndex>? = null

        /** A leading service/language tag (`PRIME:`, `AT&T:`, `HINDI:`, `IN-PREM:`) plus the usual cleaning. */
        private val TWO_TAGS = Regex("""^\s*[A-Z]{2}\s+[A-Z]{2}\s*:\s*""")
        private val LEAD_TAG = Regex("""^\s*[A-Za-z0-9&+]{2,8}(?:-[A-Za-z]+)?\s*:\s*""")

        private val BULLETS = Regex("""[◉●•]|\bHDTV\b""", RegexOption.IGNORE_CASE)
        private val TAG = Regex("""^\s*([A-Za-z]+)\s*:""")

        fun cleanName(raw: String): String {
            val folded = ChannelNameNormalizer.foldSuperscripts(raw)
            // Some names stack tags ("CA EN: NBA TV", "UK: SKY: …"): strip up to two.
            val stripped = LEAD_TAG.replace(LEAD_TAG.replace(TWO_TAGS.replace(folded, ""), ""), "").ifBlank { folded }
            return ChannelNameNormalizer.normalize(stripped).baseName.replace(BULLETS, "").trim()
        }

        /** Same rule as the widget: hidden categories stay hidden here too. */
        fun isProtected(row: LiveFindRow, keys: Set<String>): Boolean {
            if (keys.isEmpty() || row.categoryId == null) return false
            val category = row.categoryName ?: return true
            val normalized = ChannelNameNormalizer.normalize(category)
            val legacy = normalized.groupKey.ifEmpty { row.categoryId!! }
            val key = normalized.region?.uppercase()?.let { "$it:$legacy" } ?: legacy
            return key in keys || legacy in keys
        }
    }
}
