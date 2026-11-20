package app.opentv.ui.channels

/**
 * The channels a Live TV or Sports pick could have played instead, in the order they were weighed. The player asks
 * for the next one when what it opened turns out to show no picture (a black screen the stream check can't see).
 * Only answers for a channel in the current chain, so playing anything else never jumps to a stale list.
 *
 * Also what the pick was, so the player's findings go to the right record (StreamHealth): which game, and which way
 * to watch it each channel belongs to.
 */
internal object LiveFallback {
    /** One way to watch a game: its sport and title ("f1", "Apple TV F1"). */
    data class Way(val sport: String, val title: String)

    @Volatile private var chain: List<Long> = emptyList()
    @Volatile private var ways: Map<Long, Way> = emptyMap()
    @Volatile var label: String? = null
        private set
    /** The game this pick was for, and when it started: backing out quickly to another way says this one was wrong. */
    @Volatile var event: String? = null
        private set
    @Volatile var startedAt = 0L
        private set
    /** Chosen by the user from the sheet's frames (a stronger signal once they stay). Set just before the pick. */
    @Volatile var picked = false

    fun set(chosen: Long, rest: List<Long>, what: String?, event: String? = null, ways: Map<Long, Way> = emptyMap()) {
        chain = (listOf(chosen) + rest).distinct(); label = what
        this.event = event; this.ways = ways; startedAt = System.currentTimeMillis()
    }

    fun next(current: Long?): Long? {
        val at = chain.indexOf(current ?: return null).takeIf { it >= 0 } ?: return null
        return chain.drop(at + 1).firstOrNull { !NoPicture.parked(it) }
    }

    fun wayOf(channelId: Long): Way? = ways[channelId]
}
