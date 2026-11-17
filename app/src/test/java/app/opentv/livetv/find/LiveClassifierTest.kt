package app.opentv.livetv.find

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LiveClassifierTest {
    private fun c(name: String, category: String) = LiveClassifier.classify(name, category)

    @Test fun kindsRegionsAndServices() {
        assertEquals(LiveKind.HEADER, c("##### SPORT ᴴᴰ #####", "US| SPORT ᴴᴰ").kind)
        assertEquals(LiveKind.LOOP, c("24/7: EXAMPLE SHOW", "US| 24/7 COMEDY ᴿᴬᵂ").kind)
        assertEquals(LiveKind.EVENT_SLOT, c("US (SVC+ 12) | League: Alpha vs. Beta (2026-01-02 19:00:10)", "US| SVC+ PPV").kind)
        assertEquals(LiveKind.EVENT_SLOT, c("- NO EVENT STREAMING - | US: SVC PPV 210", "US| SVC PPV ⱽᴵᴾ").kind)
        // A team channel filed in a PPV category is still a channel.
        assertEquals(LiveKind.CHANNEL, c("US: EXAMPLE TEAM NETWORK ᴿᴬᵂ", "US| TEAM PPV").kind)
        assertEquals("GB", c("UK: EXAMPLE ONE HD", "UK| GENERAL ᴴᴰ").region)
        assertEquals("IN", c("HINDI: EXAMPLE NEWS", "ASIA| HINDI").region)
        assertEquals("PK", c("PK: EXAMPLE TV", "ASIA| PAKISTAN").region)
        assertEquals("FR", c("FR: EXAMPLE 8K", "8K| CINEMA").region)
        assertEquals("Prime Video", c("PRIME: EXAMPLE ᴿᴬᵂ", "US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ").service)
        assertEquals(LiveGenre.LOCAL, c("US: NBC 4 (WXYZ) EXAMPLE CITY HD", "US| NEWS ᴴᴰ").genre)
        assertEquals(LiveGenre.NEWS, c("UK: EXAMPLE NEWS HD", "UK| NEWS ᴴᴰ/ᴿᴬᵂ").genre)
        assertEquals("EXAMPLE NEWS LIVE", LiveFinder.cleanName("TUBI: EXAMPLE NEWS LIVE ᴿᴬᵂ"))
    }

    /** Local only: the provider list lives in the ignored .local folder and never in the repository. */
    @Test fun realInventorySummary() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, ".local/get_live_streams.json") }.firstOrNull { it.isFile }
        assumeTrue(file != null)
        val json = kotlinx.serialization.json.Json
        fun arr(f: File) = json.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonArray
        fun kotlinx.serialization.json.JsonElement.s(k: String) =
            ((this as kotlinx.serialization.json.JsonObject)[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val cats = arr(File(file!!.parentFile, "get_live_categories.json")).associate { it.s("category_id") to it.s("category_name") }
        val classes = arr(file).map { LiveClassifier.classify(it.s("name").orEmpty(), cats[it.s("category_id")]) to it.s("name").orEmpty() }
        println("kinds " + classes.groupingBy { it.first.kind }.eachCount())
        val chosen = setOf("AU", "US", "GB", "IN")
        val mine = classes.filter { it.first.kind == LiveKind.CHANNEL && it.first.region in chosen }
        println("channels in AU/US/GB/IN " + mine.groupingBy { it.first.region }.eachCount())
        println("genres " + mine.groupingBy { it.first.genre }.eachCount())
        println("regions top " + classes.groupingBy { it.first.region }.eachCount().entries.sortedByDescending { it.value }.take(30))
        val slotStates = classes.filter { it.first.kind == LiveKind.EVENT_SLOT }.map { EventMatcher.slotState(it.second, null) to it.second }
        println("slot states " + slotStates.groupingBy { it.first }.eachCount())
        slotStates.filter { it.first == SlotState.NAMED }.shuffled(java.util.Random(4)).take(12).forEach { println("NAMED sample: " + it.second.take(100)) }
        slotStates.filter { it.first == SlotState.REPLAY }.shuffled(java.util.Random(4)).take(6).forEach { println("REPLAY sample: " + it.second.take(100)) }
        classes.filter { it.first.kind == LiveKind.CHANNEL && it.first.region == "IN" }.shuffled(java.util.Random(2)).take(12).forEach { println("IN sample: " + it.second) }
    }
}

class EventMatcherTest {
    private val day = java.time.Instant.parse("2026-11-18T01:00:00Z").toEpochMilli()
    private val game = SportEvent("x", "nba", "NBA", "Alpha Owls at Beta Foxes",
        listOf(Competitor(EventMatcher.variants("Alpha Owls", "Owls", "Alpha", "ALP")), Competitor(EventMatcher.variants("Beta Foxes", "Foxes", "Beta", "BET"))),
        day, null, false, false, emptyList())

