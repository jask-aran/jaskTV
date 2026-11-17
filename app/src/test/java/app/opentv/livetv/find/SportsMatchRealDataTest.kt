package app.opentv.livetv.find

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Local only: matches saved ESPN samples against the private provider list in the ignored .local folder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SportsMatchRealDataTest {
    @Test fun matchSamples() {
        val local = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, ".local") }.firstOrNull { File(it, "get_live_streams.json").isFile }
        assumeTrue(local != null && File(local, "sports-samples").isDirectory)
        val json = kotlinx.serialization.json.Json
        fun arr(f: File) = json.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonArray
        fun kotlinx.serialization.json.JsonElement.s(k: String) =
            ((this as kotlinx.serialization.json.JsonObject)[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val cats = arr(File(local, "get_live_categories.json")).associate { it.s("category_id") to it.s("category_name") }
        val slots = ArrayList<SlotStream>(); val channels = ArrayList<FindChannel>()
        arr(File(local, "get_live_streams.json")).forEachIndexed { i, it ->
            val name = it.s("name").orEmpty(); val cat = cats[it.s("category_id")]
            val c = LiveClassifier.classify(name, cat)
            if (c.kind == LiveKind.EVENT_SLOT) slots += SlotStream(i.toLong(), name, c.region, cat, null)
            if (c.kind == LiveKind.CHANNEL) channels += FindChannel(c.region + ":" + name, LiveFinder.cleanName(name), null, c.region, c.service, c.genre, listOf(i.toLong()), null)
        }
        val finder = SportsFinder(LiveFindIndex(channels, emptyList(), slots, emptyMap()))
        val now = java.time.Instant.parse("2026-11-17T18:00:00Z").toEpochMilli()
        val samples = File(local, "sports-samples")
        val events = SportsSchedule.parseScoreboard(Sports.byKey("nba")!!, File(samples, "espn_basketball_nba.json").readText()) +
            SportsSchedule.parseScoreboard(Sports.byKey("nhl")!!, File(samples, "espn_hockey_nhl.json").readText()).take(4) +
            SportsSchedule.parseScoreboard(Sports.byKey("ncaab")!!, File(samples, "espn_basketball_mens-college-basketball.json").readText()).take(4) +
            SportsSchedule.parseCricket(File(samples, "espn_cricket2.json").readText()).filter { it.startUtc > now - 86_400_000 } +
            SportEvent("f1:lv:race", "f1", "Formula 1", "Las Vegas Grand Prix · Race", emptyList(),
                java.time.Instant.parse("2026-11-22T04:00:00Z").toEpochMilli(), null, false, false, emptyList(),
                listOf(listOf("Las Vegas", "Las Vegas Grand Prix"), listOf("race")))
        val regions = setOf("AU", "US", "GB", "IN")
        val t0 = System.currentTimeMillis()
        val cards = finder.cards(events, regions, now)
        println("matched ${cards.size} events in ${System.currentTimeMillis() - t0} ms")
        cards.forEach { card ->
            println("${card.event.sport} finished=${card.event.finished} live=${EventMatcher.isLive(card.event, now)} | ${card.event.title} | ${java.time.Instant.ofEpochMilli(card.event.startUtc)} | bc=${card.event.broadcasters}")
            card.options.forEach { println("      ${it.state} ${it.evidence} | ${it.title} | ${it.region} | ${it.streamIds.size} streams | e.g. " + slots.firstOrNull { s -> s.id == it.streamIds.first() }?.name?.take(90)) }
        }
        println("F1 channels: " + finder.sportChannels("f1", regions).map { it.name + "/" + it.region })
        println("cricket channels: " + finder.sportChannels("cricket", regions).map { it.name + "/" + it.region })
    }
}
