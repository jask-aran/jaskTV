package app.opentv.remote

import app.opentv.player.PlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * What this device is playing, and the player playing it. Every player registers through BindPlayerPlayback,
 * so the account's other devices can see it (presence) and a phone on the same Wi-Fi can control it (remote).
 */
object NowPlaying {
    /** [mediaKey]: "movie:<id>", "ep:<id>" or "rec:<id>" for the film player; [channelId] for live TV. */
    data class Item(val mediaKey: String? = null, val channelId: Long? = null, val title: String = "")
    class Session(val item: Item, val engine: PlayerEngine)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    fun set(item: Item, engine: PlayerEngine) { _session.value = Session(item, engine) }
    fun clear(engine: PlayerEngine) { if (_session.value?.engine === engine) _session.value = null }
}

/**
 * Something to play, named the same way on every device: a playlist's sync uid and the provider's own ids.
 * Each device plays it from its own copy of the playlist (its own login), so "casting" sends this, not video.
 */
@Serializable
data class CastItem(
    /** movie, episode or live */
    val kind: String,
    val playlist: String,
    /** Provider stream id (film, channel) or episode id. */
    val id: String,
    /** For an episode: its show's id, so a device that hasn't opened the show can fetch its episodes. */
    val series: String? = null,
    val title: String = "",
    val positionMs: Long = 0,
    /** The sender's duration, so a device that has never played this doesn't think the resume spot is the end. */
    val durationMs: Long = 0,
)

/** A remote's request to a device on the same network. */
@Serializable
data class RemoteCommand(
    /** key (Android key code), playPause, pause, release (stop the stream), seek (ms, ±), seekTo (ms), volume (±1), stop, cast */
    val type: String,
    val key: Int? = null,
    val ms: Long? = null,
    val cast: CastItem? = null,
)
