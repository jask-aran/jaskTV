package app.opentv.livetv.find

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/** Local only: parses public schedule samples saved in the ignored .local/sports-samples folder (2026-11-17). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SportsScheduleParseTest {
    private val samples = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, ".local/sports-samples") }
        .firstOrNull { File(it, "motogp_2026.json").isFile }
    private fun text(name: String): String { assumeTrue(samples != null); return File(samples, name).readText() }
    private val now = Instant.parse("2026-11-17T13:40:00Z").toEpochMilli()

    @Test fun motoGpWeekendIsOneMeetingOfSessions() {
        val portugal = SportsSchedule.parseMotoGp(text("motogp_2026.json")).filter { it.title.startsWith("Portugal") }
        println(portugal.joinToString("\n") { "${it.title} ${Instant.ofEpochMilli(it.startUtc)}" })
        assertEquals(listOf("Practice 1", "Practice", "Practice 2", "Qualifying 1", "Qualifying 2", "Sprint", "Race"), portugal.map { it.session })
        assertEquals(1, portugal.map { it.meeting }.distinct().size)
        assertEquals(Instant.parse("2026-11-22T13:00:00Z").toEpochMilli(), portugal.last().startUtc)
        assertTrue(portugal.count { it.endUtc != null && it.endUtc!! > it.startUtc } >= 5)
        println(portugal.map { it.endUtc })
    }

    @Test fun racingSessionsFromEspn() {
        val f1 = SportsSchedule.parseRacing(Sports.byKey("f1")!!, text("espn_racing_f1.json"))
        println(f1.joinToString("\n") { "${it.title} ${it.session} ${Instant.ofEpochMilli(it.startUtc)}" })
        assertEquals(listOf("FP1", "FP2", "FP3", "Qual", "Race"), f1.map { it.session })
        val nascar = SportsSchedule.parseRacing(Sports.byKey("nascar")!!, text("espn_racing_nascar.json"))
        assertEquals(1, nascar.size)
        assertTrue(nascar.single().competitors.isEmpty()) // drivers are not sides
        assertTrue(nascar.single().wordGroups.flatten().any { it == "Miami" })
        assertTrue(nascar.single().finished)
        assertEquals(1, SportsSchedule.parseRacing(Sports.byKey("indycar")!!, text("espn_racing_irl.json")).size)
    }

    @Test fun testMatchAtStumpsIsNotLiveAndShowsItsNextDay() {
        val all = SportsSchedule.parseCricket(text("espn_cricket3.json"), now)
        val test = all.first { it.title == "South Africa v Bangladesh" }
        println(all.filter { it.meeting != null }.joinToString("\n") { "${it.title} · ${it.session} live=${it.live} ${Instant.ofEpochMilli(it.startUtc)}" })
        assertFalse(test.live)
        assertEquals("1st Test · Day 4", test.session)
        assertTrue(test.startUtc > now)
        assertEquals(null, all.first { it.session?.startsWith("Qualifier 1") == true }.meeting)
        assertEquals(null, all.first { it.title == "West Indies A v England Lions" }.meeting) // one-day: no "Day 1"
        // Stumps on the last scheduled day: nothing more to come.
        assertTrue(all.first { it.title == "Afghanistan v Zimbabwe" }.let { it.finished && it.session == "2nd Test · Day 5" })
    }

    @Test fun oneCalendarShapeForEverySport() {
        val zone = java.time.ZoneOffset.UTC
        val cricket = SportCalendar.build(SportsSchedule.parseCricket(text("espn_cricket3.json"), now).map { EventCard(it, emptyList()) }, now, SportShape.SERIES)
        val test = cricket.events.first { it.title == "South Africa v Bangladesh" }
        assertEquals(listOf("1st Test · Day 1", "1st Test · Day 2", "1st Test · Day 3", "1st Test · Day 4", "1st Test · Day 5"), test.parts.map { it.label })
        assertEquals(listOf(true, true, true, false, false), test.parts.map { it.done })
        assertTrue(test.multiDay(zone))
        assertTrue(cricket.series.any { it.name == "Quaid-e-Azam Trophy Gold" && it.events.size >= 2 })
        val f1 = SportCalendar.build(SportsSchedule.parseRacing(Sports.byKey("f1")!!, text("espn_racing_f1.json")).map { EventCard(it, emptyList()) }, now, SportShape.MEETING)
        assertEquals(1, f1.events.size)
        assertEquals(5, f1.events.single().parts.size)
        assertTrue(f1.spans(zone).single() is CalEvent)
    }
}
