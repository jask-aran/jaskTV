package app.opentv.livetv.find

import android.content.Context
import app.opentv.livetv.F1Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Schedules for followed sports. ESPN's site API (unofficial, undocumented) for leagues and cricket,
 * Jolpica (through [F1Schedule]) for Formula 1. Each sport is cached on disk for 20 minutes; an
 * unreachable source falls back to its last copy. Requests carry no user or provider data.
 */
class SportsSchedule(context: Context) {
    private val cacheDir = File(context.applicationContext.cacheDir, "jask-sports").apply { mkdirs() }
    private val f1 = F1Schedule(context.applicationContext)
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun events(sports: Set<String>, now: Long = System.currentTimeMillis()): List<SportEvent> = withContext(Dispatchers.Default) {
        coroutineScope {
            sports.mapNotNull(Sports::byKey).map { sport -> async { runCatching { load(sport, now) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
        }.distinctBy { it.id }
            // A game that started 5+ hours ago with no live state is over. Finished events stay a day for
            // replays; they are never shown as live.
            .map { EventMatcher.settle(it, now) }
            .filter { (it.live || it.startUtc >= now - TimeUnit.DAYS.toMillis(1)) && it.startUtc <= now + TimeUnit.DAYS.toMillis(7) }
            .sortedBy { it.startUtc }
    }

    private suspend fun load(sport: Sport, now: Long): List<SportEvent> = when {
        sport.key == "f1" -> f1.upcoming(now).sessions.map { session ->
            val place = session.grandPrix.replace(Regex("""\s*Grand Prix\s*""", RegexOption.IGNORE_CASE), " ").trim()
            val words = when {
                session.session.startsWith("Practice") -> listOf("practice", "fp" + session.session.last())
                session.session == "Sprint qualifying" -> listOf("sprint qualifying", "sprint shootout")
                else -> listOf(session.session.lowercase())
            }
            // The schedule gives no end: a session's usual length plus a little for delays (more where red flags run long).
            // Its 3-hour search window kept a 1-hour practice "live" for two hours after it ended.
            val minutes = when {
                session.session.startsWith("Practice") -> 70
                session.session.startsWith("Sprint", ignoreCase = true) -> 60
                session.session.startsWith("Quali", ignoreCase = true) -> 80
                else -> 150
            }
            val end = session.scheduledEndUtcMillis ?: (session.startUtcMillis + minutes * 60_000L)
            SportEvent(session.id, "f1", "Formula 1", "${session.grandPrix} · ${session.session}", emptyList(),
                session.startUtcMillis, end, live = session.startUtcMillis <= now && now < end,
                // Over once past its end: the timeline then ticks it off and marks the following session Next.
                finished = now >= end, broadcasters = emptyList(), wordGroups = listOf(listOf(place, session.grandPrix), words),
                leagueLogo = "https://a.espncdn.com/i/teamlogos/leagues/500/f1.png",
                meeting = "f1:" + session.grandPrix + ":" + Instant.ofEpochMilli(session.startUtcMillis).atZone(ZoneOffset.UTC).year,
                session = session.session)
        }
        sport.key == "cricket" -> parseCricket(fetch("cricket", "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=cricket", now, 20), now)
        sport.key == "motogp" -> {
            val year = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).year
            parseMotoGp(fetch("motogp-$year", "https://api.pulselive.motogp.com/motogp/v1/events?seasonYear=$year", now, 360))
        }
        // Racing: the undated scoreboard holds the current or next meeting, every session of it.
        sport.shape == SportShape.MEETING && sport.espnPath != null ->
            parseRacing(sport, fetch(sport.key, "https://site.api.espn.com/apis/site/v2/sports/${sport.espnPath}/scoreboard", now, 20))
        sport.espnPath != null -> {
            // ESPN answers 400 to date ranges (checked 2026-11-17), so one request per day.
            val day = DateTimeFormatter.BASIC_ISO_DATE
            val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC)
            (-1L..3L).flatMap { offset ->
                val date = today.plusDays(offset).format(day)
                val url = "https://site.api.espn.com/apis/site/v2/sports/${sport.espnPath}/scoreboard?dates=$date"
                runCatching { parseScoreboard(sport, fetch("${sport.key}-$date", url, now, if (offset <= 0) 20 else 360)) }.getOrDefault(emptyList())
            }
        }
        else -> emptyList()
    }

    private suspend fun fetch(key: String, url: String, now: Long, freshMinutes: Long): String = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$key.json")
        if (file.isFile && now - file.lastModified() < TimeUnit.MINUTES.toMillis(freshMinutes)) return@withContext file.readText()
        val fetched = runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android)").build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()?.takeIf { it.length <= MAX_CHARS }
            }
        }.getOrNull()
        if (fetched != null) { runCatching { file.writeText(fetched) }; fetched }
        else if (file.isFile) file.readText() else throw IllegalStateException("Schedule unavailable")
    }

    companion object {
        private const val MAX_CHARS = 4_000_000
        private const val DAY = 86_400_000L

        /** Words a provider uses for a racing session, any of which names it in a slot. */
        private fun sessionWords(name: String): List<String> {
            val n = name.lowercase()
            return when {
                n.startsWith("fp") || n.contains("practice") -> listOf("practice", n.replace(" ", ""))
                n.contains("qual") || n.startsWith("q") -> listOf("qualifying", "quali")
                n.contains("sprint") || n == "spr" -> listOf("sprint")
                else -> listOf("race", "grand prix", "gp")
            }
        }

        /** ESPN racing (IndyCar, NASCAR): each competition of the meeting is a session. */
        fun parseRacing(sport: Sport, text: String): List<SportEvent> {
            val root = JSONObject(text)
            val leagueLogo = root.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.takeIf(String::isNotBlank)
            val league = root.optJSONArray("leagues")?.optJSONObject(0)?.optString("name")?.takeIf(String::isNotBlank) ?: sport.label
            return root.optJSONArray("events").objects().flatMap { event ->
                val name = event.optString("name")
                // "NASCAR Cup Series at Miami" → "Miami"; "Grand Prix of Monterey" → "Monterey".
                val place = name.substringAfterLast(" at ", "").ifBlank { name.substringAfterLast(" of ", "") }.ifBlank { name }.trim()
                val competitions = event.optJSONArray("competitions").objects()
                competitions.mapNotNull { c ->
                    val start = parseTime(c.optString("date")) ?: return@mapNotNull null
                    val session = c.optJSONObject("type")?.optString("abbreviation")?.takeIf { it.isNotBlank() && it != "null" } ?: "Race"
                    val state = c.optJSONObject("status")?.optJSONObject("type")?.optString("state")
                    val names = c.optJSONArray("geoBroadcasts").objects().mapNotNull { it.optJSONObject("media")?.optString("shortName")?.takeIf(String::isNotBlank) } +
                        c.optJSONArray("broadcasts").objects().flatMap { b -> b.optJSONArray("names").strings() }
                    SportEvent("espn:${sport.key}:${event.optString("id")}:${c.optString("id")}", sport.key, league,
                        if (competitions.size > 1) "$name · $session" else name, emptyList(), start, null,
                        live = state == "in", finished = state == "post", broadcasters = names.distinct(),
                        wordGroups = listOf(listOf(sport.label, league), listOf(place, name), sessionWords(session)).let { if (competitions.size > 1) it else it.dropLast(1) },
                        leagueLogo = leagueLogo, meeting = "${sport.key}:${event.optString("id")}", session = session)
                }
            }
        }

        /** MotoGP's own schedule: every premier-class session of each Grand Prix. */
        fun parseMotoGp(text: String): List<SportEvent> {
            val names = mapOf("FP1" to "Practice 1", "FP2" to "Practice 2", "PR" to "Practice", "Q1" to "Qualifying 1",
                "Q2" to "Qualifying 2", "SPR" to "Sprint", "WUP" to "Warm Up", "RAC" to "Race")
            return JSONArray(text).objects().filter { it.optString("kind") == "GP" }.flatMap { gp ->
                val place = gp.optString("additional_name").lowercase().replaceFirstChar(Char::uppercase)
                val country = gp.optJSONObject("circuit")?.optString("country").orEmpty()
                val title = (gp.optString("additional_name").takeIf(String::isNotBlank)?.let { "$place Grand Prix" } ?: gp.optString("name").trim())
                gp.optJSONArray("broadcasts").objects()
                    .filter { it.optString("type") == "SESSION" && it.optJSONObject("category")?.optString("acronym") == "MGP" && it.optString("shortname") != "WUP" }
                    .mapNotNull { b ->
                        val start = parseTime(b.optString("date_start")) ?: return@mapNotNull null
                        val short = b.optString("shortname")
                        val session = names[short] ?: b.optString("name").trim()
                        val status = b.optString("status")
                        SportEvent("motogp:${b.optString("id")}", "motogp", "MotoGP", "$title · $session", emptyList(), start,
                            parseTime(b.optString("date_end")), live = b.optBoolean("live") || status == "STARTED",
                            finished = status == "FINISHED", broadcasters = emptyList(),
                            wordGroups = listOf(listOf("MotoGP", "Moto GP"), listOfNotNull(place.takeIf(String::isNotBlank), country.takeIf(String::isNotBlank)), sessionWords(short)),
                            meeting = "motogp:${gp.optString("id")}", session = session)
                    }
            }
        }

        fun parseScoreboard(sport: Sport, text: String): List<SportEvent> {
            val root = JSONObject(text)
            val league = root.optJSONArray("leagues")?.optJSONObject(0)?.optString("name")?.takeIf(String::isNotBlank) ?: sport.label
            val events = root.optJSONArray("events") ?: return emptyList()
            val leagueLogo = root.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.takeIf(String::isNotBlank)
            return (0 until events.length()).mapNotNull { i ->
                val event = events.optJSONObject(i) ?: return@mapNotNull null
                val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: return@mapNotNull null
                val start = parseTime(event.optString("date")) ?: return@mapNotNull null
                val sides = competition.optJSONArray("competitors").objects().map { side ->
                    val team = side.optJSONObject("team") ?: side.optJSONObject("athlete") ?: JSONObject()
                    Competitor(EventMatcher.variants(team.optString("displayName"), team.optString("shortDisplayName"),
                        team.optString("name")), team.optString("logo").takeIf(String::isNotBlank), colour(team.optString("color")),
                        team.optString("shortDisplayName").takeIf { it.isNotBlank() && it != "null" })
                }
                val state = competition.optJSONObject("status")?.optJSONObject("type")?.optString("state")
                    ?: event.optJSONObject("status")?.optJSONObject("type")?.optString("state")
                val names = competition.optJSONArray("geoBroadcasts").objects()
                    .mapNotNull { it.optJSONObject("media")?.optString("shortName")?.takeIf(String::isNotBlank) } +
                    competition.optJSONArray("broadcasts").objects().flatMap { b -> b.optJSONArray("names").strings() }
                // Playoff series and in-season tournaments (guessed from ESPN's playoff payloads; not in the November
                // samples): "series.title" ("East 1st Round") with the two teams, else a note headline ("NBA Cup · West Group B").
                val series = competition.optJSONObject("series")?.optString("title")?.takeIf { it.isNotBlank() && it != "null" }
                    ?.let { title -> title + ": " + sides.mapNotNull { it.shortName ?: it.names.firstOrNull() }.sorted().joinToString(" v ") }
                val note = competition.optJSONArray("notes").objects().firstNotNullOfOrNull { n -> n.optString("headline").takeIf { it.isNotBlank() && it != "null" } }
                SportEvent("espn:${sport.key}:${event.optString("id")}", sport.key, league, event.optString("name"), sides,
                    start, null, live = state == "in", finished = state == "post", broadcasters = names.distinct(), leagueLogo = leagueLogo,
                    group = series ?: note)
            }
        }

        /**
         * ESPN's cricket feed. A match over several days (a Test) stays "in" between days, with the summary
         * "Stumps": then it isn't on, and its next day's play is what's coming. Its card is the current day's play.
         */
        fun parseCricket(text: String, now: Long = System.currentTimeMillis()): List<SportEvent> {
            val leagues = JSONObject(text).optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues") ?: return emptyList()
            return leagues.objects().flatMap { league ->
                league.optJSONArray("events").objects().mapNotNull { event ->
                    val start = parseTime(event.optString("date")) ?: return@mapNotNull null
                    val sides = event.optJSONArray("competitors").objects().map { side ->
                        Competitor(EventMatcher.variants(side.optString("displayName"), side.optString("shortDisplayName").takeIf { it != "null" }),
                            side.optString("logo").takeIf { it.isNotBlank() && it != "null" }, colour(side.optString("color")))
                    }
                    val status = event.optString("status")
                    val summary = event.optString("summary")
                    // "2nd Test, Sri Lanka tour of Pakistan at Rawalpindi, Nov 17-21 2026" → "2nd Test".
                    val what = event.optString("description").substringBefore(",").trim().takeIf { it.isNotBlank() && it != "null" }
                    // The feed's end date is the last day plus one, at 23:59: a Test runs 3+ calendar days.
                    val end = parseTime(event.optString("endDate"))
                    // (A one-day game's end is under 2½ days after its start; a four-day game's is 4¾.)
                    val days = if (end != null && end - start > 3 * DAY) ((end - start) / DAY).toInt() else 1
                    val stumps = status == "in" && summary.startsWith("Stumps", true)
                    val today = ((now - start).coerceAtLeast(0) / DAY).toInt()
                    // At stumps the next day's play is what's coming, unless that was the last day.
                    val over = stumps && today + 1 >= days
                    val day = if (days > 1 && status != "pre") (today + if (stumps && !over) 1 else 0).coerceAtMost(days - 1) else 0
                    SportEvent("espn:cricket:${event.optString("id")}", "cricket", league.optString("name"), event.optString("name"), sides,
                        start + day * DAY, if (days > 1) null else end, live = status == "in" && !stumps, finished = status == "post" || over,
                        broadcasters = emptyList(), meeting = if (days > 1) "cricket:${event.optString("id")}" else null,
                        session = listOfNotNull(what, if (days > 1 && status != "pre") "Day ${day + 1}" else null).joinToString(" · ").ifBlank { null },
                        firstDay = if (days > 1) start else null, days = days)
                }
            }
        }

        /** "c4ced4" or "#3f8108" → ARGB; null for blank or black-on-black ("000000" reads as no colour). */
        private fun colour(hex: String?): Long? = hex?.removePrefix("#")?.takeIf { it.length == 6 && it != "null" }
            ?.toLongOrNull(16)?.let { 0xFF000000L or it }

        private fun parseTime(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")).toInstant().toEpochMilli() }.getOrNull()
            // MotoGP: "2026-11-19T15:30:00+0000" and "2026-11-27T08:00:00+01:00".
            ?: runCatching { java.time.OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX")).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }
}
