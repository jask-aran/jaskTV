package app.opentv.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * The live side of jaskTV's own downloader (DownloadService runs it): what the running download is doing this second,
 * and whether it must stand aside.
 *
 * Why its own downloader: Android's DownloadManager is a background job, and on the phone it was stopped every time
 * the screen went off (light Doze, about two minutes after), so a queue of episodes only moved while the screen was on
 * (2026-11-20: three hours for four episodes). A foreground service keeps its network through Doze.
 *
 * Why it stands aside: IPTV accounts often allow one connection (this one: max_connections 1). Playing anything from
 * the provider while a download runs gets one of them refused, so a download pauses while the player is playing from
 * the same server and carries on (from where it was) after. Previews and checks skip that server meanwhile.
 */
object DownloadEngine {
    enum class Phase { CONNECTING, RUNNING, RETRY_WAIT, HELD, NO_NETWORK }

    data class Live(
        val phase: Phase,
        val bytes: Long,
        val total: Long,
        /** Bytes a second, smoothed over the last few seconds; 0 until measured. */
        val speed: Double = 0.0,
        /** RETRY_WAIT: when the next try is, and what went wrong. */
        val retryAt: Long = 0,
        val why: String? = null,
    )

    private val _live = MutableStateFlow<Map<Long, Live>>(emptyMap())
    val live: StateFlow<Map<Long, Live>> = _live.asStateFlow()
    fun set(id: Long, live: Live?) { _live.value = if (live == null) _live.value - id else _live.value + (id to live) }

    /** The server the running download is using, while it runs (not while it stands aside). */
    @Volatile var busyHost: String? = null
        internal set

    /** The server the player is playing from; a download from it waits. Null when nothing (or a local file) plays. */
    @Volatile var playingHost: String? = null
        private set

    /** Called by the player (not previews): [url] started playing, or null when it stopped. */
    fun playing(url: String?) { playingHost = url?.let(::hostOf) }

    /** Whether a preview or a check may open a connection to [url] now (not while a download holds that server). */
    fun mayConnect(url: String): Boolean { val busy = busyHost; return busy == null || busy != hostOf(url) }

    /** Downloads cancelled while running (deleted from the list): the transfer stops and drops its partial file. */
    val cancelled: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())

    fun hostOf(url: String): String? = runCatching { java.net.URI(url).let { u -> u.host?.let { h -> "$h:${u.port}" } } }.getOrNull()

    /**
     * What's known about each download beyond the database row (no schema change): its size, what the file is
     * ("1080p · HEVC"), when it finished, why it last failed. A small JSON file in the app's private storage.
     */
    class Notes(dir: File) {
        private val file = File(dir, "jask-downloads.json")
        private val root: JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

        @Synchronized fun get(id: Long): JSONObject? = root.optJSONObject(id.toString())
        @Synchronized fun put(id: Long, key: String, value: Any?) {
            val o = root.optJSONObject(id.toString()) ?: JSONObject().also { root.put(id.toString(), it) }
            if (value == null) o.remove(key) else o.put(key, value)
            runCatching { val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(root.toString()); tmp.renameTo(file) }
        }
        @Synchronized fun forget(id: Long) { root.remove(id.toString()); runCatching { file.writeText(root.toString()) } }
    }
}
