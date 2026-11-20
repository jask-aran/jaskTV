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
            // Names are in the signature, and so is how cards are made (bump INDEX_RULES when that changes).
            val signature = rows.fold(rows.size.toLong() + INDEX_RULES) { acc, row -> acc * 31 + row.id * 17 + row.name.hashCode() + (row.epgChannelId?.hashCode() ?: 0) } +
                protectedKeys.hashCode()
            // The index saved by an earlier launch counts too: if the channels haven't changed, nothing is rebuilt.
            if (cached == null) storedIndex()
            cached?.takeIf { it.first == signature }?.let { identitiesReady = true; return@withLock it.second }
            identitiesReady = catalog.warmFromDisk()
            catalog.warmLogos(network = false)
            val lookup: (suspend (String, String) -> app.opentv.discovery.ChannelIdentity?)? =
                if (identitiesReady) { name, country -> catalog.strictIn(name, country) } else null
            val byGuide: (suspend (String) -> app.opentv.discovery.ChannelIdentity?)? =
                if (identitiesReady) { id -> catalog.byGuideId(id) } else null
            buildIndex(rows.filterNot { isProtected(it, protectedKeys) }, lookup, catalog::logoFor, byGuide)
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
    suspend fun refreshSlotNames(categories: Set<Pair<Long, String>>, every: Long = 15 * 60_000L): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val due = categories.filter { now - (slotRefreshedAt[it] ?: 0) > every }.take(12)
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

    /**
     * Keeps event-slot names current while Live TV or Sports is open (call it every few minutes). From jasktv-core when
     * one answers: its changes since this device's last copy, one small request (core re-reads the slots itself).
     * Otherwise straight from the provider: the slot categories of [categories], at most every 5 minutes each.
     * Returns whether any name changed (rebuild then). Never retries or re-runs itself.
     */
    suspend fun keepSlotsFresh(categories: Set<Pair<Long, String>>): Boolean = withContext(Dispatchers.IO) {
        var fromCore = false
        var changed = false
        for (source in graph.database.sources().enabled()) {
            val n = runCatching { graph.catalogRepository.liveChangesFromCore(source) }.getOrNull() ?: continue
            fromCore = true
            if (n > 0) { changed = true; android.util.Log.i("jaskLive", "Slot names from core: $n changed") }
        }
        if (fromCore) return@withContext changed
        refreshSlotNames(categories, every = 5 * 60_000L) || changed
    }

    /**
     * Of [ids], the ones that still show [event] right now. Event slots ("US| SOCCER PPV 3") are renamed as one event
     * ends and the next is scheduled, so a slot matched earlier can carry another game by now: their names are
     * re-read from the provider first (no 15-minute wait). Ordinary channels (not in a slot category) are kept.
     */
    suspend fun stillShowing(ids: List<Long>, event: SportEvent): List<Long> = withContext(Dispatchers.IO) {
        val dao = graph.database.channels()
        val rows = dao.byIds(ids.take(20))
        val categories = rows.mapNotNull { r -> r.categoryId?.let { r.sourceId to it } }.toSet()
        categories.forEach { slotRefreshedAt.remove(it) }
        runCatching { refreshSlotNames(categories) }
        val fresh = dao.byIds(ids.take(20)).associateBy { it.id }
        val now = System.currentTimeMillis()
        ids.filter { id -> fresh[id]?.let { EventMatcher.matchesFolded(it.name, EventMatcher.fold(it.name), event, now) } == true }
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
        /** Version of the rules below: part of the index signature, so a saved index made by older rules is rebuilt. */
        private const val INDEX_RULES = 3L

        /** A US or Canadian local station: a network's name and the station's call sign ("NBC WSTM Syracuse"). */
        private val NETWORK = Regex("""\b(NBC|CBS|FOX|ABC|CW|PBS|TELEMUNDO|UNIVISION|UNIMAS|MYNETWORK|MY ?TV|ION|CTV|GLOBAL|CITY|CBC)\b""")
        private val CALL_SIGN = Regex("""\b([KW][A-Z]{2,3}|C[A-Z]{3})\b""")
        private val NOT_CALL = setOf("WEST", "WILD", "KIDS", "WWE", "WNBA", "WIDE", "WEEK", "WAVE", "WIRE", "WINE", "WISE", "WEB", "KING",
            "CITY", "CBS", "CBC", "CTV", "CITV", "CNN", "CNBC", "CMT", "CW")
        /** "WSTM" from "NBC WSTM Syracuse NY D": only with a network named too, so "KIDS" or "WEST" isn't taken. */
        fun callSign(name: String): String? {
            val upper = ChannelNameNormalizer.foldSuperscripts(name).uppercase()
            if (!NETWORK.containsMatchIn(upper)) return null
            return CALL_SIGN.findAll(upper).map { it.groupValues[1] }.firstOrNull { it !in NOT_CALL }
        }

        /** Timeshifted copies ("+1", "West", "Pacific") carry the main channel's guide id but aren't it. */
        private val TIMESHIFT = Regex("""\+\s?\d\b|\bWEST\b|\bPACIFIC\b""", RegexOption.IGNORE_CASE)

        suspend fun buildIndex(rows: List<LiveFindRow>, identity: (suspend (String, String) -> app.opentv.discovery.ChannelIdentity?)?,
            logoOf: (String) -> String? = { null },
            /** The real channel a provider guide id names (see IptvOrgCatalog.byGuideId); null: names only. */
            byGuide: (suspend (String) -> app.opentv.discovery.ChannelIdentity?)? = null): LiveFindIndex {
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
                        // A local station is one card whatever each package calls it ("NBC WSTM Syracuse NY D",
                        // "RK: NBC 3 Syracuse (WSTM)"): by its call sign.
                        val call = if (cls.region == "US" || cls.region == "CA") callSign(row.name) else null
                        // Language feeds of one brand stay apart; names with no Latin letters never merge.
                        val key = when {
                            call != null && cls.kind == LiveKind.CHANNEL -> "${cls.region}:call:$call"
                            name.isEmpty() -> "${cls.region}:id${row.id}"
                            else -> cls.region + ":" + (languageTag(row.name)?.let { "$it:" } ?: "") + name
                        }
                        (if (cls.kind == LiveKind.LOOP) loops else channels).getOrPut(key) { mutableListOf() } += row to cls
                    }
                }
            }
            // Two provider spellings of one identified channel ("C-SPAN", "C-SPAN 1") become one card.
            val found = channels.map { (key, members) -> collapse(key, members, identity, logoOf, byGuide) }
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
            identityOf: (suspend (String, String) -> app.opentv.discovery.ChannelIdentity?)?, logoOf: (String) -> String? = { null },
            byGuide: (suspend (String) -> app.opentv.discovery.ChannelIdentity?)? = null): FindChannel {
            val sorted = members.sortedByDescending { it.first.qualityRank }
            val (best, cls) = sorted.first()
            // A local station's copies are named every which way ("DALLAS TX KXAS NBC 5 A3 MIAMI city"): its card
            // takes the plainest, the shortest name outside the provider's "city" listings.
            val name = if (":call:" in key) members.map { cleanName(it.first.name) }
                .sortedWith(compareBy<String> { Regex("""\bCITY$""", RegexOption.IGNORE_CASE).containsMatchIn(it) }.thenBy { it.length }).first()
                else cleanName(best.name)
            // The provider's guide id names the real channel outright: used when the copies agree on one channel of
            // this region (or the region is a mixed one). Timeshifted feeds keep to their own name. Else the name.
            val guided = if (byGuide == null || TIMESHIFT.containsMatchIn(best.name)) null else
                members.mapNotNull { it.first.epgChannelId?.takeIf(String::isNotBlank) }.distinct()
                    .mapNotNull { byGuide(it) }.distinctBy { it.id }
                    .filter { it.country == cls.region || cls.region.length != 2 }.singleOrNull()
            val identity = guided ?: identityOf?.invoke(name, cls.region)
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
