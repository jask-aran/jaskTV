package app.opentv

import app.opentv.livetv.*
import app.opentv.data.model.Programme
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class F1DiscoveryTest {
    @Test fun `schedule requires UTC time and keeps unknown ends absent`() {
        val text = """{"MRData":{"RaceTable":{"Races":[{"season":"2026","round":"1","raceName":"Test Grand Prix","date":"2026-11-20","time":"10:00:00Z","Qualifying":{"date":"2026-11-19","time":"09:00:00Z"},"FirstPractice":{"date":"2026-11-18"}}]}}}"""
        val result = F1Schedule.parse(text)
        assertThat(result.map { it.session }).containsExactly("Qualifying", "Race")
        assertThat(result.first().startUtcMillis).isEqualTo(java.time.Instant.parse("2026-11-19T09:00:00Z").toEpochMilli())
        assertThat(result.first().scheduledEndUtcMillis).isNull()
    }
    @Test fun `guide evidence rejects replays other sessions and unrelated time windows`() {
        val session = F1Session("test", "Test Grand Prix", "Qualifying", 10_000_000)
        fun listing(title: String, start: Long = 10_000_000) = Programme(feedId = 1, epgChannelId = "f1", title = title,
            startUtcMillis = start, endUtcMillis = start + 3_600_000)
        assertThat(F1Discovery.matches(session, listing("F1 Qualifying Live"))).isTrue()
        for (title in listOf("F1 Sprint Qualifying", "F1 Qualifying Highlights", "F2 Qualifying", "Formula 1 Practice 3"))
            assertThat(F1Discovery.matches(session, listing(title))).isFalse()
        assertThat(F1Discovery.matches(session, listing("F1 Qualifying", 20_000_000))).isFalse()
    }
}