    @Test fun bothSidesAndDateRequired() {
        val now = day - 6 * EventMatcher.HOUR
        org.junit.Assert.assertTrue(EventMatcher.matches("US (SVC 005) | Owls vs. Foxes (2026-11-17 20:00:10)", game, now))
        // "Nov 19:00" is a time, not 19 November: this slot is the day before's listing.
        org.junit.Assert.assertFalse(EventMatcher.matches("NEXT | OWLS VS FOXES | Mon 15 Nov 19:00 EST (US)", game, now))
        org.junit.Assert.assertFalse(EventMatcher.matches("US (SVC 006) | Owls vs. Bears (2026-11-17 20:00:10)", game, now))
        org.junit.Assert.assertFalse(EventMatcher.matches("End | Owls vs. Foxes | all | 17-11-2026 | 00:00 (GMT)", game, now))
        org.junit.Assert.assertTrue(EventMatcher.matches("Owls vs Foxes @ Nov 17 8:00 PM :SVC  04", game, now))
    }
}

class SlotStateTest {
    @Test fun states() {
        assertEquals(SlotState.IDLE, EventMatcher.slotState("- NO EVENT STREAMING - | 8K EXCLUSIVE | US: SVC PPV 210", "US| SVC PPV"))
        assertEquals(SlotState.IDLE, EventMatcher.slotState(":SVC SE  15", "SE| SVC PPV"))
        assertEquals(SlotState.IDLE, EventMatcher.slotState("US (SVC+ 556) |  (2098-12-31 08:01:04)", "US| SVC+ PPV"))
        assertEquals(SlotState.IDLE, EventMatcher.slotState("Svc Event 08 :", "XX| SVC PPV"))
        assertEquals(SlotState.IDLE, EventMatcher.slotState("(JM) SVC PLAY 89 (D)", "US| SVC PLAY PPV"))
        assertEquals(SlotState.IDLE, EventMatcher.slotState("UK: SVC EVENT 12", "UK| SVC PPV"))
        assertEquals(SlotState.NAMED, EventMatcher.slotState("svcwrestling: Example Holiday Classic (Mat 2) (2026-11-27 10:00:00)", "US| SVC PPV"))
        assertEquals(SlotState.REPLAY, EventMatcher.slotState("svccheer: 2026 REBROADCAST: Example Spectacular (2026-12-06 04:50:00)", "US| SVC PPV"))
        assertEquals(SlotState.ENDED, EventMatcher.slotState("ENDED | ALPHA VS. BETA | Sun 15 Nov 14:00 EST (US)", "US| SVC PPV"))
        assertEquals(SlotState.REPLAY, EventMatcher.slotState("Svc Owls in 30: Foxes vs. Owls A @ Nov 16 12:30 :SVC+  32", "CA| SVC+ PPV"))
        assertEquals(SlotState.REPLAY, EventMatcher.slotState("Alpha vs Beta", "US| SOCCER REPLAY"))
        assertEquals(SlotState.UPCOMING, EventMatcher.slotState("NEXT | PLACE: RACE | Sat 21 Nov 21:50 EST (US)", "US| SVC F1 PPV"))
        assertEquals(SlotState.LIVE, EventMatcher.slotState("LIVE | ALPHA VS. BETA | Mon 16 Nov 15:30 EST (US)", "US| SVC PPV"))
        assertEquals(SlotState.NAMED, EventMatcher.slotState("US (SVC 005) | Owls vs. Foxes (2026-11-17 19:00:10)", "US| SVC PPV"))
    }
}

class AbbreviationCollisionTest {
    @Test fun codesAloneNeverMatch() {
        val start = java.time.Instant.parse("2026-11-18T00:30:00Z").toEpochMilli()
        // An NBA game, as the schedule parser builds names now (no city, no code).
        val nba = SportEvent("n", "nba", "NBA", "Alpha Owls at Beta Foxes",
            listOf(Competitor(EventMatcher.variants("Alpha Owls", "Owls")), Competitor(EventMatcher.variants("Beta Foxes", "Foxes"))),
            start, null, false, false, emptyList())
        // Same day, another sport, same city codes.
        org.junit.Assert.assertFalse(EventMatcher.matches("US (SVC+ 241) | Hockey: ALP vs. BET (2026-11-17 18:30:40)", nba, start))
        org.junit.Assert.assertFalse(EventMatcher.matches("US (SVC+ 241) | Hockey: Alpha vs. Beta (2026-11-17 18:30:40)", nba, start))
        org.junit.Assert.assertTrue(EventMatcher.matches("US (SVC 005) | Owls vs. Foxes (2026-11-17 19:30:10)", nba, start))
    }
}
