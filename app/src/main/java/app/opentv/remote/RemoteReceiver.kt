package app.opentv.remote

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Takes remote commands from the account's other devices on the same Wi-Fi (they arrive sealed with the account
 * key; see LanLink). Keys go to the app's own window as if pressed on this device's remote, so they work while
 * jaskTV is in front; play, pause and seek go straight to the player; volume is the device's media volume.
 */
object RemoteReceiver {
    private var activity: WeakReference<Activity>? = null
    /** Whether the app is in front (only then can keys be pressed in it). */
    @Volatile var inFront = false
        private set

    fun attach(a: Activity) { activity = WeakReference(a); inFront = true }
    fun detach(a: Activity) { if (activity?.get() === a) inFront = false }

    suspend fun handle(context: Context, from: String, c: RemoteCommand, resolve: suspend (CastItem) -> RemoteSignals.Play?) {
        when (c.type) {
            "cast" -> c.cast?.let { resolve(it) }?.let(RemoteSignals::play)
            "volume" -> (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if ((c.ms ?: 1) > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            else -> withContext(Dispatchers.Main) {
                val engine = NowPlaying.session.value?.engine
                when (c.type) {
                    "playPause" -> if (engine != null) engine.setPlaying(!engine.status.value.isPlaying) else press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    "pause" -> engine?.setPlaying(false)
                    // Another device takes over: close the stream (one-stream providers), not just pause it.
                    "release" -> engine?.stop()
                    "seek" -> engine?.seekBy(c.ms ?: 0)
                    "seekTo" -> engine?.seekTo(c.ms ?: 0)
                    "stop" -> press(KeyEvent.KEYCODE_BACK)
                    "key" -> c.key?.takeIf { it in ALLOWED }?.let(::press)
                }
            }
        }
    }

    private fun press(code: Int) {
        val a = activity?.get()?.takeIf { inFront } ?: return
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Only remote-control keys: nothing that could type or launch elsewhere. */
    private val ALLOWED = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE)
}

/** Something to open in a player, asked for by another device (cast, or "watch here"). The nav graph plays it. */
object RemoteSignals {
    sealed interface Play {
        data class Live(val channelId: Long) : Play
        data class Vod(val mediaKey: String, val url: String, val title: String, val userAgent: String) : Play
    }
    private val _pending = MutableStateFlow<Play?>(null)
    val pending: StateFlow<Play?> = _pending.asStateFlow()
    fun play(p: Play) { _pending.value = p }
    fun consume() { _pending.value = null }
}
