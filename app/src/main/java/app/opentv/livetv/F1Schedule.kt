package app.opentv.livetv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** Scheduling is independent of broadcasters, guide listings and working stream availability. */
data class F1Session(val id: String, val grandPrix: String, val session: String, val startUtcMillis: Long,
    val scheduledEndUtcMillis: Long? = null) {
    val searchWindowEnd: Long get() = scheduledEndUtcMillis ?: startUtcMillis + 3 * 3_600_000
}

class F1Schedule(context: Context) {
    private val cacheDir = context.applicationContext.cacheDir
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    data class Result(val sessions: List<F1Session>, val stale: Boolean)
    suspend fun upcoming(now: Long = System.currentTimeMillis()): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            val year = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year
            var stale = false
            val sessions = (year..year + 1).flatMap { season ->
                val file = File(cacheDir, "jask-f1-$season.json")
                val fresh = file.isFile && now - file.lastModified() < TimeUnit.HOURS.toMillis(6)
                var text = if (file.isFile) runCatching { file.readText().takeIf { it.length <= MAX_BYTES } }.getOrNull() else null
                if (!fresh && now >= (retryAfter[season] ?: 0)) {
                    val fetched = runCatching {
                        val url = "https://api.jolpi.ca/ergast/f1/$season/races/?limit=100"
                        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            if (!response.isSuccessful) null else response.body?.byteStream()?.use { input ->
                                val bytes = input.readBytesBounded(MAX_BYTES)
                                bytes.toString(Charsets.UTF_8).also { parse(it) }
                            }
                        }
                    }.getOrNull()
                    if (fetched != null) {
                        text = fetched
                        runCatching { file.writeText(fetched) }
                    } else { stale = true; retryAfter[season] = now + 15 * 60_000 }
                } else if (!fresh) stale = true
                text?.let { runCatching { parse(it) }.getOrNull() }.orEmpty()
            }.distinctBy { it.id }.filter { it.startUtcMillis >= now - 3 * 3_600_000 && it.startUtcMillis <= now + TimeUnit.DAYS.toMillis(45) }
                .sortedBy { it.startUtcMillis }.take(24)
            Result(sessions, stale)
        }
    }
    companion object {
        private const val MAX_BYTES = 512 * 1024
        private val mutex = Mutex()
        private val retryAfter = mutableMapOf<Int, Long>()
        fun recognizes(query: String): Boolean = Regex("\\b(f1|formula\\s*(1|one))\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)
        fun parse(text: String): List<F1Session> {
            val races = JSONObject(text).getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races")
            val result = mutableListOf<F1Session>()
            for (i in 0 until minOf(races.length(), 100)) {
                val race = races.getJSONObject(i)
                val name = race.optString("raceName").takeIf(String::isNotBlank) ?: continue
                val fields = listOf("FirstPractice" to "Practice 1", "SecondPractice" to "Practice 2", "ThirdPractice" to "Practice 3",
                    "Qualifying" to "Qualifying", "SprintQualifying" to "Sprint qualifying", "SprintShootout" to "Sprint qualifying", "Sprint" to "Sprint", "Race" to "Race")
                for ((key, label) in fields) {
                    val item = if (key == "Race") race else race.optJSONObject(key) ?: continue
                    val date = item.optString("date")
                    val time = item.optString("time")
                    // A date without a known UTC time must never silently become midnight.
                    val start = runCatching { Instant.parse("${date}T$time").toEpochMilli() }.getOrNull() ?: continue
                    result += F1Session("f1:${race.optString("season")}:${race.optString("round")}:$label", name, label, start)
                }
            }
            return result.distinctBy { it.id }
        }
    }
}

private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(output.size() + n <= max) { "Schedule response exceeds size limit" }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
