package app.opentv.livetv.find

import app.opentv.data.db.LiveFindRow
import app.opentv.discovery.IptvOrgCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Local only: the Find index over the private provider list with a cached IPTV-org catalogue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FindIndexRealDataTest {
    @Test fun index() = runBlocking {
        val local = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, ".local") }.firstOrNull { File(it, "get_live_streams.json").isFile }
        assumeTrue(local != null && File(local, "sports-samples/iptvorg-channels.json").isFile)
        val context = RuntimeEnvironment.getApplication()
        File(local, "sports-samples/iptvorg-channels.json").copyTo(File(context.cacheDir, "jasktv-iptvorg-channels.json"), overwrite = true)
        val catalog = IptvOrgCatalog(context)
        check(catalog.warmFromDisk())
        val json = kotlinx.serialization.json.Json
        fun arr(f: File) = json.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonArray
        fun kotlinx.serialization.json.JsonElement.s(k: String) =
            ((this as kotlinx.serialization.json.JsonObject)[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val cats = arr(File(local, "get_live_categories.json")).associate { it.s("category_id") to it.s("category_name") }
        val rows = arr(File(local, "get_live_streams.json")).mapIndexed { i, it ->
            val name = it.s("name").orEmpty()
            LiveFindRow(i.toLong(), 1, name, app.opentv.data.parser.ChannelNameNormalizer.normalize(name).groupKey, 0, it.s("category_id"), null, cats[it.s("category_id")])
        }
        val t0 = System.currentTimeMillis()
        val index = LiveFinder.buildIndex(rows, { name, country -> catalog.strictIn(name, country) })
        println("built in ${System.currentTimeMillis() - t0} ms: ${index.channels.size} channels, ${index.loops.size} loops, ${index.slots.size} slots")
        val mine = index.channels.filter { it.region in setOf("AU", "US", "GB", "IN") }
        println("mine ${mine.size}; identified ${mine.count { it.identity != null }}; by genre " + mine.groupingBy { it.genre }.eachCount())
        println("US news: " + mine.filter { it.region == "US" && it.genre == LiveGenre.NEWS }.map { it.name })
        println("AU news: " + mine.filter { it.region == "AU" && it.genre == LiveGenre.NEWS }.map { it.name })
        println("IN sports: " + mine.filter { it.region == "IN" && it.genre == LiveGenre.SPORTS }.map { it.name })
        println("TV9 cards: " + mine.filter { it.name.contains("TV9", true) }.map { it.name + "/" + it.streamIds.size })
        val dupKeys = (index.channels + index.loops).groupingBy { it.key }.eachCount().filterValues { it > 1 }
        println("keys shared by a channel and a loop: ${dupKeys.size}")
        // The saved copy a fresh launch reads instead of rebuilding.
        val t1 = System.currentTimeMillis()
        LiveSnapshot.saveIndex(context, 42L, index)
        val t2 = System.currentTimeMillis()
        val (signature, back) = checkNotNull(LiveSnapshot.loadIndex(context))
        println("saved in ${t2 - t1} ms (${File(context.filesDir, "jask-live-index.json").length() / 1024} KB), loaded in ${System.currentTimeMillis() - t2} ms")
        check(signature == 42L && back.channels == index.channels && back.loops == index.loops && back.slots == index.slots && back.regionCounts == index.regionCounts)
        val finder = SportsFinder(index)
        println("cricket channels: " + finder.sportChannels("cricket", setOf("AU", "US", "GB", "IN")).map { it.name + "/" + it.region })
        val cards = finder.cards(SportsSchedule.parseCricket(File(local, "sports-samples/espn_cricket3.json").readText()), setOf("AU", "US", "GB", "IN"))
        LiveSnapshot.saveSports(context, setOf("cricket"), setOf("AU"), cards, emptyMap(), System.currentTimeMillis())
        check(LiveSnapshot.loadSports(context, setOf("cricket"), setOf("AU"), System.currentTimeMillis())?.first == cards)
        check(LiveSnapshot.loadSports(context, setOf("nba"), setOf("AU"), System.currentTimeMillis()) == null) // other sports: rebuilt
    }
}
