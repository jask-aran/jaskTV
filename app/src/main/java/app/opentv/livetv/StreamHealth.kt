package app.opentv.livetv

import android.content.Context
import app.opentv.data.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

/**
 * How well each live stream has worked on this device, learned from the app's own checks over time, so a channel
 * that keeps working is picked straight away and one that keeps failing goes to the back.
 *
 * Two records:
 *  - per channel (provider source + stream id, never the URL): did it start and show a real picture;
 *  - per "way to watch" a sport (sport + option title, "f1|Apple TV F1"): did it work, and did the user stay on it.
 *    A provider's event slot carries a different event each week, so its own history means little, but the group
 *    it belongs to ("Apple TV F1" dead for both practices) is a real pattern, and so is a guide-listed channel
 *    that showed something else (Fox Sports 506 during Practice 2), which only the user leaving reveals.
 *
 * Results fade with a 10-day half-life, so a channel that was dead last month gets another chance. Kept in an
 * app-private file (not the database), on this device only.
 */
object StreamHealth {
    private const val HALF_LIFE = 10 * 24 * 3_600_000.0
    private const val FILE = "jask-stream-health.json"

    /** Weights: what each observation is worth. */
    const val PICTURE = 1f       // a real picture on screen
    const val WATCHED = 1.5f     // the user stayed on it for minutes
    const val PICKED = 1f        // chosen by the user from the frame picker (on top of the picture)
    const val FLAT = 1f          // black or flat frames
    const val NO_FRAME = 1f      // no frame at all in time
    const val SILENT = 0.4f      // the quick byte check got nothing (weak: a busy connection looks the same)
    const val LEFT = 1f          // the user backed out within seconds and played another way to the same game

    private class Tally(var good: Double, var bad: Double, var at: Long)

    private val channels = HashMap<String, Tally>()
    private val ways = HashMap<String, Tally>()
    @Volatile private var loaded = false
    private var file: File? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saving: Job? = null

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val f = File(context.applicationContext.filesDir, FILE).also { file = it }
            runCatching {
                if (f.isFile) {
                    val root = JSONObject(f.readText())
                    fun read(name: String, into: HashMap<String, Tally>) {
                        val o = root.optJSONObject(name) ?: return
                        o.keys().forEach { k -> o.optJSONArray(k)?.let { a -> into[k] = Tally(a.optDouble(0), a.optDouble(1), a.optLong(2)) } }
                    }
                    read("channels", channels); read("ways", ways)
                }
            }
            loaded = true
        }
    }

    fun key(channel: Channel) = "${channel.sourceId}:${channel.streamId}"
    fun wayKey(sport: String, title: String) = "$sport|${title.lowercase()}"

    fun good(channel: Channel, weight: Float = PICTURE) = add(channels, key(channel), weight.toDouble(), 0.0)
    fun bad(channel: Channel, weight: Float) = add(channels, key(channel), 0.0, weight.toDouble())
    fun wayGood(sport: String, title: String, weight: Float = PICTURE) = add(ways, wayKey(sport, title), weight.toDouble(), 0.0)
    fun wayBad(sport: String, title: String, weight: Float) = add(ways, wayKey(sport, title), 0.0, weight.toDouble())

    /** A channel's record: the chance it works (a fair coin when unknown) and how much evidence there is. */
    fun of(channel: Channel): Record = record(channels, key(channel))
    fun ofWay(sport: String, title: String): Record = record(ways, wayKey(sport, title))

    data class Record(val score: Float, val evidence: Float) {
        /** Worked often enough, recently enough, to play without checking first. */
        val trusted get() = evidence >= 2.5f && score >= .75f
        /** Failed often enough to go to the back. */
        val poor get() = evidence >= 1.5f && score <= .3f
    }

    private fun decayed(t: Tally, now: Long): Pair<Double, Double> {
        val f = 0.5.pow((now - t.at).coerceAtLeast(0) / HALF_LIFE)
        return t.good * f to t.bad * f
    }

    @Synchronized private fun record(map: HashMap<String, Tally>, key: String): Record {
        val t = map[key] ?: return Record(.5f, 0f)
        val (g, b) = decayed(t, System.currentTimeMillis())
        // One imagined success and one failure: a single result can't take a channel to certainty.
        return Record(((g + 1) / (g + b + 2)).toFloat(), (g + b).toFloat())
    }

    @Synchronized private fun add(map: HashMap<String, Tally>, key: String, good: Double, bad: Double) {
        val now = System.currentTimeMillis()
        val t = map[key]
        if (t == null) map[key] = Tally(good, bad, now)
        else { val (g, b) = decayed(t, now); t.good = g + good; t.bad = b + bad; t.at = now }
        android.util.Log.i("jaskHealth", "$key +${good} -${bad} → ${record(map, key)}")
        scheduleSave()
    }

    private fun scheduleSave() {
        saving?.cancel()
        saving = io.launch {
            delay(2_000)
            val f = file ?: return@launch
            val text = synchronized(this@StreamHealth) {
                val now = System.currentTimeMillis()
                fun write(map: HashMap<String, Tally>) = JSONObject().apply {
                    // Drop what has faded to nothing, so the file doesn't grow forever.
                    map.forEach { (k, t) -> val (g, b) = decayed(t, now); if (g + b >= .05) put(k, org.json.JSONArray().put(g).put(b).put(now)) }
                }
                JSONObject().put("channels", write(channels)).put("ways", write(ways)).toString()
            }
            runCatching { val tmp = File(f.parentFile, "$FILE.tmp"); tmp.writeText(text); tmp.renameTo(f) }
        }
    }

    /** Debug builds: forget everything (emulator test plays shouldn't seed a phone's record). */
    @Synchronized fun clear() { channels.clear(); ways.clear(); scheduleSave() }
}
